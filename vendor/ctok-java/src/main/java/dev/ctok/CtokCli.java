package dev.ctok;

/** Small headless entry point for scripts and manual comparison. */
public final class CtokCli {
    private CtokCli() {}
    public static void main(String[] args) {
        if(args.length<1||args.length>2) throw new IllegalArgumentException("Usage: java -jar ctok-java.jar TEXT [VERSION]");
        Ctok tokenizer=Ctok.forVersion(args.length==2?args[1]:"3.0");
        System.out.println(tokenizer.tokenCount(args[0]));
    }
}
