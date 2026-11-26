package io.nativeffm.cpptoc;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits a header into tokens. Comments and preprocessor lines are tokens too (COMMENT, DIRECTIVE): the C view is
 * rendered from the original text, so nothing may be lost. Each token keeps its offsets into the source.
 */
final class Lexer {

    enum K { IDENT, NUMBER, STRING, CHAR, PUNCT, DIRECTIVE, COMMENT }

    record Tok(K kind, String text, int start, int end) {
        boolean is(String s) {
            return (kind == K.IDENT || kind == K.PUNCT) && text.equals(s);
        }

        boolean ident() {
            return kind == K.IDENT;
        }
    }

    /** What t(i) returns past either end of a header. */
    static final Tok EOF = new Tok(K.PUNCT, "", 0, 0);
    /** Punctuators of more than one character (no << or >>: they would break template argument lists). */
    private static final String[] MULTI_PUNCT = {"...", "::", "->", "&&", "||", "==", "!=", "<=", ">=", "##"};

    private Lexer() {
    }

    static List<Tok> lex(String s) {
        List<Tok> r = new ArrayList<>();
        int i = 0, n = s.length();
        boolean lineStart = true;
        while (i < n) {
            char c = s.charAt(i);
            if (c == '\n') {
                lineStart = true;
                i++;
                continue;
            }
            if (Character.isWhitespace(c)) {
                i++;
                continue;
            }
            if (c == '\\' && i + 1 < n && s.charAt(i + 1) == '\n') {
                i += 2;
                continue;
            }
            int st = i;
            if (c == '/' && i + 1 < n && s.charAt(i + 1) == '/') {
                while (i < n && s.charAt(i) != '\n') i++;
                r.add(new Tok(K.COMMENT, s.substring(st, i), st, i));
                continue;
            }
            if (c == '/' && i + 1 < n && s.charAt(i + 1) == '*') {
                int e = s.indexOf("*/", i + 2);
                i = e < 0 ? n : e + 2;
                r.add(new Tok(K.COMMENT, s.substring(st, i), st, i));
                continue;
            }
            if (c == '#' && lineStart) {
                while (i < n) {
                    char d = s.charAt(i);
                    if (d == '\\' && i + 1 < n && s.charAt(i + 1) == '\n') {
                        i += 2;
                    } else if (d == '\n') {
                        break;
                    } else if (d == '/' && i + 1 < n && s.charAt(i + 1) == '*') {
                        int e = s.indexOf("*/", i + 2);
                        i = e < 0 ? n : e + 2;
                    } else if (d == '/' && i + 1 < n && s.charAt(i + 1) == '/') {
                        while (i < n && s.charAt(i) != '\n') i++;
                    } else if (d == '"' || d == '\'') {
                        i = skipQuoted(s, i);
                    } else {
                        i++;
                    }
                }
                r.add(new Tok(K.DIRECTIVE, s.substring(st, i), st, i));
                continue;
            }
            lineStart = false;
            if (c == '"' || c == '\'') {
                i = skipQuoted(s, i);
                r.add(new Tok(c == '"' ? K.STRING : K.CHAR, s.substring(st, i), st, i));
                continue;
            }
            if (Character.isDigit(c) || (c == '.' && i + 1 < n && Character.isDigit(s.charAt(i + 1)))) {
                boolean hex = c == '0' && i + 1 < n && (s.charAt(i + 1) == 'x' || s.charAt(i + 1) == 'X');
                i++;
                while (i < n) {
                    char d = s.charAt(i);
                    if (Character.isLetterOrDigit(d) || d == '_' || d == '.') {
                        i++;
                    } else if (d == '\'' && i + 1 < n && Character.isLetterOrDigit(s.charAt(i + 1))) {
                        i++;                                                    // digit separator 1'000
                    } else if ((d == '+' || d == '-') && (hex ? "pP" : "eE").indexOf(s.charAt(i - 1)) >= 0) {
                        i++;
                    } else {
                        break;
                    }
                }
                r.add(new Tok(K.NUMBER, s.substring(st, i), st, i));
                continue;
            }
            if (Character.isLetter(c) || c == '_' || c == '$') {
                while (i < n && (Character.isLetterOrDigit(s.charAt(i)) || s.charAt(i) == '_' || s.charAt(i) == '$')) i++;
                String id = s.substring(st, i);
                if (i < n && s.charAt(i) == '"' && id.matches("L|u8|u|U")) {
                    i = skipQuoted(s, i);
                    r.add(new Tok(K.STRING, s.substring(st, i), st, i));
                } else if (i < n && s.charAt(i) == '"' && id.matches("R|LR|u8R|uR|UR")) {   // raw string R"x(...)x"
                    int open = s.indexOf('(', i);
                    String delim = open < 0 ? "" : s.substring(i + 1, open);
                    int close = open < 0 ? -1 : s.indexOf(")" + delim + "\"", open);
                    i = close < 0 ? n : close + delim.length() + 2;
                    r.add(new Tok(K.STRING, s.substring(st, i), st, i));
                } else {
                    r.add(new Tok(K.IDENT, id, st, i));
                }
                continue;
            }
            String punct = String.valueOf(c);
            for (String m : MULTI_PUNCT) {
                if (s.startsWith(m, i)) {
                    punct = m;
                    break;
                }
            }
            i += punct.length();
            r.add(new Tok(K.PUNCT, punct, st, i));
        }
        return r;
    }

    static int skipQuoted(String s, int i) {
        char q = s.charAt(i++);
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '\\') {
                i += 2;
            } else if (c == q) {
                return i + 1;
            } else if (c == '\n') {
                return i;
            } else {
                i++;
            }
        }
        return i;
    }
}
