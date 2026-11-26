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
 * The top level of a header: namespaces, extern "C" blocks, and declarations: functions (with their parameters)
 * and variables (constants become #defines). Type definitions go to TypeParser, preprocessor lines to Preprocessor.
 */
final class Parser extends HeaderPass {

    private final Preprocessor pre;
    private final TypeParser types;

    Parser(Header h) {
        super(h);
        pre = new Preprocessor(h);
        types = new TypeParser(h);
    }

    /** Parses the whole header; a declaration that fails is reported and skipped. */
    void parse() {
        pre.findIncludeGuard();
        h.guarded = h.guardIf >= 0;
        while (h.p < h.n) {
            int before = h.p, itemCount = h.items.size();
            try {
                topLevel();
            } catch (RuntimeException e) {       // recover: report, skip the declaration and go on
                warn(before, "could not translate this declaration (" + e.getMessage() + "), skipped");
                while (h.items.size() > itemCount) h.items.remove(h.items.size() - 1);
                int i;
                try {
                    i = scanDeclEnd(before)[0];
                } catch (RuntimeException again) {
                    i = before;
                    while (i < h.n - 1 && !h.toks.get(i).is(";")) i++;
                }
                h.p = before;
                drop(before, Math.max(before, i));
            }
            if (h.p <= before) h.p = before + 1;
        }
        if (!h.scopes.isEmpty()) warn(h.n - 1, "unbalanced braces at the end of the header");
    }

    void topLevel() {
        Tok k = h.toks.get(h.p);
        if (k.kind() == K.COMMENT) {
            h.p++;
            return;
        }
        if (k.kind() == K.DIRECTIVE) {
            pre.directive();
            return;
        }
        int start = h.p;
        if (k.is(";")) {
            drop(h.p, h.p);
            return;
        }
        if (k.is("}")) {
            if (h.scopes.isEmpty()) throw new Fail("unexpected '}'");
            h.scopes.pop();
            drop(h.p, h.p);
            return;
        }
        Macro m = k.ident() ? macro(k.text()) : null;
        if (m != null && (m.kind() == MacroKind.SCOPE_C || m.kind() == MacroKind.SCOPE_CXX)) {
            h.scopes.push(new Scope(m.kind() == MacroKind.SCOPE_C ? "C" : "C++", List.of()));
            drop(h.p, h.p);
            return;
        }
        if (m != null && m.kind() == MacroKind.SCOPE_END) {
            if (!h.scopes.isEmpty()) h.scopes.pop();
            drop(h.p, h.p);
            return;
        }
        if (k.is("namespace") || (k.is("inline") && t(sig(h.p + 1)).is("namespace"))) {
            namespace(start);
            return;
        }
        if (k.is("extern") && t(sig(h.p + 1)).kind() == K.STRING) {
            int q = sig(h.p + 1);
            boolean c = h.toks.get(q).text().equals("\"C\"");
            int r = sig(q + 1);
            if (t(r).is("{")) {
                h.scopes.push(new Scope(c ? "C" : "C++", List.of()));
                drop(start, r);
                return;
            }
            h.p = r;
            dispatch(start, c, q);
            return;
        }
        dispatch(start, null, -1);
    }

    void dispatch(int start, Boolean forcedC, int externStr) {
        int q = skipAttributes(h.p);
        Tok h = t(q);
        if (h.is("template")) {
            skipped(start, "template");
        } else if (h.is("using")) {
            types.using(start, q);
        } else if (h.is("static_assert")) {
            drop(start, scanDeclEnd(q)[0]);
        } else if (h.is("typedef")) {
            types.typedef(start, q);
        } else if ((h.is("struct") || h.is("class") || h.is("union")) && types.isClassDefinition(q)) {
            StructDef s = types.classDef(start, q, false, null);
            if (s != null) add(start, s.semi, it -> tr.cview.struct(s, it.last));
        } else if ((h.is("struct") || h.is("class") || h.is("union")) && t(sig(q + 1)).ident()
                && t(sig(sig(q + 1) + 1)).is(";")) {
            String name = t(sig(q + 1)).text();                  // forward declaration
            tr.forwardStructs.add(name);
            String kw = h.is("union") ? "union" : "struct";
            replaced(start, sig(sig(q + 1) + 1), "typedef " + kw + " " + name + " " + name + ";");
        } else if (h.is("enum") && types.isEnumDefinition(q)) {
            EnumDef en = types.enumDef(start, q, false, null);
            add(start, en.semi, it -> tr.cview.enumeration(en, it.last));
        } else if (h.is("enum") && t(scanDeclEnd(q)[0]).is(";") && types.isEnumForward(q)) {
            drop(start, scanDeclEnd(q)[0]);                     // enum class X : int; (opaque declaration)
        } else {
            declaration(start, forcedC, externStr);
        }
    }

    void skipped(int start, String why) {
        int end = scanDeclEnd(start)[0];
        String summary = summary(start, end);
        warn(start, why + " skipped: " + summary);
        add(start, end, it -> "/* [CppToC] not translated (" + why + "): " + summary.replace("*/", "* /") + " */");
    }

    void namespace(int start) {
        int i = sig(h.p);
        if (t(i).is("inline")) i = sig(i + 1);
        i = sig(i + 1);
        List<String> parts = new ArrayList<>();
        while (t(i).ident() || t(i).is("::")) {
            if (t(i).ident() && !t(i).is("inline")) parts.add(t(i).text());
            i = sig(i + 1);
        }
        i = skipAttributes(i);
        if (t(i).is("=")) {
            drop(start, scanDeclEnd(i)[0]);
            return;
        }
        if (!t(i).is("{")) throw new Fail("expected '{' after namespace");
        if (parts.isEmpty()) {
            warn(start, "anonymous namespace skipped (internal linkage)");
            drop(start, skipBalanced(i) - 1);
            return;
        }
        h.scopes.push(new Scope("ns", parts));
        drop(start, i);
    }

    void declaration(int start, Boolean forcedC, int externStr) {
        int[] de = scanDeclEnd(h.p);
        int last = de[0], body = de[1];
        int stop = body >= 0 ? body : t(last).is(";") ? last : last + 1;
        List<Integer> sg = new ArrayList<>();
        for (int i = h.p; i < stop; i++) if (t(i).kind() != K.COMMENT && t(i).kind() != K.DIRECTIVE) sg.add(i);
        if (sg.isEmpty()) {
            drop(start, last);
            return;
        }
        int k = 0;
        boolean isStatic = false, isInline = false, isConstexpr = false, isConsteval = false, isExtern = false;
        boolean cl = false;
        while (k < sg.size()) {
            Tok x = t(sg.get(k));
            if (x.is("[") && k + 1 < sg.size() && t(sg.get(k + 1)).is("[")) {
                k = groupEnd(sg, k);
                continue;
            }
            if (!x.ident()) break;
            String w = x.text();
            if (GROUP_ATTRS.contains(w)) {
                k = groupEnd(sg, k + 1);
            } else if (w.equals("static")) {
                isStatic = true;
                k++;
            } else if (Set.of("inline", "__inline", "__inline__", "__forceinline").contains(w)) {
                isInline = true;
                k++;
            } else if (w.equals("constexpr")) {
                isConstexpr = true;
                k++;
            } else if (w.equals("consteval")) {
                isConsteval = true;
                k++;
            } else if (w.equals("extern")) {
                isExtern = true;
                k++;
            } else if (Set.of("constinit", "thread_local", "register", "explicit", "friend", "virtual").contains(w)) {
                k++;
            } else if (isDecor(x)) {
                Macro m = macro(w);
                if (m != null && m.cLinkage()) cl = true;
                if (m != null && m.value().matches("(?s).*\\bstatic\\b.*")) isStatic = true;
                if (m != null && m.value().matches("(?s).*\\binline\\b.*")) isInline = true;
                k++;
                if (m != null && m.fnLike()) k = groupEnd(sg, k);
            } else if (isFnMacro(x) && k + 1 < sg.size() && t(sg.get(k + 1)).is("(")) {
                k = groupEnd(sg, k + 1);                               // attribute-like macro: DEPRECATED("...")
            } else {
                break;
            }
        }
        int retStart = k, nameK = -1;
        boolean operator = false;
        for (int j = k; j < sg.size(); j++) {
            Tok x = t(sg.get(j));
            if (x.is("operator")) {
                operator = true;
                break;
            }
            if (x.is("=") || x.is("{") || x.is("[")) break;
            if (x.is("(")) {
                Tok prev = j > 0 ? t(sg.get(j - 1)) : EOF;
                if (prev.ident() && (GROUP_ATTRS.contains(prev.text()) || isFnMacro(prev))) {
                    j = groupEnd(sg, j) - 1;
                    continue;
                }
                int inner = j + 1;
                while (inner < sg.size() && isDecor(t(sg.get(inner)))) inner++;
                boolean declaratorParen = inner < sg.size() && (t(sg.get(inner)).is("*") || t(sg.get(inner)).is("&"));
                if (j > retStart && prev.ident() && !NOT_FUNCTION.contains(prev.text()) && !declaratorParen) nameK = j - 1;
                break;
            }
            if (x.is("<")) {
                break;
            }
        }
        if (operator) {
            skipped(start, "operator overload");
            return;
        }
        if (nameK < 0) {
            variable(start, last, sg, retStart, isConstexpr, isExtern, forcedC != null ? forcedC : cl || cLinkageScope(),
                    externStr);
            return;
        }
        if (nameK > 0 && t(sg.get(nameK - 1)).is("::")) {
            skipped(start, "definition of a qualified name");
            return;
        }
        Fn f = new Fn(h);
        f.first = start;
        f.last = last;
        f.hasBody = body >= 0;
        f.isStatic = isStatic;
        f.isInline = isInline || isConstexpr;
        f.cLinkage = forcedC != null ? forcedC : cl || cLinkageScope();
        f.externIdx = externStr >= 0 ? prevSig(externStr) : -1;
        f.ns = ns();
        f.nameIdx = sg.get(nameK);
        f.name = t(f.nameIdx).text();
        f.open = sg.get(nameK + 1);
        f.close = skipBalanced(f.open) - 1;
        f.conds = currentConds();
        for (int j = retStart; j < nameK; j++) {
            Tok x = t(sg.get(j));
            if (x.is("[") && j + 1 < nameK && t(sg.get(j + 1)).is("[")) {
                j = groupEnd(sg, j) - 1;
            } else if (x.ident() && GROUP_ATTRS.contains(x.text())) {
                j = groupEnd(sg, j + 1) - 1;
            } else if (isDecor(x)) {
                Macro m = macro(x.text());
                if (m != null && m.fnLike()) j = groupEnd(sg, j + 1) - 1;
            } else {
                f.retIdx.add(sg.get(j));
            }
        }
        if (isConsteval) f.problem = "consteval";
        int j = sig(f.close + 1);
        while (j < stop && f.problem == null) {
            Tok x = t(j);
            if (x.is("noexcept") || x.is("throw")) {
                j = sig(j + 1);
                if (t(j).is("(")) j = sig(skipBalanced(j));
            } else if (x.is("[") && t(sig(j + 1)).is("[")) {
                j = sig(skipBalanced(j));
            } else if (x.ident() && GROUP_ATTRS.contains(x.text())) {
                j = sig(skipBalanced(sig(j + 1)));
            } else if (isDecor(x)) {
                j = sig(j + 1);
                if (isFnMacro(x) && t(j).is("(")) j = sig(skipBalanced(j));
            } else if (x.is("->")) {
                f.problem = "trailing return type";
            } else if (x.is("=")) {
                f.problem = "deleted function";
            } else if (x.is("(")) {
                f.problem = "returns a function pointer";
            } else {
                f.problem = "unexpected '" + x.text() + "' after the parameters";
            }
        }
        params(f);
        tr.functions.add(f);
        add(start, last, it -> tr.cview.function(f, it.last));
    }

    /** sg.get(k) opens a group: the index in sg after its end. */
    int groupEnd(List<Integer> sg, int k) {
        if (k >= sg.size()) return k;
        int end = skipBalanced(sg.get(k));
        while (k < sg.size() && sg.get(k) < end) k++;
        return k;
    }

    void params(Fn f) {
        List<int[]> ranges = new ArrayList<>();
        int from = f.open + 1, depth = 0, angle = 0;
        boolean eq = false;
        for (int i = f.open + 1; i <= f.close; i++) {
            Tok x = t(i);
            if (i == f.close || x.is(",") && depth == 0 && angle == 0) {
                ranges.add(new int[]{from, i - 1});
                from = i + 1;
                eq = false;
                continue;
            }
            if (x.is("(") || x.is("[") || x.is("{")) depth++;
            else if (x.is(")") || x.is("]") || x.is("}")) depth--;
            else if (x.is("=") && depth == 0) eq = true;
            else if (!eq && x.is("<") && t(prevSig(i)).ident()) angle++;
            else if (!eq && x.is(">") && angle > 0) angle--;
        }
        for (int[] r : ranges) {
            int a = sig(r[0]), b = prevSig(r[1] + 1);
            if (a > b) continue;
            if (a == b && t(a).is("void") && ranges.size() == 1) continue;
            if (a == b && t(a).is("...")) {
                f.variadic = true;
                continue;
            }
            Param pr = new Param();
            pr.from = a;
            pr.to = b;
            pr.typeTo = b;
            int d = 0;
            for (int i = a; i <= b; i++) {
                if (t(i).is("(") || t(i).is("[") || t(i).is("{")) d++;
                else if (t(i).is(")") || t(i).is("]") || t(i).is("}")) d--;
                else if (t(i).is("=") && d == 0) {
                    pr.eqIdx = i;
                    pr.typeTo = prevSig(i);
                    break;
                }
            }
            for (int i = a; i <= pr.typeTo; i++) {
                if (t(i).is("(")) {
                    pr.funcPtr = true;
                    int close = skipBalanced(i) - 1;
                    for (int j = i + 1; j < close; j++) if (t(j).ident() && !isDecor(t(j))) pr.nameIdx = j;
                    break;
                }
            }
            if (!pr.funcPtr) {
                int end = pr.typeTo;
                while (t(end).is("]")) {                               // T a[N]: skip the dimensions
                    int depth2 = 0, j = end;
                    for (; j >= a; j--) {
                        if (t(j).is("]")) depth2++;
                        if (t(j).is("[") && --depth2 == 0) break;
                    }
                    end = prevSig(j);
                }
                Tok last = t(end);
                int before = prevSig(end);
                boolean hasType = false;
                for (int i = a; i < end; i++) {
                    if (t(i).kind() == K.COMMENT) continue;
                    if (!t(i).is("const") && !t(i).is("volatile") && !t(i).is("struct") && !t(i).is("enum")
                            && !t(i).is("class") && !t(i).is("union") && !t(i).is("::")) hasType = true;
                }
                if (last.ident() && !PRIM_WORDS.contains(last.text()) && hasType && !t(before).is("::")
                        && !isDecor(last)) {
                    pr.nameIdx = end;
                }
            }
            pr.name = pr.nameIdx >= 0 ? t(pr.nameIdx).text() : null;
            f.params.add(pr);
        }
    }

    void variable(int start, int last, List<Integer> sg, int k, boolean isConstexpr, boolean isExtern,
                  boolean cLinkage, int externStr) {
        int eqK = -1;
        for (int j = k; j < sg.size(); j++) {
            if (t(sg.get(j)).is("=") || t(sg.get(j)).is("{")) {
                eqK = j;
                break;
            }
        }
        boolean constant = isConstexpr;
        if (!constant && eqK > 0) {
            boolean hasConst = false, hasPtr = false;
            for (int j = k; j < eqK; j++) {
                if (t(sg.get(j)).is("const")) hasConst = true;
                if (t(sg.get(j)).is("*")) hasPtr = true;
            }
            constant = hasConst && (!hasPtr || t(sg.get(eqK - 2)).is("const"));
        }
        if (constant && eqK > 0 && !isExtern) {
            List<ConstDef> defs = new ArrayList<>();
            int j = sg.get(k);
            List<String> ns = ns();
            while (j < last) {                                        // const int A = 1, B = 2;
                int eq = j;
                while (eq < last && !t(eq).is("=") && !t(eq).is("{")) eq++;
                if (eq >= last) break;
                int nameIdx = prevSig(eq);
                if (t(nameIdx).is("]")) {
                    warn(nameIdx, "constant array not translated");
                    break;
                }
                int end = eq, depth = 0;
                if (t(eq).is("{")) {
                    end = skipBalanced(eq);
                } else {
                    end = eq + 1;
                    while (end < last && !(depth == 0 && t(end).is(","))) {
                        if (t(end).is("(") || t(end).is("[") || t(end).is("{")) depth++;
                        if (t(end).is(")") || t(end).is("]") || t(end).is("}")) depth--;
                        end++;
                    }
                }
                ConstDef c = new ConstDef(h);
                c.bare = t(nameIdx).text();
                c.cName = c.bare;
                c.ns = ns;
                c.keys = Translation.suffixes(ns.isEmpty() ? c.bare : String.join("::", ns) + "::" + c.bare);
                c.exprFrom = sig(eq + 1);
                c.exprTo = t(eq).is("{") ? prevSig(end - 1) : prevSig(end);
                c.first = start;
                c.last = last;
                defs.add(c);
                tr.consts.add(c);
                j = end + 1;
            }
            add(start, last, it -> tr.cview.constants(defs, it));
            return;
        }
        if (isExtern && cLinkage) {
            Item it = add(start, last, x -> {
                Edits e = new Edits();
                if (externStr >= 0) e.replace.put(externStr, "");
                if (externStr >= 0) e.drop(externStr, externStr);
                tr.cview.typeEdits(h, e, sg.get(k), last, true);
                return render(x.first, x.last, e);
            });
            return;
        }
        String summary = summary(start, last);
        warn(start, "variable not translated (" + (isExtern ? "C++ linkage" : "not a constant") + "): " + summary);
        add(start, last, it -> "/* [CppToC] variable not translated: " + summary.replace("*/", "* /") + " */");
    }
}
