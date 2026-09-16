package dev.ctok;

import java.text.Normalizer;
import java.util.*;
import static dev.ctok.UnicodeData.*;
import static dev.ctok.Notation.*;

final class TextNormalizer {
    private record Run(int cls,String body) {}
    static String normalize(String text,boolean quotes) {
        StringBuilder safe=new StringBuilder();
        for(int i=0;i<text.length();i++) {
            char c=text.charAt(i);
            if(Character.isHighSurrogate(c) && i+1<text.length() && Character.isLowSurrogate(text.charAt(i+1))) { safe.append(c).append(text.charAt(++i)); }
            else safe.append(Character.isSurrogate(c)?'\ufffd':c);
        }
        String n=Normalizer.normalize(safe,Normalizer.Form.NFC).replace("\u0e4d\u0e32","\u0e33");
        StringBuilder out=new StringBuilder();
        n.codePoints().forEach(cp -> {
            if((cp>=1&&cp<=8)||(cp>=11&&cp<=31)||(cp>=127&&cp<=159)||(cp>=0xe000&&cp<=0xf8ff)) return;
            if(cp==0 || cp==0xa0 || cp==0x1680 || (cp>=0x2000&&cp<=0x200a) || cp==0x2028 || cp==0x2029 || cp==0x202f || cp==0x205f) cp=32;
            if(quotes) { if(cp==0x2018||cp==0x2019) cp=39; if(cp==0x201c||cp==0x201d) cp=34; }
            out.appendCodePoint(cp);
        });
        return out.toString();
    }
    static String markCase(String span,int caps,boolean fused) {
        if(fused || span.contains("ẞ")) return span;
        int[] a=span.codePoints().toArray();
        if(span.contains("İ")) {
            if(a[0]!=0x130 && flag(a[0],8) && Arrays.stream(a).skip(1).noneMatch(cp->cp!=0x130&&flag(cp,8))) {
                StringBuilder out=new StringBuilder(S);
                for(int cp:a) out.append(cp==0x130?"İ":lower(cp));
                return out.toString();
            }
            return span;
        }
        boolean cased=false,upper=true,unlowerable=false;
        StringBuilder lowered=new StringBuilder();
        for(int cp:a) {
            boolean u=flag(cp,8),l=flag(cp,16);
            cased |= u||l; if(l&&!u) upper=false;
            if(flag(cp,32)&&!l&&(!u||lower(cp).equals(new String(Character.toChars(cp))))) unlowerable=true;
            lowered.append(lower(cp));
        }
        if(caps>0&&cased&&upper&&a.length>=caps&&!unlowerable) return C+lowered.toString().replace('ς','σ');
        if(flag(a[0],8)&&Arrays.stream(a).skip(1).noneMatch(cp->flag(cp,8))) return S+lowered;
        return span;
    }
    static List<Run> runs(String s) {
        List<Run> out=new ArrayList<>(); int current=-1; StringBuilder body=new StringBuilder();
        for(int cp:s.codePoints().toArray()) {
            int c=cls(cp);
            if(c==WORD&&flag(cp,1)&&current!=WORD) c=STRAY;
            if(c!=current) { if(current>=0) out.add(new Run(current,body.toString())); body.setLength(0); current=c; }
            body.appendCodePoint(cp);
        }
        if(current>=0) out.add(new Run(current,body.toString()));
        List<Run> split=new ArrayList<>();
        for(Run r:out) {
            if(r.cls!=HARD) { split.add(r); continue; }
            body.setLength(0); int kind=-1;
            for(int cp:r.body.codePoints().toArray()) {
                int k=punct(cp)?0:flag(cp,4)?1:2;
                if(body.length()>0 && !selector(cp)&&k!=kind) { split.add(new Run(HARD,body.toString())); body.setLength(0); }
                if(body.length()==0) kind=k;
                body.appendCodePoint(cp);
            }
            split.add(new Run(HARD,body.toString()));
        }
        return split;
    }
    static int first(String s) { return s.codePointAt(0); }
    static int last(String s) { return s.codePointBefore(s.length()); }
    static boolean hardBow(String s) { return selector(first(s))||punct(first(s)); }
    static boolean hardEow(String s) { return selector(last(s))||punct(last(s)); }
    static boolean digitRun(String s) {
        int cp=first(s),cat=cp<65536?NUMBERS[cp]:0;
        // Astral digits never take borders, so their category cannot affect the stream.
        return cat!=0&&s.codePoints().allMatch(c->c<65536&&NUMBERS[c]==cat);
    }
    static boolean digitBow(String s) { return digitRun(s)&&flag(first(s),4); }
    static boolean digitEow(String s) { return digitRun(s)&&flag(last(s),4); }
    static boolean opens(List<Run> runs,int i) { return runs.get(i).body.equals("'")&&i+1<runs.size()&&(runs.get(i+1).cls==WORD||runs.get(i+1).cls==STRAY); }
    static boolean rightBorder(Run r) { return r.cls==PUNCT||hardEow(r.body)||((r.cls==DIGIT||r.cls==HARD)&&digitEow(r.body)); }
    static boolean contraction(List<Run> runs,int i) {
        return i>0&&Set.of("s","t","d","m","ll","re","ve").contains(runs.get(i).body)&&runs.get(i-1).cls==PUNCT&&runs.get(i-1).body.equals("'")&&(i<2||!rightBorder(runs.get(i-2)));
    }
    static boolean borders(List<Run> runs,int i,int side,boolean frame) {
        int j=i+side;
        if(j<0) return frame;
        if(j>=runs.size()||runs.get(j).cls!=SPACE) return false;
        String b=runs.get(j).body;
        return side<0?b.endsWith(" "):b.startsWith(" ")&&!b.startsWith("  ");
    }
    static String stream(String norm,Ctok model,boolean rawHead) {
        StringBuilder escaped=new StringBuilder();
        norm.codePoints().forEach(cp->escaped.appendCodePoint(cp>=0xfdd0&&cp<=0xfdd4?0xe000+cp-0xfdd0:cp));
        norm=escaped.toString().replaceAll("\n+$","");
        if(model.frameBow()&&rawHead&&norm.startsWith(" ")&&!norm.startsWith("  ")) norm=norm.substring(1);
        List<Run> runs=runs(norm);
        if(runs.isEmpty()) return model.frameBow()?B:"";
        Run f=runs.get(0); boolean quote=opens(runs,0);
        boolean own=!quote&&(f.cls==WORD||f.cls==PUNCT||f.cls==STRAY||hardBow(f.body)||((f.cls==DIGIT||f.cls==HARD)&&digitBow(f.body))||(f.cls==SPACE&&f.body.startsWith(" ")));
        StringBuilder out=new StringBuilder(own||!model.frameBow()?"":quote?" ":B);
        for(int i=0;i<runs.size();i++) {
            Run r=runs.get(i); String b=r.body;
            if(r.cls==WORD) {
                boolean fused=i>0&&runs.get(i-1).cls==STRAY;
                String n=markCase(b,model.capsMinimum(),fused),pre="";
                while(n.startsWith(S)||n.startsWith(C)) { pre+=n.charAt(0); n=n.substring(1); }
                out.append(pre).append(fused||contraction(runs,i)?"":B).append(n).append(E);
            } else if(r.cls==STRAY) out.append(B).append(b).append(i+1<runs.size()&&runs.get(i+1).cls==WORD?"":E);
            else if(r.cls==PUNCT||hardBow(b)||hardEow(b)) {
                if(borders(runs,i,-1,model.frameBow())&&!opens(runs,i)&&(r.cls==PUNCT||hardBow(b))) out.append(B);
                out.append(b);
                if(borders(runs,i,1,model.frameBow())&&(r.cls==PUNCT||hardEow(b))) out.append(E);
            } else if((r.cls==DIGIT||r.cls==HARD)&&digitRun(b)) {
                if(digitBow(b)&&borders(runs,i,-1,model.frameBow())) out.append(B);
                out.append(b);
                if(digitEow(b)&&borders(runs,i,1,model.frameBow())) out.append(E);
            } else out.append(b);
        }
        return out.toString().replaceAll("(.)"+E+" (["+S+C+"]*)"+B,"$1"+E+"$2"+B);
    }
}
