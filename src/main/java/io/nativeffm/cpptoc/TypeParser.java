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
 * Type definitions: struct, class and union (data members, nested types, static constants; what makes a class
 * opaque in C), enum and enum class, typedef and using. They are recorded in the Translation and become Items of
 * the header, rendered later by CView.
 */
final class TypeParser extends HeaderPass {

    TypeParser(Header h) {
        super(h);
    }

    boolean isClassDefinition(int q) {
        int i = skipAttributes(sig(q + 1));
        if (t(i).ident() && !t(i).is("final")) i = sig(i + 1);
        i = skipAttributes(i);
        if (t(i).is("final")) i = sig(i + 1);
        return t(i).is("{") || t(i).is(":");
    }

    boolean isEnumDefinition(int q) {
        int i = sig(q + 1);
        if (t(i).is("class") || t(i).is("struct")) i = sig(i + 1);
        i = skipAttributes(i);
        if (t(i).ident()) i = sig(i + 1);
        if (t(i).is(":")) {
            while (i < h.n && !t(i).is("{") && !t(i).is(";")) i = sig(i + 1);
        }
        return t(i).is("{");
    }

    boolean isEnumForward(int q) {
        int i = sig(q + 1);
        if (t(i).is("class") || t(i).is("struct")) i = sig(i + 1);
        return t(i).ident() && (t(sig(i + 1)).is(":") || t(sig(i + 1)).is(";"));
    }

    void typedef(int start, int q) {
        int r = skipAttributes(sig(q + 1));
        Tok h = t(r);
        if ((h.is("struct") || h.is("class") || h.is("union")) && isClassDefinition(r)) {
            StructDef s = classDef(start, r, true, null);
            if (s != null) add(start, s.semi, it -> tr.cview.struct(s, it.last));
            return;
        }
        if (h.is("enum") && isEnumDefinition(r)) {
            EnumDef en = enumDef(start, r, true, null);
            add(start, en.semi, it -> tr.cview.enumeration(en, it.last));
            return;
        }
        int semi = scanDeclEnd(q)[0];
        TypedefDef td = new TypedefDef(this.h);
        td.first = start;
        td.semi = semi;
        td.typeFrom = r;
        td.typeTo = semi - 1;
        typedefNames(td, r, semi - 1);
        for (String name : td.names) tr.typedefs.putIfAbsent(name, td);
        add(start, semi, it -> tr.cview.typedef(td, it.last));
    }

    void typedefNames(TypedefDef td, int from, int to) {
        int depth = 0, last = -1;
        for (int i = from; i <= to + 1; i++) {
            boolean end = i > to;
            Tok x = end ? EOF : t(i);
            if (!end && x.kind() == K.COMMENT) continue;
            if (!end && x.is("(") && depth == 0) {
                int close = skipBalanced(i) - 1;
                int inner = sig(i + 1);
                while (isDecor(t(inner))) inner = sig(inner + 1);
                if (t(inner).is("*") || t(inner).is("&")) {          // (*Name)(...)
                    for (int j = inner; j < close; j++) if (t(j).ident() && !isDecor(t(j))) last = j;
                }
                i = close;
                continue;
            }
            if (!end && x.is("[")) {
                i = skipBalanced(i) - 1;
                continue;
            }
            if (!end && x.is("<")) depth++;
            if (!end && x.is(">")) depth--;
            if (!end && x.ident() && !isDecor(x)) {
                last = i;
                continue;
            }
            if ((end || x.is(",")) && depth == 0) {
                if (last >= 0) {
                    td.names.add(t(last).text());
                    if (td.nameIdx < 0) td.nameIdx = last;
                }
                last = -1;
            }
        }
    }

    void using(int start, int q) {
        int i = sig(q + 1);
        int semi = scanDeclEnd(q)[0];
        if (t(i).is("namespace")) {
            drop(start, semi);
            return;
        }
        int eq = skipAttributes(sig(i + 1));
        if (t(i).ident() && t(eq).is("=")) {
            TypedefDef td = new TypedefDef(this.h);
            td.using = true;
            td.first = start;
            td.semi = semi;
            td.names.add(t(i).text());
            td.nameIdx = i;
            td.typeFrom = sig(eq + 1);
            td.typeTo = prevSig(semi);
            tr.typedefs.putIfAbsent(t(i).text(), td);
            add(start, semi, it -> tr.cview.typedef(td, it.last));
            return;
        }
        drop(start, semi);                                       // using-declaration
    }

