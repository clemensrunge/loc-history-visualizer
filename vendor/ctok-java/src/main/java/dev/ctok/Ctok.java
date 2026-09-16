package dev.ctok;

import java.io.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import static dev.ctok.Notation.*;

/** Offline reconstruction of Claude's single-user-message token counts.
 * Instances are immutable and safe to share between threads. Token boundaries are approximate.
 */
public final class Ctok {
    /** Recorded upstream evidence for a vocabulary entry. */
    public record Witness(String probe,Integer raw,String kind) {}
    private static final Map<String,Ctok> MODELS=new ConcurrentHashMap<>();
    private static final class Node {
        final Map<Integer,Node> children=new HashMap<>();
        boolean terminal;
    }
    private record Tiling(int cost,int[] points,int[] parents) {}
    private final String family;
    private final int overhead,caps;
    private final boolean quotes,frame;
    private final Node trie=new Node();
    private final Set<String> vocabulary=new HashSet<>(),byteTokens=new HashSet<>();
    private final Map<String,Witness> witnesses;
    private final int maxByteLength;

    private Ctok(String family) {
        this.family=family; frame=!family.equals("v4.8");
        Map<String,Witness> evidence=new LinkedHashMap<>();
        try(DataInputStream in=UnicodeData.open(family.equals("v3")?"v3.bin":"v4_7.bin")) {
            int storedOverhead=in.readInt(); overhead=frame?storedOverhead:6;
            quotes=in.readBoolean(); caps=in.readInt();
            int size=in.readInt();
            for(int i=0;i<size;i++) {
                String group=UnicodeData.readString(in),key=UnicodeData.readString(in);
                Witness w=new Witness(UnicodeData.readString(in),in.readInt(),UnicodeData.readString(in));
                if(w.kind().equals("special")) w=new Witness(null,null,w.kind());
                evidence.put(key,w);
                if(group.equals("bytes_fallback")) byteTokens.add(key);
                else {
                    String parsed=parse(key); vocabulary.add(parsed);
                    if(parsed.codePointCount(0,parsed.length())==1&&!marker(parsed.codePointAt(0))) byteTokens.add(HexFormat.of().formatHex(parsed.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
                    if(group.equals("contractions")) vocabulary.add(parsed+E);
                }
            }
        } catch(IOException e) { throw new UncheckedIOException("Cannot load ctok vocabulary",e); }
        witnesses=Collections.unmodifiableMap(evidence);
        maxByteLength=Math.max(1,byteTokens.stream().mapToInt(s->s.length()/2).max().orElse(1));
        for(String piece:vocabulary) {
            Node node=trie; int[] points=piece.codePoints().toArray();
            for(int i=points.length-1;i>=0;i--) node=node.children.computeIfAbsent(points[i],k->new Node());
            node.terminal=true;
        }
    }
    /** Default Python-compatible family (3.0). Prefer an explicit version in integrations. */
    public static Ctok forVersion() { return forVersion("3.0"); }
    /** Routes [3.0,4.7) to v3, [4.7,4.8) to v4.7, and 4.8+ to v4.8. */
    public static Ctok forVersion(String version) {
        Objects.requireNonNull(version,"version");
        String[] parts=version.split("\\.",-1); int[] values=new int[Math.max(2,parts.length)];
        try { for(int i=0;i<parts.length;i++) values[i]=Integer.parseInt(parts[i].strip()); }
        catch(NumberFormatException e) { throw new IllegalArgumentException("Unknown Claude tokenizer version: "+version,e); }
        String family;
        if(atLeast(values,4,8)) family="v4.8";
        else if(atLeast(values,4,7)) family="v4.7";
        else if(atLeast(values,3,0)) family="v3";
        else throw new IllegalArgumentException("Unknown Claude tokenizer version: "+version);
        return MODELS.computeIfAbsent(family,Ctok::new);
    }
    private static boolean atLeast(int[] values,int major,int minor) {
        int[] base={major,minor};
        for(int i=0;i<values.length;i++) {
            int expected=i<2?base[i]:0;
            if(values[i]!=expected) return values[i]>expected;
        }
        return true;
    }
    /** Resolved reconstruction family. */
    public String family() { return family; }
    /** Fixed message framing cost; content-only counts still include the frame's boundary effects. */
    public int messageOverhead() { return overhead; }
    boolean frameBow() { return frame; }
    int capsMinimum() { return caps; }
    public String normalize(String text) { return TextNormalizer.normalize(Objects.requireNonNull(text,"text"),quotes); }
    /** Diagnostic stream in the public upstream notation. */
    public String markedStream(String text) { return render(TextNormalizer.stream(normalize(text),this,text.startsWith(" "))); }
    /** Vocabulary entries and their recorded witnesses, including byte prefixes. */
    public Map<String,Witness> pieces() { return witnesses; }
    public Witness witness(String piece) {
        Witness w=witnesses.get(Objects.requireNonNull(piece,"piece"));
        if(w==null) throw new NoSuchElementException(piece);
        return w;
    }
    private List<byte[]> byteChunks(int cp) {
        byte[] bytes=utf8(literal(cp)); int n=bytes.length;
        int[] best=new int[n+1],parent=new int[n+1];
        for(int end=1;end<=n;end++) {
            best[end]=Integer.MAX_VALUE;
            for(int start=Math.max(0,end-maxByteLength);start<end;start++) {
                if(end-start==1||byteTokens.contains(HexFormat.of().formatHex(bytes,start,end))) {
                    int candidate=best[start]+1;
                    if(candidate<best[end]) { best[end]=candidate; parent[end]=start; }
                }
            }
        }
        List<byte[]> out=new ArrayList<>();
        for(int end=n;end>0;end=parent[end]) out.add(Arrays.copyOfRange(bytes,parent[end],end));
        Collections.reverse(out); return out;
    }
    private int floor(int cp,Map<Integer,List<byte[]>> cache) {
        return marker(cp)?1:cache.computeIfAbsent(cp,this::byteChunks).size();
    }
    private Tiling tile(String s,Map<Integer,List<byte[]>> cache) {
        int[] a=s.codePoints().toArray(),best=new int[a.length+1],parents=new int[a.length+1];
        for(int end=1;end<=a.length;end++) {
            int start=end-1; Node node=trie.children.get(a[start]);
            best[end]=best[start]+(node!=null&&node.terminal?1:floor(a[start],cache)); parents[end]=start;
            if(node==null) continue;
            for(start=end-2;start>=0;start--) {
                node=node.children.get(a[start]); if(node==null) break;
                if(node.terminal&&best[start]+1<=best[end]) { best[end]=best[start]+1; parents[end]=start; }
            }
        }
        return new Tiling(best[a.length],a,parents);
    }
    private int trailing(String normalized) {
        int n=0; for(int i=normalized.length()-1;i>=0&&normalized.charAt(i)=='\n';i--) n++;
        return n;
    }
    /** Count including the single-message frame, exactly as Python ctok.token_count. */
    public int tokenCount(String text) {
        String norm=normalize(text); Map<Integer,List<byte[]>> cache=new HashMap<>();
        String s=TextNormalizer.stream(norm,this,text.startsWith(" "));
        int n=trailing(norm),tail=n==0?0:tile("\n".repeat(n+2),cache).cost-1;
        return Math.addExact(overhead,Math.addExact(tile(s,cache).cost,tail));
    }
    /** Count with the fixed message overhead subtracted, for source metrics. */
    public int contentTokenCount(String text) { return tokenCount(text)-overhead; }
    /** Rendered tokens including frame pads; list size equals tokenCount. */
    public List<String> tokenize(String text) {
        String norm=normalize(text); Map<Integer,List<byte[]>> cache=new HashMap<>();
        Tiling t=tile(TextNormalizer.stream(norm,this,text.startsWith(" ")),cache);
        List<String> out=new ArrayList<>(Collections.nCopies(overhead,PAD));
        out.addAll(renderTiling(t,cache));
        int n=trailing(norm);
        if(n>0) { List<String> tail=renderTiling(tile("\n".repeat(n+2),cache),cache); out.addAll(tail.subList(0,tail.size()-1)); }
        return List.copyOf(out);
    }
    private List<String> renderTiling(Tiling t,Map<Integer,List<byte[]>> cache) {
        List<int[]> spans=new ArrayList<>();
        for(int end=t.points.length;end>0;end=t.parents[end]) spans.add(new int[]{t.parents[end],end});
        Collections.reverse(spans); List<String> out=new ArrayList<>();
        for(int[] span:spans) {
            String seg=new String(t.points,span[0],span[1]-span[0]); int cp=t.points[span[0]];
            if(vocabulary.contains(seg)||floor(cp,cache)==1) out.add(render(seg));
            else for(byte[] chunk:cache.computeIfAbsent(cp,this::byteChunks)) out.add(renderBytes(chunk));
        }
        return out;
    }
}
