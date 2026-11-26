package io.nativeffm.cpptoc;

import java.util.List;
import java.util.Map;

/** Writes JSON (for the model file): maps, lists, strings, numbers, booleans and null, indented by two spaces. */
final class Json {

    private Json() {
    }

    static String write(Object value) {
        StringBuilder b = new StringBuilder();
        write(value, b, "");
        return b.append('\n').toString();
    }

    private static void write(Object v, StringBuilder b, String indent) {
        String in = indent + "  ";
        switch (v) {
            case null -> b.append("null");
            case String s -> quote(s, b);
            case Number n -> b.append(n);
            case Boolean x -> b.append(x);
            case Map<?, ?> m when m.isEmpty() -> b.append("{}");
            case Map<?, ?> m when m.values().stream().allMatch(Json::scalar) -> {
                b.append('{');                                    // a field, a parameter: one line
                String sep = "";
                for (var e : m.entrySet()) {
                    b.append(sep);
                    quote(e.getKey().toString(), b);
                    b.append(": ");
                    write(e.getValue(), b, in);
                    sep = ", ";
                }
                b.append('}');
            }
            case Map<?, ?> m -> {
                b.append("{");
                String sep = "\n";
                for (var e : m.entrySet()) {
                    b.append(sep).append(in);
                    quote(e.getKey().toString(), b);
                    b.append(": ");
                    write(e.getValue(), b, in);
                    sep = ",\n";
                }
                b.append('\n').append(indent).append('}');
            }
            case List<?> l when l.isEmpty() -> b.append("[]");
            case List<?> l when l.stream().allMatch(x -> x instanceof String || x instanceof Number) -> {
                b.append('[');                                    // short lists of names on one line
                for (int i = 0; i < l.size(); i++) {
                    if (i > 0) b.append(", ");
                    write(l.get(i), b, in);
                }
                b.append(']');
            }
            case List<?> l -> {
                b.append('[');
                String sep = "\n";
                for (Object x : l) {
                    b.append(sep).append(in);
                    write(x, b, in);
                    sep = ",\n";
                }
                b.append('\n').append(indent).append(']');
            }
            default -> throw new IllegalArgumentException("not JSON: " + v.getClass());
        }
    }

    private static boolean scalar(Object v) {
        return v == null || v instanceof String || v instanceof Number || v instanceof Boolean;
    }

    private static void quote(String s, StringBuilder b) {
        b.append('"');
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\t' -> b.append("\\t");
                default -> {
                    if (c < 0x20) b.append(String.format("\\u%04x", (int) c)); else b.append(c);
                }
            }
        }
        b.append('"');
    }
}