    String qualified(StructDef outer, String name) {
        if (outer != null) return outer.cppName + "::" + name;
        List<String> ns = ns();
        return "::" + (ns.isEmpty() ? "" : String.join("::", ns) + "::") + name;
    }

    /** Parses struct/class/union at kw (whose definition starts at start); does not add an item. */
    StructDef classDef(int start, int kw, boolean typedefForm, StructDef outer) {
        StructDef s = new StructDef(h);
        s.first = start;
        s.kwIdx = kw;
        s.kw = t(kw).text();
        s.isUnion = s.kw.equals("union");
        s.typedefForm = typedefForm;
        s.outer = outer;
        int i = skipAttributes(sig(kw + 1));
        if (t(i).ident() && !t(i).is("final")) {
            s.tag = t(i).text();
            i = sig(i + 1);
        }
        i = skipAttributes(i);
        if (t(i).is("final")) i = sig(i + 1);
        if (t(i).is(":")) {
            s.opaque = "has a base class";
            while (i < h.n && !t(i).is("{")) i = sig(i + 1);
        }
        s.open = i;
        s.close = skipBalanced(i) - 1;
        s.cppName = qualified(outer, s.tag != null ? s.tag : "$anonymous");   // nested types need it
        members(s, s.open + 1, s.close - 1, s.kw.equals("class") ? "private" : "public", s.e, s);
        int j = s.close + 1, depth = 0;
        while (j < h.n && !(depth == 0 && t(j).is(";"))) {
            if (t(j).is("(") || t(j).is("[") || t(j).is("{")) depth++;
            if (t(j).is(")") || t(j).is("]") || t(j).is("}")) depth--;
            j++;
        }
        s.semi = j;
        s.declFrom = s.close + 1;
        s.declTo = j - 1;
        List<String> names = declaratorNames(s.declFrom, s.declTo);
        if (typedefForm) {
            s.cName = s.tag != null && names.contains(s.tag) ? s.tag : !names.isEmpty() ? names.get(0) : s.tag;
        } else {
            s.cName = s.tag;
            if (!names.isEmpty() && outer == null) {
                warn(s.declFrom, "variables declared with struct " + s.tag + " are not translated: " + names);
            }
        }
        if (s.cName == null) {
            if (outer == null) {
                warn(kw, "anonymous struct at namespace scope skipped");
                h.p = start;
                drop(start, s.semi);
            }
            return null;
        }
        s.cppName = qualified(outer, s.tag != null ? s.tag : s.cName);
        renest(s);
        s.conds = currentConds();
        if (tr.structs.containsKey(s.cName)) {
            warn(kw, "struct " + s.cName + " is defined twice (namespaces are dropped in C); the second is"
                    + " ignored");
        } else {
            tr.structs.put(s.cName, s);
        }
        return s;
    }

    /** Sets the C++ names of the types nested in s (typedef struct { struct X {...} x; } S: S known last). */
    void renest(StructDef s) {
        for (Object h : s.hoisted) {
            if (h instanceof StructDef c) {
                c.cppName = s.cppName + "::" + (c.tag != null ? c.tag : c.cName);
                renest(c);
            } else if (h instanceof EnumDef en && en.cName != null) {
                en.cppName = s.cppName + "::" + (en.tag != null ? en.tag : en.cName);
            }
        }
    }

    List<String> declaratorNames(int from, int to) {
        List<String> names = new ArrayList<>();
        int last = -1, depth = 0;
        for (int i = from; i <= to + 1; i++) {
            Tok x = i <= to ? t(i) : new Tok(K.PUNCT, ",", 0, 0);
            if (x.kind() == K.COMMENT) continue;
            if (x.is("(") || x.is("[") || x.is("{")) depth++;
            else if (x.is(")") || x.is("]") || x.is("}")) depth--;
            else if (x.ident() && (depth == 0 || t(i - 1).is("*") || t(i - 1).is("("))) last = i;
            else if (x.is(",") && depth == 0 && last >= 0) {
                names.add(t(last).text());
                last = -1;
            }
            else if (x.is("=") && depth == 0 && last >= 0) {
                names.add(t(last).text());
                last = -1;
                while (i <= to && !(t(i).is(",") && depth == 0)) {
                    if (t(i).is("(") || t(i).is("{")) depth++;
                    if (t(i).is(")") || t(i).is("}")) depth--;
                    i++;
                }
            }
        }
        return names;
    }

