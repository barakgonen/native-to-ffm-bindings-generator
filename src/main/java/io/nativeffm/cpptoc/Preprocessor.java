package io.nativeffm.cpptoc;

import io.nativeffm.cpptoc.Ast.*;
import io.nativeffm.cpptoc.Lexer.K;
import io.nativeffm.cpptoc.Lexer.Tok;

import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.nativeffm.cpptoc.Lexer.EOF;
import static io.nativeffm.cpptoc.Words.*;

/**
 * Preprocessor lines of a header: the include guard, #include (provider headers are followed, C library headers
 * kept only if used), #define (export macros and extern "C" scopes are recognized), and conditionals: those on
 * __cplusplus are decided (CppToC reads the header as C++), the others are kept in the C view.
 */
final class Preprocessor extends HeaderPass {

    Preprocessor(Header h) {
        super(h);
    }

    void findIncludeGuard() {
        int first = sig0(0), last = h.n - 1;
        while (last >= 0 && h.toks.get(last).kind() == K.COMMENT) last--;
        if (first < 0 || first >= h.n || h.toks.get(first).kind() != K.DIRECTIVE || last <= first) return;
        Matcher a = Pattern.compile("#\\s*(?:ifndef\\s+(\\w+)|if\\s+!\\s*defined\\s*\\(?\\s*(\\w+)\\s*\\)?)")
                .matcher(directiveText(first));
        if (!a.matches()) return;
        String g = a.group(1) != null ? a.group(1) : a.group(2);
        int second = sig0(first + 1);
        if (second >= h.n || h.toks.get(second).kind() != K.DIRECTIVE
                || !directiveText(second).matches("#\\s*define\\s+" + Pattern.quote(g) + "(\\s+1)?")) return;
        if (h.toks.get(last).kind() != K.DIRECTIVE || !keyword(last).equals("endif")) return;
        int depth = 0;
        for (int i = first; i <= last; i++) {
            if (h.toks.get(i).kind() != K.DIRECTIVE) continue;
            String kw = keyword(i);
            if (kw.startsWith("if")) depth++;
            else if (kw.equals("endif") && --depth == 0 && i != last) return;
        }
        h.guardIf = first;
        h.guardDefine = second;
        h.guardEndif = last;
    }

    void directive() {
        int i = h.p;
        if (i == h.guardIf || i == h.guardDefine || i == h.guardEndif) {
            drop(i, i);
            return;
        }
        String kw = keyword(i);
        String rest = directiveText(i).replaceFirst("^#\\s*\\w*\\s*", "");
        switch (kw) {
            case "pragma" -> {
                if (rest.equals("once")) {
                    h.guarded = true;
                    drop(i, i);
                } else {
                    keep(i, i);
                }
            }
            case "include", "include_next" -> include(i, rest);
            case "define" -> define(i, rest);
            case "if", "ifdef", "ifndef" -> condIf(i, kw, rest);
            case "elif", "elifdef", "elifndef" -> condElif(i, kw, rest);
            case "else" -> condElse(i);
            case "endif" -> condEndif(i);
            default -> keep(i, i);
        }
    }

    void include(int i, String rest) {
        Matcher m = Pattern.compile("^([<\"])([^>\"]+)[>\"]").matcher(rest);
        if (!m.find()) {
            keep(i, i);
            return;
        }
        String inc = m.group(2);
        boolean angle = m.group(1).equals("<");
        if (angle && C_HEADERS.containsKey(inc)) {
            systemInclude(i, C_HEADERS.get(inc));
            return;
        }
        Path[] found = tr.findInclude(inc, angle ? null : h.file.getParent());
        if (found != null) {                                    // one of the provider's headers
            includes(found[0], found[1]);
            Path key = found[0].toAbsolutePath().normalize();
            String text = angle ? "#include <" + Translation.cHeaderName(inc) + ">" : "#include \"" + Translation.cHeaderName(inc) + "\"";
            add(i, i, it -> {                                   // left out if that header is not converted
                Header target = tr.units.get(key);
                if (target != null && !target.needed) return null;
                Edits e = new Edits();
                e.replace.put(i, text);
                return render(it.first, it.last, e);
            });
            return;
        }
        if (angle) {
            if (inc.endsWith(".h")) systemInclude(i, inc);
            else drop(i, i);                                    // C++ library header: not valid (or needed) in C
            return;
        }
        warn(i, "#include \"" + inc + "\" not found in " + tr.includeDirs + ", kept as is (add its folder"
                + " with --include-dir)");
        keep(i, i);
    }

