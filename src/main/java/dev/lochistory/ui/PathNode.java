package dev.lochistory.ui;

record PathNode(String name, String path, boolean file, int loc, int rloc, boolean showRloc) {
    int lines() {
        return showRloc ? rloc : loc;
    }

    @Override
    public String toString() {
        return name + "  —  " + String.format("%,d", lines()) + (showRloc ? " RLOC" : " LOC");
    }
}
