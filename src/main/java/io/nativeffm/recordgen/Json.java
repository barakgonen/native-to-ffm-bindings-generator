package io.nativeffm.recordgen;

import java.util.*;

/** Reads JSON (the model file): objects become LinkedHashMaps, arrays Lists, numbers Longs. */
final class Json {

    private final String s;
    private int i;

    private Json(String s) {
        this.s = s;
    }

    static Object parse(String text) {
        Json j = new Json(text);
        Object v = j.value();
        j.space();
        if (j.i != text.length()) throw j.error("text after the value");
        return v;
    }

    private Object value() {
        space();
        if (i >= s.length()) throw error("unexpected end");
        char c = s.charAt(i);
        switch (c) {
            case '{' -> {
                Map<String, Object> m = new LinkedHashMap<>();
                i++;
                space();
                if (peek('}')) return m;
                do {
                    space();
                    String key = string();
                    space();
                    expect(':');
                    m.put(key, value());
                    space();
                } while (next(','));
                expect('}');
                return m;
            }
            case '[' -> {
                List<Object> l = new ArrayList<>();
                i++;
                space();
                if (peek(']')) return l;
                do {
                    l.add(value());
                    space();
                } while (next(','));
                expect(']');
                return l;
            }
            case '"' -> {
                return string();
            }
            default -> {
                for (String word : List.of("true", "false", "null")) {
                    if (s.startsWith(word, i)) {
                        i += word.length();
                        return word.equals("null") ? null : Boolean.valueOf(word);
                    }
                }
                int from = i;
                while (i < s.length() && "+-0123456789".indexOf(s.charAt(i)) >= 0) i++;
                if (from == i) throw error("unexpected '" + c + "'");
                return Long.parseLong(s.substring(from, i));
            }
        }
    }

    private String string() {
        expect('"');
        StringBuilder b = new StringBuilder();
        while (s.charAt(i) != '"') {
            char c = s.charAt(i++);
            if (c != '\\') {
                b.append(c);
                continue;
            }
            char e = s.charAt(i++);
            switch (e) {
                case 'n' -> b.append('\n');
                case 't' -> b.append('\t');
                case 'u' -> {
                    b.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                    i += 4;
                }
                default -> b.append(e);
            }
        }
        i++;
        return b.toString();
    }

    private void space() {
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
    }

    private boolean peek(char c) {
        if (i < s.length() && s.charAt(i) == c) {
            i++;
            return true;
        }
        return false;
    }

    private boolean next(char c) {
        space();
        return peek(c);
    }

    private void expect(char c) {
        if (!peek(c)) throw error("expected '" + c + "'");
    }

    private RuntimeException error(String what) {
        return new IllegalArgumentException("bad JSON at offset " + i + ": " + what);
    }
}