    /** #include <stddef.h> and friends: kept only if this header uses a name the C header declares. */
    void systemInclude(int i, String cName) {
        Pattern declares = C_HEADER_NAMES.get(cName);
        add(i, i, it -> {
            if (declares != null && h.identifiers().stream().noneMatch(w -> declares.matcher(w).matches())) return null;
            Edits e = new Edits();
            e.replace.put(i, "#include <" + cName + ">");
            return render(it.first, it.last, e);
        });
    }

    void includes(Path target, Path dir) {
        Path key = target.toAbsolutePath().normalize();
        tr.pending.add(new Path[]{key, dir});
        tr.included.add(key);
        h.includes.add(key);
        tr.includedBy.computeIfAbsent(key, k -> new LinkedHashSet<>()).add(h.rel);
    }

    void define(int i, String rest) {
        Matcher m = Pattern.compile("^(\\w+)(\\([^)]*\\))?(.*)$", Pattern.DOTALL).matcher(rest);
        if (!m.matches()) {
            keep(i, i);
            return;
        }
        String name = m.group(1);
        boolean fnLike = m.group(2) != null;
        String value = m.group(3).strip();
        MacroKind kind = classify(value);
        boolean cLinkage = kind == MacroKind.DECOR && value.matches("(?s).*extern\\s*\"C\".*");
        tr.macros.computeIfAbsent(name, k -> new ArrayList<>()).add(new Macro(name, fnLike, value, kind, cLinkage));
        tr.macroUnits.computeIfAbsent(name, k -> new LinkedHashSet<>()).add(h);
        if (!fnLike && kind == MacroKind.OTHER && !tr.syms.containsKey(name)) {
            List<Tok> valueToks = Lexer.lex(value).stream().filter(x -> x.kind() != K.COMMENT).toList();
            tr.syms.put(name, new Sym(name, s -> tr.evaluate(valueToks, Map.of())));
        }
        switch (kind) {
            case SCOPE_C, SCOPE_CXX, SCOPE_END -> drop(i, i);
            case DECOR -> {
                String text = h.toks.get(i).text();
                String c = text.replaceAll("extern\\s*\"C\"", "extern").replaceAll("extern\\s*\"C\\+\\+\"", "")
                        .replaceAll("\\[\\[.*?\\]\\]", "");
                if (c.equals(text)) keep(i, i); else replaced(i, i, c);
            }
            default -> keep(i, i);
        }
    }

    MacroKind classify(String value) {
        String v = value.replaceAll("\\s+", " ").strip();
        if (v.equals("extern \"C\" {")) return MacroKind.SCOPE_C;
        if (v.equals("extern \"C++\" {")) return MacroKind.SCOPE_CXX;
        if (v.equals("}")) return MacroKind.SCOPE_END;
        List<Tok> ts = Lexer.lex(v).stream().filter(x -> x.kind() != K.COMMENT).toList();
        for (int i = 0; i < ts.size(); i++) {
            Tok x = ts.get(i);
            if (x.ident() && Set.of("extern", "inline", "__inline", "__inline__", "__forceinline", "static")
                    .contains(x.text())) continue;
            if (x.ident() && (CALLCONV.contains(x.text()) || isDecor(x))) continue;
            if (x.kind() == K.STRING && (x.text().equals("\"C\"") || x.text().equals("\"C++\""))) continue;
            boolean group = x.ident() && (x.text().equals("__declspec") || x.text().equals("__attribute__"))
                    || x.is("[") && i + 1 < ts.size() && ts.get(i + 1).is("[");
            if (!group) return MacroKind.OTHER;
            int depth = 0, j = x.ident() ? i + 1 : i;
            for (; j < ts.size(); j++) {
                if (ts.get(j).is("(") || ts.get(j).is("[")) depth++;
                else if ((ts.get(j).is(")") || ts.get(j).is("]")) && --depth == 0) break;
            }
            if (x.is("[")) j++;                               // the second ']' of [[...]]
            i = j;
        }
        return MacroKind.DECOR;
    }

