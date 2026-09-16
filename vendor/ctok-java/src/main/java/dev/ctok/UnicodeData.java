package dev.ctok;

import java.io.*;
import java.nio.charset.StandardCharsets;

final class UnicodeData {
    static final int WORD=0,HARD=1,DIGIT=2,PUNCT=3,SPACE=4,STRAY=5;
    static final byte[] CLASSES=new byte[65536], FLAGS=new byte[65536], NUMBERS=new byte[65536];
    static final String[] LOWER=new String[65536];
    static {
        try(DataInputStream in=open("unicode.bin")) {
            for(int i=0;i<65536;i++) { CLASSES[i]=in.readByte(); FLAGS[i]=in.readByte(); NUMBERS[i]=in.readByte(); LOWER[i]=readString(in); }
        } catch(IOException e) { throw new ExceptionInInitializerError(e); }
    }
    static DataInputStream open(String name) throws IOException {
        InputStream stream=UnicodeData.class.getResourceAsStream(name);
        if(stream==null) throw new IOException("Missing bundled resource: "+name);
        return new DataInputStream(new BufferedInputStream(stream));
    }
    static String readString(DataInputStream in) throws IOException {
        int n=in.readInt(); if(n<0 || n>10_000_000) throw new IOException("Invalid string size");
        byte[] b=in.readNBytes(n); if(b.length!=n) throw new EOFException();
        return new String(b,StandardCharsets.UTF_8);
    }
    static boolean flag(int cp,int mask) { return cp<65536 && (FLAGS[cp]&mask)!=0; }
    static int cls(int cp) { return cp<65536?CLASSES[cp]:HARD; }
    static boolean selector(int cp) { return cp>=0xfe00 && cp<=0xfe0f; }
    static boolean punct(int cp) { return flag(Notation.literal(cp),2); }
    static String lower(int cp) { return cp<65536?LOWER[cp]:new String(Character.toChars(cp)).toLowerCase(java.util.Locale.ROOT); }
}