    /** Members of s in [from, to]; e: the edits of the struct being rendered; top: the struct to hoist into. */
    void members(StructDef s, int from, int to, String access, Edits e, StructDef top) {
        int i = from, leading = from;
        while (i <= to) {
            Tok x = h.toks.get(i);
            if (x.kind() == K.COMMENT) {
                i++;
                continue;
            }
            if (x.kind() == K.DIRECTIVE) {
                s.directives = true;
                i++;
                leading = i;
                continue;
            }
            if ((x.is("public") || x.is("private") || x.is("protected")) && t(sig(i + 1)).is(":")) {
                access = x.text();
                int end = trailingComments(sig(i + 1));
                e.drop(i, end);
                i = end + 1;
                leading = i;
                continue;
            }
            if (x.is(";")) {
                e.drop(i, i);
                i++;
                leading = i;
                continue;
            }
            int m0 = skipAttributes(i);
            Tok h = t(m0);
            int end;
            if ((h.is("struct") || h.is("class") || h.is("union")) && isClassDefinition(m0)
                    || h.is("enum") && isEnumDefinition(m0)) {
                end = nestedType(s, leading, m0, to, access, e, top);
            } else {
                end = memberEnd(m0, to);
                member(s, leading, i, m0, end, access, e, top);
            }
            i = trailingComments(end) + 1;
            leading = i;
        }
    }

    /** The ')' closing the parameters if [i, to] declares a function, else -1. */
    int functionDeclaratorEnd(int i, int to) {
        int prev = -1;
        for (int j = i; j <= to; j++) {
            Tok x = t(j);
            if (x.kind() == K.COMMENT || x.kind() == K.DIRECTIVE) continue;
            if (x.is(";") || x.is("{") || x.is("=") || x.is(":")) return -1;
            if (x.is("operator")) {
                int k = sig(j + 1);
                if (t(k).is("(")) k = sig(k + 1) + 1;               // operator()
                while (k <= to && !t(k).is("(")) k = sig(k + 1);
                return k <= to ? skipBalanced(k) - 1 : -1;
            }
            if (x.is("(")) {
                Tok pt = t(prev);
                int inner = sig(j + 1);
                while (isDecor(t(inner))) inner = sig(inner + 1);
                boolean declaratorParen = t(inner).is("*") || t(inner).is("&") || t(inner).is("^");
                if (prev >= 0 && pt.ident() && !NOT_FUNCTION.contains(pt.text()) && !isFnMacro(pt)
                        && !declaratorParen) {
                    return skipBalanced(j) - 1;
                }
                j = skipBalanced(j) - 1;
                prev = j;
                continue;
            }
            if (x.is("[") || x.is("<")) {
                j = x.is("[") ? skipBalanced(j) - 1 : skipAngles(j) - 1;
                prev = j;
                continue;
            }
            prev = j;
        }
        return -1;
    }

    int memberEnd(int i, int to) {
        int fnClose = functionDeclaratorEnd(i, to);
        if (fnClose >= 0) {
            int j = sig(fnClose + 1);
            while (j <= to) {
                Tok y = t(j);
                if (y.is(";")) return j;
                if (y.is("{")) {
                    int c = skipBalanced(j) - 1;
                    int k = sig(c + 1);
                    return k <= to && t(k).is(";") ? k : c;
                }
                if (y.is(":")) {                                     // constructor initializer list
                    j = sig(j + 1);
                    while (j <= to) {
                        while (j <= to && !t(j).is("(") && !t(j).is("{")) j = sig(j + 1);
                        if (j > to) break;
                        j = sig(skipBalanced(j));
                        if (t(j).is(",")) {
                            j = sig(j + 1);
                            continue;
                        }
                        break;
                    }
                    continue;
                }
                if (y.is("(") || y.is("[")) {
                    j = sig(skipBalanced(j));
                    continue;
                }
                j = sig(j + 1);
            }
            return to;
        }
        int j = i;
        while (j <= to) {
            Tok y = t(j);
            if (y.kind() == K.COMMENT || y.kind() == K.DIRECTIVE) {
                j++;
                continue;
            }
            if (y.is("(") || y.is("[") || y.is("{")) {
                j = skipBalanced(j);
                continue;
            }
            if (y.is(";")) return j;
            j++;
        }
        return to;
    }

