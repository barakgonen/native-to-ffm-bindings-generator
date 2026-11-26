package io.nativeffm.cpptoc;

import io.nativeffm.cpptoc.Ast.EnumDef;
import io.nativeffm.cpptoc.Ast.Sym;
import io.nativeffm.cpptoc.Lexer.K;
import io.nativeffm.cpptoc.Lexer.Tok;

import java.util.*;

import static io.nativeffm.cpptoc.Words.C_STD_TYPES;
import static io.nativeffm.cpptoc.Words.PRIM_WORDS;

/**
 * Evaluates integer constant expressions: arithmetic, shifts, comparisons, logic, ?:, casts (C style and
 * static_cast), and named constants (enum constants, constexpr, static members, #defines). Used for array sizes,
 * enum values and constants whose C++ spelling C cannot take. parse() returns null when the expression is not a
 * constant it can compute.
 */
final class ConstEval {

    /** Binary operators from the lowest precedence to the highest (C rules). */
    private static final List<List<String>> LEVELS = List.of(List.of("||"), List.of("&&"), List.of("|"), List.of("^"),
            List.of("&"), List.of("==", "!="), List.of("<", ">", "<=", ">="), List.of("<<", ">>"), List.of("+", "-"),
            List.of("*", "/", "%"));

    private final List<Tok> ts;
    private final Map<String, Sym> syms, local;
    private int i;

    ConstEval(List<Tok> ts, Map<String, Sym> syms, Map<String, Sym> local) {
        this.ts = ts;
        this.syms = syms;
        this.local = local;
    }

    private static final class NoValue extends RuntimeException {
        NoValue() {
            super(null, null, false, false);
        }
    }

    Long parse() {
        try {
            long v = ternary();
            return i == ts.size() ? v : null;
        } catch (NoValue | IndexOutOfBoundsException | ArithmeticException e) {
            return null;
        }
    }

    private boolean at(String s) {
        return i < ts.size() && ts.get(i).is(s);
    }

    /** {@code <<} and {@code >>}: the lexer makes them two adjacent tokens (so that templates parse). */
    private boolean at2(String s) {
        return i + 1 < ts.size() && ts.get(i).is(s) && ts.get(i + 1).is(s) && ts.get(i).end() == ts.get(i + 1).start();
    }

    private long ternary() {
        long c = binary(0);
        if (!at("?")) return c;
        i++;
        long a = ternary();                  // both branches are evaluated: an error in either gives no value
        if (!at(":")) throw new NoValue();
        i++;
        long b = ternary();
        return c != 0 ? a : b;
    }

    private long binary(int level) {
        if (level == LEVELS.size()) return unary();
        long v = binary(level + 1);
        for (String op = operator(level); op != null; op = operator(level)) v = apply(op, v, binary(level + 1));
        return v;
    }

    /** The operator at the current token if it is one of this level's (consumed), or null. */
    private String operator(int level) {
        String op = at2("<") ? "<<" : at2(">") ? ">>" : i < ts.size() && ts.get(i).kind() == K.PUNCT ? ts.get(i).text() : null;
        if (op == null || !LEVELS.get(level).contains(op)) return null;
        i += op.equals("<<") || op.equals(">>") ? 2 : 1;
        return op;
    }

    private static long apply(String op, long v, long r) {
        return switch (op) {
            case "||" -> v != 0 || r != 0 ? 1 : 0;
            case "&&" -> v != 0 && r != 0 ? 1 : 0;
            case "|" -> v | r;
            case "^" -> v ^ r;
            case "&" -> v & r;
            case "==" -> v == r ? 1 : 0;
            case "!=" -> v != r ? 1 : 0;
            case "<" -> v < r ? 1 : 0;
            case ">" -> v > r ? 1 : 0;
            case "<=" -> v <= r ? 1 : 0;
            case ">=" -> v >= r ? 1 : 0;
            case "<<" -> v << r;
            case ">>" -> v >> r;
            case "+" -> v + r;
            case "-" -> v - r;
            case "*" -> v * r;
            case "/" -> v / r;
            default -> v % r;
        };
    }

