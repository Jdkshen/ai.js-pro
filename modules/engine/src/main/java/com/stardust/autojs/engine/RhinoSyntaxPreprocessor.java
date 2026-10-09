package com.stardust.autojs.engine;

import org.mozilla.javascript.CompilerEnvirons;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.ErrorReporter;
import org.mozilla.javascript.EvaluatorException;
import org.mozilla.javascript.Parser;
import org.mozilla.javascript.ast.AstRoot;
import org.mozilla.javascript.ast.Name;
import org.mozilla.javascript.ast.ObjectProperty;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Compatibility for object properties missing from the bundled E4X-capable Rhino parser. */
public final class RhinoSyntaxPreprocessor {
    private RhinoSyntaxPreprocessor() {}

    public static String apply(String source) {
        String templates = TemplateLiteralPreprocessor.apply(source);
        String original = templates == null ? source : templates;
        String marker = "__rhino_shorthand_value__";
        while (original.contains(marker)) marker += "_";
        final String placeholder = marker;
        String converted = original;
        int shorthandCount = 0;
        // Rhino locates real object properties, including those inside E4X expressions.
        // Temporary values let its AST identify the corresponding variable precisely;
        // no brace/comment/regex heuristics are used to guess property boundaries.
        for (int attempt = 0; attempt < 256; attempt++) {
            try {
                AstRoot root = parse(converted);
                List<Replacement> values = new ArrayList<>();
                root.visit(node -> {
                    if (node instanceof ObjectProperty) {
                        ObjectProperty property = (ObjectProperty) node;
                        if (property.getRight() instanceof Name
                                && placeholder.equals(((Name) property.getRight()).getIdentifier())
                                && property.getLeft() instanceof Name) {
                            Name right = (Name) property.getRight();
                            values.add(new Replacement(right.getAbsolutePosition(),
                                    ((Name) property.getLeft()).getIdentifier()));
                        }
                    }
                    return true;
                });
                if (values.size() != shorthandCount) return original;
                Collections.sort(values, (first, second) -> Integer.compare(second.offset, first.offset));
                StringBuilder result = new StringBuilder(converted);
                for (Replacement value : values) {
                    result.replace(value.offset, value.offset + placeholder.length(), value.name);
                }
                return result.toString();
            } catch (SyntaxProblem problem) {
                if (!problem.getMessage().contains("invalid object initializer")) return original;
                int offset = absoluteOffset(converted, problem.line, problem.column);
                if (offset < 0 || offset >= converted.length()) return original;
                char token = converted.charAt(offset);
                String insertion;
                if (token == ',' || token == '}') {
                    insertion = ":" + placeholder;
                    shorthandCount++;
                } else if (token == '(') {
                    insertion = ":function";
                } else {
                    return original;
                }
                converted = converted.substring(0, offset) + insertion + converted.substring(offset);
            }
        }
        return original;
    }

    private static AstRoot parse(String source) {
        CompilerEnvirons environment = new CompilerEnvirons();
        Context current = Context.getCurrentContext();
        if (current != null) environment.initFromContext(current);
        environment.setLanguageVersion(Context.VERSION_ES6);
        ErrorReporter reporter = new ErrorReporter() {
            @Override public void warning(String message, String name, int line, String text, int column) {}
            @Override public void error(String message, String name, int line, String text, int column) {
                throw new SyntaxProblem(message, line, column);
            }
            @Override public EvaluatorException runtimeError(String message, String name, int line,
                                                              String text, int column) {
                throw new SyntaxProblem(message, line, column);
            }
        };
        return new Parser(environment, reporter).parse(source, "compatibility", 1);
    }

    private static int absoluteOffset(String source, int line, int column) {
        if (line < 1 || column < 1) return -1;
        int offset = 0;
        for (int current = 1; current < line; current++) {
            boolean found = false;
            while (offset < source.length()) {
                char c = source.charAt(offset++);
                if (c == '\r') {
                    if (offset < source.length() && source.charAt(offset) == '\n') offset++;
                    found = true;
                    break;
                }
                if (c == '\n' || c == '\u2028' || c == '\u2029') {
                    found = true;
                    break;
                }
            }
            if (!found) return -1;
        }
        return offset + column - 1;
    }

    private static final class Replacement {
        final int offset;
        final String name;
        Replacement(int offset, String name) { this.offset = offset; this.name = name; }
    }

    private static final class SyntaxProblem extends RuntimeException {
        final int line;
        final int column;
        SyntaxProblem(String message, int line, int column) {
            super(message, null, false, false);
            this.line = line;
            this.column = column;
        }
    }
}