    boolean hasWord(int from, int to, String w) {
        for (int i = from; i <= to; i++) if (t(i).is(w)) return true;
        return false;
    }

    void member(StructDef s, int leading, int start, int m0, int end, String access, Edits e, StructDef top) {
        int te = trailingComments(end);
        Tok h = t(m0);
        if (h.is("friend") || h.is("template") || h.is("static_assert")) {
            e.drop(leading, te);
            return;
        }
        if (h.is("using") || h.is("typedef")) {
            hoistTypedef(top, m0, end, leading);
            e.drop(leading, te);
            return;
        }
        if (hasWord(m0, end, "virtual")) {
            s.opaque = "has virtual functions";
            e.drop(leading, te);
            return;
        }
        if (functionDeclaratorEnd(m0, end) >= 0) {                  // member function: no effect on the layout
            e.drop(leading, te);
            return;
        }
        if (hasWord(m0, end, "static")) {
            hoistStaticConst(top, m0, end, leading);
            e.drop(leading, te);
            return;
        }
        dataMember(s, start, end, access, e, top);
    }

    void hoistTypedef(StructDef top, int m0, int end, int leading) {
        TypedefDef td = new TypedefDef(this.h);
        td.outer = top;
        td.first = m0;
        td.semi = end;
        if (t(m0).is("using")) {
            int i = sig(m0 + 1);
            int eq = sig(i + 1);
            if (!t(i).ident() || !t(eq).is("=")) return;
            td.using = true;
            td.names.add(t(i).text());
            td.nameIdx = i;
            td.typeFrom = sig(eq + 1);
            td.typeTo = prevSig(end);
        } else {
            td.typeFrom = sig(m0 + 1);
            td.typeTo = end - 1;
            typedefNames(td, td.typeFrom, td.typeTo);
        }
        for (String name : td.names) tr.typedefs.putIfAbsent(name, td);
        top.hoisted.add(td);
    }

    void hoistStaticConst(StructDef top, int m0, int end, int leading) {
        boolean constant = hasWord(m0, end, "constexpr") || hasWord(m0, end, "const");
        int eq = -1;
        for (int i = m0; i < end; i++) {
            if (t(i).is("=") || t(i).is("{")) {
                eq = i;
                break;
            }
        }
        if (!constant || eq < 0) return;                            // other static data: not in the layout
        int nameIdx = prevSig(eq);
        if (!t(nameIdx).ident()) return;
        ConstDef c = new ConstDef(h);
        c.outer = top;
        c.bare = t(nameIdx).text();
        c.exprFrom = sig(eq + 1);
        c.exprTo = t(eq).is("{") ? prevSig(skipBalanced(eq) - 1) : prevSig(end);
        if (t(eq).is("{")) c.exprFrom = sig(eq + 1);
        c.first = m0;
        c.last = end;
        top.hoisted.add(c);
        tr.consts.add(c);
    }

