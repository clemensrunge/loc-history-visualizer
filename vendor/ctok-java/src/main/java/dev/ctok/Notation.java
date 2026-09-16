package dev.ctok;

import java.nio.ByteBuffer;
import java.nio.charset.*;

final class Notation {
    static final String B="\ufdd0", E="\ufdd1", S="\ufdd3", C="\ufdd4", PAD="⟨pad⟩";
    static final String[] ATOMS={"⟨bow⟩","⟨eow⟩","⟨pad⟩","⟨shift⟩","⟨caps⟩"};
    static boolean marker(int cp) { return cp==0xfdd0 || cp==0xfdd1 || cp==0xfdd3 || cp==0xfdd4; }
    static int literal(int cp) { return cp>=0xe000 && cp<=0xe004 ? 0xfdd0+cp-0xe000 : cp; }
    static byte[] utf8(int cp) { return new String(Character.toChars(cp)).getBytes(StandardCharsets.UTF_8); }
    static String bytes(byte[] b) {
        StringBuilder out=new StringBuilder();
        for(byte v:b) out.append(String.format("⟨0x%02X⟩",v&255));
        return out.toString();
    }
    static String renderBytes(byte[] b) {
        try { return escape(StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(b)).toString()); }
        catch(CharacterCodingException e) { return bytes(b); }
    }
    static String escape(String s) {
        StringBuilder out = new StringBuilder();
        s.codePoints().forEach(cp -> {
            if (cp == 0x27e8 || cp == 0x27e9) out.append(bytes(utf8(cp)));
            else out.appendCodePoint(cp);
        });
        return out.toString();
    }
    static String render(String s) {
        StringBuilder out=new StringBuilder();
        s.codePoints().forEach(cp -> {
            if(cp>=0xe000 && cp<=0xe004) out.append(bytes(utf8(literal(cp))));
            else if(cp>=0xfdd0 && cp<=0xfdd4) out.append(ATOMS[cp-0xfdd0]);
            else out.append(escape(new String(Character.toChars(cp))));
        });
        return out.toString();
    }
    static String parse(String s) {
        for(int i=0;i<ATOMS.length;i++) if(i!=2) s=s.replace(ATOMS[i],new String(Character.toChars(0xfdd0+i)));
        java.util.regex.Matcher m=java.util.regex.Pattern.compile("(?:⟨0x[0-9A-Fa-f]{2}⟩)+").matcher(s);
        StringBuilder out=new StringBuilder();
        while(m.find()) {
            String run=m.group(); byte[] b=new byte[run.length()/6];
            for(int i=0;i<b.length;i++) b[i]=(byte)Integer.parseInt(run.substring(i*6+3,i*6+5),16);
            m.appendReplacement(out,java.util.regex.Matcher.quoteReplacement(new String(b,StandardCharsets.UTF_8)));
        }
        m.appendTail(out); return out.toString();
    }
}