    void condIf(int i, String kw, String rest) {
        String c = kw.equals("ifdef") ? "defined(" + rest + ")" : kw.equals("ifndef") ? "!defined(" + rest + ")" : rest;
        Boolean v = cplusplusValue(c);
        if (v == null) {
            Cond f = new Cond(false);
            f.current = c;
            h.conds.push(f);
            if (c.contains("__cplusplus")) replaced(i, i, "#if " + cxxReplaced(c)); else keep(i, i);
            return;
        }
        Cond f = new Cond(true);
        h.conds.push(f);
        drop(i, i);
        if (v) f.taken = true; else skipInactive();
    }

    void condElif(int i, String kw, String rest) {
        Cond f = h.conds.peek();
        if (f == null) throw new Fail("#elif without #if");
        String c = kw.equals("elifdef") ? "defined(" + rest + ")" : kw.equals("elifndef") ? "!defined(" + rest + ")" : rest;
        if (!f.cxx) {
            f.previous.add(f.current);
            f.current = c;
            keep(i, i);
            return;
        }
        if (f.taken) {
            drop(i, i);
            skipInactive();
            return;
        }
        Boolean v = cplusplusValue(c);
        if (v == null) {                                        // from here on an ordinary conditional
            h.conds.pop();
            Cond g = new Cond(false);
            g.current = c;
            h.conds.push(g);
            replaced(i, i, "#if " + cxxReplaced(c));
            return;
        }
        drop(i, i);
        if (v) f.taken = true; else skipInactive();
    }

    void condElse(int i) {
        Cond f = h.conds.peek();
        if (f == null) throw new Fail("#else without #if");
        if (!f.cxx) {
            f.previous.add(f.current);
            f.current = null;
            keep(i, i);
            return;
        }
        drop(i, i);
        if (f.taken) skipInactive(); else f.taken = true;
    }

    void condEndif(int i) {
        Cond f = h.conds.poll();
        if (f == null) throw new Fail("#endif without #if");
        if (f.cxx) drop(i, i); else keep(i, i);
    }

    /** Drops an inactive branch, up to (not including) its #elif, #else or #endif. */
    void skipInactive() {
        int from = h.p, depth = 0, i = h.p;
        for (; i < h.n; i++) {
            if (h.toks.get(i).kind() != K.DIRECTIVE) continue;
            String kw = keyword(i);
            if (kw.startsWith("if")) depth++;
            else if (kw.equals("endif")) {
                if (depth == 0) break;
                depth--;
            } else if ((kw.startsWith("elif") || kw.equals("else")) && depth == 0) break;
        }
        if (i > from) {
            Item it = new Item(from, i - 1);
            it.render = x -> null;
            h.items.add(it);
        }
        h.p = i;
    }

    static Boolean cplusplusValue(String c) {
        String s = c.replaceAll("\\s+", "");
        switch (s) {
            case "defined(__cplusplus)", "defined__cplusplus", "__cplusplus" -> {
                return true;
            }
            case "!defined(__cplusplus)", "!defined__cplusplus", "!__cplusplus" -> {
                return false;
            }
            default -> { }
        }
        Matcher m = Pattern.compile("__cplusplus(>=|>|<=|<)(\\d+)L?").matcher(s);
        if (m.matches()) {
            long v = 201703L, x = Long.parseLong(m.group(2));
            return switch (m.group(1)) {
                case ">=" -> v >= x;
                case ">" -> v > x;
                case "<=" -> v <= x;
                default -> v < x;
            };
        }
        return null;
    }

    static String cxxReplaced(String c) {
        return c.replaceAll("defined\\s*\\(\\s*__cplusplus\\s*\\)", "1").replaceAll("defined\\s+__cplusplus", "1")
                .replaceAll("__cplusplus", "201703L");
    }
}