    void dataMember(StructDef s, int from, int end, String access, Edits e, StructDef top) {
        String bad = null;
        int depth = 0;
        for (int i = from; i < end; i++) {
            Tok x = t(i);
            if (x.is("(") || x.is("[") || x.is("{")) depth++;
            if (x.is(")") || x.is("]") || x.is("}")) depth--;
            if (x.is("=")) break;
            if (x.is("<") && depth == 0) bad = "a member uses a template type";
            if (x.is("std") && t(sig(i + 1)).is("::") && !C_STD_TYPES.contains(t(sig(sig(i + 1) + 1)).text())) {
                bad = "a member uses std::" + t(sig(sig(i + 1) + 1)).text();
            }
            if ((x.is("&") || x.is("&&")) && depth == 0) bad = "has a reference member";
            if (x.is("auto")) bad = "a member uses auto";
        }
        if (bad != null && s.opaque == null) s.opaque = bad;
        if (!access.equals("public") && s.opaque == null) s.opaque = "has " + access + " data members";
        s.typeRanges.add(new int[]{from, end});
        // declarators: names, array dimensions, bit-fields, default member initializers
        depth = 0;
        int last = -1, groupFrom = from;
        boolean bitfield = false;
        TypeInfo spec = null;                                        // the specifiers, shared by all declarators
        String specText = null;
        for (int i = from; i <= end; i++) {
            Tok x = t(i);
            if (x.kind() == K.COMMENT) continue;
            if (x.is("(") && depth == 0) {                          // (*callback)(int)
                int close = skipBalanced(i) - 1;
                for (int j = i + 1; j < close; j++) if (t(j).ident() && !isDecor(t(j))) last = j;
                i = close;
                if (t(sig(i + 1)).is("(")) i = skipBalanced(sig(i + 1)) - 1;
                continue;
            }
            if (x.is("[") && t(sig(i + 1)).is("[")) {
                int close = skipBalanced(i) - 1;
                e.drop(i, close);
                i = close;
                continue;
            }
            if (x.is("[")) {
                int close = skipBalanced(i) - 1;
                if (close > i + 1) s.exprRanges.add(new int[]{sig(i + 1), prevSig(close)});
                i = close;
                continue;
            }
            if (x.is(":") && depth == 0) bitfield = true;
            if (x.is("{") || x.is("=")) {                            // default member initializer
                int j = x.is("{") ? skipBalanced(i) : i;
                if (x.is("=")) {
                    int d = 0;
                    while (j < end && !(d == 0 && t(j).is(","))) {
                        if (t(j).is("(") || t(j).is("[") || t(j).is("{")) d++;
                        if (t(j).is(")") || t(j).is("]") || t(j).is("}")) d--;
                        j++;
                    }
                }
                e.drop(i, j - 1);
                s.hasDefaults = true;
                i = j - 1;
                continue;
            }
            if (x.ident() && !x.is("const") && !x.is("volatile") && !x.is("mutable") && !bitfield) last = i;
            if (x.is("mutable")) e.drop(i, i);
            if ((x.is(",") || x.is(";")) && depth == 0) {
                if (last >= 0 && !bitfield) {
                    s.fields.add(t(last).text());
                    s.fieldDefs.add(fieldDef(from, groupFrom, i - 1, last, spec, specText));
                    spec = s.fieldDefs.getLast().spec();
                    specText = s.fieldDefs.getLast().specText();
                }
                groupFrom = i + 1;
                last = -1;
                bitfield = false;
            }
        }
    }

    /** A struct/union/enum definition inside a struct: anonymous ones stay inline, named ones are hoisted. */
    int nestedType(StructDef s, int leading, int m0, int to, String access, Edits e, StructDef top) {
        Tok h = t(m0);
        if (h.is("enum")) {
            EnumDef en = enumDef(m0, m0, false, top);
            en.hoistFrom = leading;
            if (en.cName == null) {
                warn(m0, "anonymous enum inside struct " + top.tag + ": constants hoisted");
            }
            top.hoisted.add(en);
            List<String> names = declaratorNames(en.close + 1, en.semi - 1);
            if (names.isEmpty()) {
                e.drop(leading, trailingComments(en.semi));
            } else {
                e.drop(leading, en.close);
                e.before.put(sig(en.close + 1), (en.cName == null ? "int" : en.cName) + " ");
                s.fields.addAll(names);
                declaredAs(s, en.close + 1, en.semi - 1, en.cName == null ? "int" : en.cName, null);
                s.typeRanges.add(new int[]{sig(en.close + 1), en.semi});
            }
            return en.semi;
        }
        int i = skipAttributes(sig(m0 + 1));
        boolean named = t(i).ident() && !t(i).is("final");
        if (!named) {                                                // anonymous member: stays inline
            StructDef inner = new StructDef(this.h);
            inner.inline = true;
            inner.kw = h.text();
            inner.isUnion = h.is("union");
            inner.open = i;
            inner.close = skipBalanced(i) - 1;
            if (h.is("class")) e.replace.put(m0, "struct");
            members(inner, inner.open + 1, inner.close - 1, h.is("class") ? "private" : "public", e, top);
            if (inner.opaque != null && s.opaque == null) s.opaque = inner.opaque;
            s.typeRanges.addAll(inner.typeRanges);
            s.exprRanges.addAll(inner.exprRanges);
            int semi = inner.close + 1;
            while (semi <= to && !t(semi).is(";")) semi++;
            List<String> names = declaratorNames(inner.close + 1, semi - 1);
            if (names.isEmpty()) s.hasAnonymous = true; else s.fields.addAll(names);
            declaredAs(s, inner.close + 1, semi - 1, null, inner);
            return semi;
        }
        StructDef inner = classDef(m0, m0, false, top);
        if (inner == null) return memberEnd(m0, to);
        inner.hoistFrom = leading;
        top.hoisted.add(inner);
        List<String> names = declaratorNames(inner.close + 1, inner.semi - 1);
        if (names.isEmpty()) {
            e.drop(leading, trailingComments(inner.semi));
        } else {
            e.drop(leading, inner.close);
            e.before.put(sig(inner.close + 1), inner.cName + " ");
            s.fields.addAll(names);
            declaredAs(s, inner.close + 1, inner.semi - 1, inner.cName, null);
            s.typeRanges.add(new int[]{sig(inner.close + 1), inner.semi});
        }
        return inner.semi;
    }

