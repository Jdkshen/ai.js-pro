package org.autojs.source.engine.android;

/** Decodes levels from our console module; ordinary stdout remains a debug line. */
public final class Node24ConsoleLine {
    private static final String PREFIX = "\u001eAJSLOG1:";
    public final int level;
    public final String text;

    private Node24ConsoleLine(int level, String text) {
        this.level = level;
        this.text = text;
    }

    public static Node24ConsoleLine decode(String line) {
        String text = line == null ? "" : line;
        if (text.startsWith(PREFIX) && text.length() >= PREFIX.length() + 2
                && text.charAt(PREFIX.length() + 1) == ':') {
            int level;
            switch (text.charAt(PREFIX.length())) {
                case 'V': level = 2; break;
                case 'D': level = 3; break;
                case 'I': level = 4; break;
                case 'W': level = 5; break;
                case 'E': level = 6; break;
                case 'A': level = 7; break;
                default: return new Node24ConsoleLine(3, text);
            }
            return new Node24ConsoleLine(level, text.substring(PREFIX.length() + 2));
        }
        return new Node24ConsoleLine(3, text);
    }
}