    private long unary() {
        if (at("-")) { i++; return -unary(); }
        if (at("+")) { i++; return unary(); }
        if (at("~")) { i++; return ~unary(); }
        if (at("!")) { i++; return unary() == 0 ? 1 : 0; }
        return primary();
    }

    private long primary() {
        Tok x = ts.get(i);
        if (x.kind() == K.NUMBER) {
            i++;
            Long v = parseNumber(x.text());
            if (v == null) throw new NoValue();
            return v;
        }
        if (x.kind() == K.CHAR) {
            i++;
            String body = x.text().substring(1, x.text().length() - 1);
            if (body.length() == 1) return body.charAt(0);
            throw new NoValue();
        }
        if (x.is("(")) {
            int j = i + 1;                                         // (type) cast
            boolean cast = false;
            while (j < ts.size() && ts.get(j).ident() && (PRIM_WORDS.contains(ts.get(j).text())
                    || C_STD_TYPES.contains(ts.get(j).text()) || ts.get(j).is("const"))) {
                j++;
                cast = true;
            }
            if (cast && j < ts.size() && ts.get(j).is(")")) {
                i = j + 1;
                return unary();
            }
            i++;
            long v = ternary();
            if (!at(")")) throw new NoValue();
            i++;
            return v;
        }
        if (x.is("true") || x.is("false")) {
            i++;
            return x.is("true") ? 1 : 0;
        }
        if (x.is("static_cast") || x.is("const_cast") || x.is("reinterpret_cast")) {
            i++;
            if (!at("<")) throw new NoValue();
            int depth = 0;
            while (i < ts.size()) {
                if (at("<")) depth++;
                if (at(">") && --depth == 0) break;
                i++;
            }
            i++;
            if (!at("(")) throw new NoValue();
            i++;
            long v = ternary();
            if (!at(")")) throw new NoValue();
            i++;
            return v;
        }
        if (x.ident() || x.is("::")) {                              // a named constant, maybe qualified
            StringBuilder q = new StringBuilder();
            while (i < ts.size() && (ts.get(i).ident() || ts.get(i).is("::"))) {
                if (ts.get(i).ident() && !q.isEmpty() && !q.toString().endsWith("::")) break;
                q.append(ts.get(i).text());
                i++;
            }
            String name = q.toString().replaceFirst("^::", "");
            Sym s = local.get(name);
            if (s == null) s = syms.get(name);
            Long v = s == null ? null : s.value();
            if (v == null) throw new NoValue();
            return v;
        }
        throw new NoValue();
    }

    // ---------------------------------------------------------- helpers

    /** An integer literal: decimal, hex, octal, binary, with ' separators and u/l suffixes; null if it is not one. */
    static Long parseNumber(String text) {
        String s = text.replace("'", "").replaceAll("[uUlLzZ]+$", "");
        try {
            if (s.matches("0[xX][0-9a-fA-F]+")) return Long.parseUnsignedLong(s.substring(2), 16);
            if (s.matches("0[bB][01]+")) return Long.parseUnsignedLong(s.substring(2), 2);
            if (s.matches("0[0-7]+")) return Long.parseLong(s.substring(1), 8);
            if (s.matches("\\d+")) return Long.parseUnsignedLong(s);
        } catch (NumberFormatException e) {
            return null;
        }
        return null;
    }

    /** The value of an enum constant: its expression, or the previous constant + 1 (0 for the first). */
    static Long enumValue(Translation tr, EnumDef en, int[] constant) {
        int idx = en.constants.indexOf(constant);
        if (constant[2] >= 0) return tr.evaluate(sigToks(en.u, constant[2], constant[3]), en.localSyms);
        if (idx == 0) return 0L;
        Long prev = en.localSyms.get(en.u.t(en.constants.get(idx - 1)[0]).text()).value();
        return prev == null ? null : prev + 1;
    }

    /** The tokens of [from, to] without comments and directives. */
    static List<Tok> sigToks(Header u, int from, int to) {
        List<Tok> r = new ArrayList<>();
        for (int i = from; i <= to; i++) if (u.t(i).kind() != K.COMMENT && u.t(i).kind() != K.DIRECTIVE) r.add(u.t(i));
        return r;
    }
}