    EnumDef enumDef(int start, int kw, boolean typedefForm, StructDef outer) {
        EnumDef en = new EnumDef(h);
        en.first = start;
        en.kwIdx = kw;
        en.typedefForm = typedefForm;
        en.outer = outer;
        int i = sig(kw + 1);
        if (t(i).is("class") || t(i).is("struct")) {
            en.scoped = true;
            en.scopedIdx = i;
            i = sig(i + 1);
        }
        i = skipAttributes(i);
        if (t(i).ident()) {
            en.tag = t(i).text();
            en.tagIdx = i;
            i = sig(i + 1);
        }
        int underFrom = -1;
        if (t(i).is(":")) {
            en.colonIdx = i;
            underFrom = sig(i + 1);
            int j = underFrom;
            while (j < h.n && !t(j).is("{")) j = sig(j + 1);
            en.underTo = prevSig(j);
            i = j;
        }
        en.open = i;
        en.close = skipBalanced(i) - 1;
        int j = sig(en.open + 1);
        while (j < en.close) {
            if (!t(j).ident()) {
                j = sig(j + 1);
                continue;
            }
            int nameIdx = j;
            j = skipAttributes(sig(j + 1));
            int eq = -1, ef = -1, et = -1;
            if (t(j).is("=")) {
                eq = j;
                ef = sig(j + 1);
                int k = ef, depth = 0;
                while (k < en.close && !(depth == 0 && t(k).is(","))) {
                    if (t(k).is("(") || t(k).is("[") || t(k).is("{")) depth++;
                    if (t(k).is(")") || t(k).is("]") || t(k).is("}")) depth--;
                    k++;
                }
                et = prevSig(k);
                j = k;
            }
            en.constants.add(new int[]{nameIdx, eq, ef, et});
            while (j < en.close && !t(j).is(",")) j++;
            j = sig(j + 1);
        }
        int semi = en.close + 1;
        while (semi < h.n && !t(semi).is(";")) semi++;
        en.semi = semi;
        List<String> names = declaratorNames(en.close + 1, semi - 1);
        en.cName = typedefForm && !names.isEmpty() ? (en.tag != null && names.contains(en.tag) ? en.tag : names.get(0))
                : en.tag;
        en.cppName = en.cName == null ? null : qualified(outer, en.tag != null ? en.tag : en.cName);
        if (underFrom >= 0) {
            TypeInfo ut = tr.resolver.typeInfo(h, underFrom, en.underTo, -1);
            en.underlying = ut.base();
            en.small = !INT_LIKE.contains(ut.base());
        }
        en.conds = currentConds();
        List<String> ns = outer != null ? List.of() : ns();
        for (int[] c : en.constants) {
            String bare = t(c[0]).text();
            List<String> keys = new ArrayList<>();
            String scope = en.cppName == null ? null : en.cppName.substring(2);
            if (scope != null) keys.addAll(Translation.qualifiedKeys(scope + "::" + bare));       // Grade::PREMIUM
            if (!en.scoped) {                                                     // GREEN, ns::GREEN
                String nsScope = outer != null ? outer.cppName.substring(2) : String.join("::", ns);
                keys.addAll(Translation.suffixes(nsScope.isEmpty() ? bare : nsScope + "::" + bare));
            }
            int[] cc = c;
            Sym sym = new Sym(bare, x -> ConstEval.enumValue(tr, en, cc));
            en.localSyms.put(bare, sym);
            for (String k : keys) tr.syms.putIfAbsent(k, sym);
            en.cNames.put(bare, bare);
        }
        tr.allEnums.add(en);
        if (en.cName != null) tr.enums.putIfAbsent(en.cName, en);
        return en;
    }

    // ---------------------------------------------------------- field types, for the model

