package dev.ctok;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Dependency-free executable verification; any mismatch exits nonzero. */
public final class ParityTest {
    private static void equal(Object actual,Object expected,String label) {
        if(!Objects.equals(actual,expected)) throw new AssertionError(label+"\nexpected: "+abbreviate(expected)+"\nactual: "+abbreviate(actual));
    }
    private static String abbreviate(Object value) {
        String s=String.valueOf(value);
        return s.length()>240?s.substring(0,240)+"…":s;
    }
    private static void apiChecks() throws Exception {
        Ctok a=Ctok.forVersion(),b=Ctok.forVersion("4.7"),c=Ctok.forVersion("4.8");
        equal(a.tokenCount("hello"),8,"v3 hello");
        equal(b.tokenCount("hello"),13,"v4.7 hello");
        equal(c.tokenCount("hello"),8,"v4.8 hello");
        for(String v:List.of("5","5.0","4.10","4.8.1")) equal(Ctok.forVersion(v),c,"routing "+v);
        for(String v:List.of("3","3.5","4.6")) equal(Ctok.forVersion(v),a,"routing "+v);
        for(String v:List.of("2.9","banana","4.","")) {
            try { Ctok.forVersion(v); throw new AssertionError("accepted "+v); } catch(IllegalArgumentException expected) {}
        }
        try { a.tokenCount(null); throw new AssertionError("accepted null"); } catch(NullPointerException expected) {}
        equal(a.tokenCount("\ud800"),a.tokenCount("�"),"lone surrogate");
        equal(a.tokenCount("\ud800x\udfff"),a.tokenCount("�x�"),"multiple lone surrogates");
        equal(a.normalize("𐐀"),"𐐀","valid surrogate pair");
        equal(a.markedStream("⟨"),"⟨bow⟩⟨0xE2⟩⟨0x9F⟩⟨0xA8⟩","literal bracket escapes");
        equal(a.witness("⟨bow⟩").raw(),null,"special marker has no raw witness");
        equal(Ctok.forVersion("4.7.-1"),a,"trailing version component comparison");
        for(Ctok model:List.of(a,b,c)) {
            equal(model.contentTokenCount("hello"),model.tokenCount("hello")-model.messageOverhead(),"content count");
            try { model.pieces().clear(); throw new AssertionError("mutable pieces"); } catch(UnsupportedOperationException expected) {}
            try { model.tokenize("hello").clear(); throw new AssertionError("mutable tokens"); } catch(UnsupportedOperationException expected) {}
        }
        ExecutorService pool=Executors.newFixedThreadPool(4);
        try {
            List<Callable<Integer>> jobs=new ArrayList<>();
            for(int i=0;i<100;i++) jobs.add(()->c.tokenCount("public class Hello { /* 😀 */ }\n"));
            for(Future<Integer> result:pool.invokeAll(jobs)) equal(result.get(),c.tokenCount("public class Hello { /* 😀 */ }\n"),"concurrency");
        } finally { pool.shutdown(); }
        System.out.println("API, invalid inputs, immutability, and concurrent counting: PASS");
    }
    public static void main(String[] args) throws Exception {
        apiChecks(); if(args.length==0) return;
        int failures=0;
        try(DataInputStream in=new DataInputStream(new BufferedInputStream(Files.newInputStream(Path.of(args[0]))))) {
            int cases=in.readInt();
            for(int i=0;i<cases;i++) {
                String label=UnicodeData.readString(in),version=UnicodeData.readString(in),text=UnicodeData.readString(in),norm=UnicodeData.readString(in),stream=UnicodeData.readString(in);
                int count=in.readInt(); List<String> tokens=new ArrayList<>(count);
                for(int j=0;j<count;j++) tokens.add(UnicodeData.readString(in));
                Ctok model=Ctok.forVersion(version);
                try {
                    equal(model.normalize(text),norm,label+" normalize "+version);
                    equal(model.markedStream(text),stream,label+" stream "+version);
                    equal(model.tokenCount(text),count,label+" count "+version);
                    equal(model.tokenize(text),tokens,label+" tokens "+version);
                } catch(AssertionError e) {
                    failures++; if(failures<=20) System.err.println(e.getMessage());
                }
            }
            if(in.read()!=-1) throw new AssertionError("Unexpected trailing parity data");
            if(failures>0) throw new AssertionError(failures+" mismatches out of "+cases+" cases");
            System.out.println(cases+" Python/Java normalization, marked stream, count, and token-list comparisons: PASS");
        }
    }
}