    /**
     * The FieldDef of the declarator [groupFrom, groupTo] (its name at nameIdx) of the member that starts at from.
     * spec: the specifiers, if an earlier declarator of the same member already read them (int a, *b;).
     */
    private FieldDef fieldDef(int from, int groupFrom, int groupTo, int nameIdx, TypeInfo spec, String specText) {
        if (spec == null) {
            spec = tr.resolver.typeInfo(h, from, nameIdx - 1, -1);
            specText = specifiers(from, nameIdx);
        }
        int ptr = 0, dims = 0;
        boolean funcPtr = false;
        for (int i = groupFrom; i <= groupTo; i++) {
            Tok x = t(i);
            if (x.is("=") || x.is("{") || x.is(":")) break;                    // initializer, bit-field width
            if (x.is("[")) {
                if (i > nameIdx && !t(sig(i + 1)).is("[")) dims++;            // not [[attribute]]
                i = skipBalanced(i) - 1;
            } else if (x.is("(") && i < nameIdx) {
                int inner = sig(i + 1);
                while (isDecor(t(inner))) inner = sig(inner + 1);
                if (t(inner).is("*") || t(inner).is("&") || t(inner).is("^")) funcPtr = true;   // (*callback)(int)
                else i = skipBalanced(i) - 1;                                                  // alignas(16)
            } else if ((x.is("*") || x.is("&")) && i < nameIdx) {
                ptr++;
            }
        }
        List<String> words = new ArrayList<>();
        if (funcPtr) {                                                         // the declaration as written
            for (int i = from; i <= groupTo && !t(i).is("="); i++) {
                if (t(i).kind() != K.COMMENT && !isDecor(t(i))) words.add(t(i).text());
            }
        } else {
            words.add(specText);
            for (int k = 0; k < ptr; k++) words.add("*");
            words.add(t(nameIdx).text());
            for (int i = nameIdx + 1; i <= groupTo && !t(i).is("=") && !t(i).is("{") && !t(i).is(":"); i++) {
                if (t(i).kind() != K.COMMENT) words.add(t(i).text());               // the dimensions
            }
        }
        words.replaceAll(w -> w.equals("&") ? "*" : w);
        return new FieldDef(t(nameIdx).text(), spec, specText, ptr, dims, funcPtr, Header.joinWords(words), null);
    }

    /** The specifiers of a member as C spells them (const char, enum color_e, unsigned long): for javadoc. */
    private String specifiers(int from, int nameIdx) {
        List<String> words = new ArrayList<>();
        for (int i = from; i < nameIdx; i++) {
            Tok x = t(i);
            if (x.is("*") || x.is("&") || x.is("(")) break;                    // the declarator starts
            if (x.kind() == K.COMMENT || isDecor(x) || x.is("mutable") || x.is("::")) continue;
            if (x.is("[") && t(sig(i + 1)).is("[")) {
                i = skipBalanced(i) - 1;
            } else if (x.ident() && GROUP_ATTRS.contains(x.text()) && t(sig(i + 1)).is("(")) {
                i = skipBalanced(sig(i + 1)) - 1;
            } else if (!(x.ident() && t(sig(i + 1)).is("::"))) {             // ns::Type: the qualifier is dropped
                words.add(x.is("class") ? "struct" : x.text());
            }
        }
        return String.join(" ", words);
    }

    /**
     * FieldDefs for the declarators [from, to] of a type defined in place: struct X {...} x, *px; (type: its C
     * name) or an anonymous struct or union (type null, anon: its definition).
     */
    private void declaredAs(StructDef s, int from, int to, String type, StructDef anon) {
        TypeInfo spec = new TypeInfo(type == null ? "" : type, 0, false, false, false, null);
        String what = type != null ? type : (anon.isUnion ? "union" : "struct") + " {...}";
        for (String name : declaratorNames(from, to)) {
            int nameIdx = from;
            while (nameIdx < to && !t(nameIdx).is(name)) nameIdx++;
            int ptr = 0, dims = 0;
            for (int i = nameIdx - 1; i >= from && !t(i).is(","); i--) if (t(i).is("*")) ptr++;
            for (int i = nameIdx + 1; i <= to && !t(i).is(","); i++) if (t(i).is("[")) dims++;
            s.fieldDefs.add(new FieldDef(name, spec, what, ptr, dims, false, what + " " + "*".repeat(ptr) + name, anon));
        }
    }
}
