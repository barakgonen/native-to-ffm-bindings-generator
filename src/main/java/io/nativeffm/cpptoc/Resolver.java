package io.nativeffm.cpptoc;

import io.nativeffm.cpptoc.Ast.*;
import io.nativeffm.cpptoc.Lexer.K;
import io.nativeffm.cpptoc.Lexer.Tok;

import java.util.*;
import java.util.stream.Stream;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.nativeffm.cpptoc.Lexer.EOF;
import static io.nativeffm.cpptoc.Words.*;

/**
 * Types and functions, after all headers are parsed: what a type spelling resolves to (through typedefs), the
 * decisions that need every definition (names, forward declarations, warnings), and each function's analysis for
 * the safe wrapper.
 */
final class Resolver {

    private final Translation tr;

    Resolver(Translation tr) {
        this.tr = tr;
    }

    TypeInfo typeInfo(Header u, int from, int to, int skip) {
        List<String> words = new ArrayList<>();
        int ptr = 0;
        boolean ref = false, constBase = false, seenPtr = false, funcPtr = false, std = false;
        String bad = null;
        for (int i = from; i <= to && i < u.n; i++) {
            if (i == skip) continue;
            Tok x = u.t(i);
            if (x.kind() == K.COMMENT || x.kind() == K.DIRECTIVE) continue;
            if (x.is("[") && u.t(u.sig(i + 1)).is("[")) {
                i = u.skipBalanced(i) - 1;
            } else if (x.ident() && GROUP_ATTRS.contains(x.text()) && u.t(u.sig(i + 1)).is("(")) {
                i = u.skipBalanced(u.sig(i + 1)) - 1;
            } else if (tr.isDecor(x)) {
                if (tr.isFnMacro(x) && u.t(u.sig(i + 1)).is("(")) i = u.skipBalanced(u.sig(i + 1)) - 1;
            } else if (x.is("const") || x.is("volatile")) {
                if (!seenPtr) constBase = true;
            } else if (x.is("struct") || x.is("class") || x.is("union") || x.is("enum") || x.is("typename")) {
                // elaborated type specifier
            } else if (x.is("::")) {
                // qualifier separator (the qualifier itself was not added)
            } else if (x.ident()) {
                if (u.t(u.sig(i + 1)).is("::")) {
                    if (x.is("std")) std = true;
                    continue;
                }
                if (std && !C_STD_TYPES.contains(x.text())) bad = "uses std::" + x.text();
                std = false;
                if (x.is("auto") || x.is("decltype")) bad = "uses " + x.text();
                words.add(x.text());
            } else if (x.is("*")) {
                ptr++;
                seenPtr = true;
            } else if (x.is("&")) {
                ref = true;
                ptr++;
                seenPtr = true;
            } else if (x.is("&&")) {
                bad = "rvalue reference";
            } else if (x.is("<")) {
                bad = "template type";
            } else if (x.is("(")) {
                funcPtr = true;
                break;
            } else if (x.is("[")) {
                i = u.skipBalanced(i) - 1;
            }
        }
        return new TypeInfo(String.join(" ", words), ptr, ref, constBase, funcPtr, bad);
    }

    Resolved resolve(TypeInfo ti) {
        return resolve(ti, 0);
    }

    private Resolved resolve(TypeInfo ti, int depth) {
        if (ti.funcPtr()) return new Resolved(Kind.FUNCPTR, ti.ptr());
        String base = ti.base();
        if (base.isEmpty()) return new Resolved(Kind.UNKNOWN, ti.ptr());
        if (Stream.of(base.split(" ")).allMatch(PRIM_WORDS::contains)) {
            return new Resolved(base.equals("void") && ti.ptr() == 0 ? Kind.VOID : Kind.PRIM, ti.ptr());
        }
        if (C_STD_TYPES.contains(base)) return new Resolved(Kind.PRIM, ti.ptr());
        StructDef s = tr.structs.get(base);
        if (s != null) return new Resolved(s.opaque != null ? Kind.OPAQUE : Kind.STRUCT, ti.ptr());
        if (tr.forwardStructs.contains(base)) return new Resolved(Kind.OPAQUE, ti.ptr());
        EnumDef en = tr.enums.get(base);
        if (en != null) return new Resolved(en.small ? Kind.PRIM : Kind.ENUM, ti.ptr());
        TypedefDef td = tr.typedefs.get(base);
        if (td != null && depth < 16) {
            if (td.unsupported != null) return new Resolved(Kind.UNKNOWN, ti.ptr());
            TypeInfo target = td.using ? typeInfo(td.u, td.typeFrom, td.typeTo, -1)
                    : typeInfo(td.u, td.typeFrom, td.typeTo, td.nameIdx);
            Resolved r = resolve(target, depth + 1);
            return new Resolved(r.kind(), r.ptr() + ti.ptr());
        }
        return new Resolved(Kind.UNKNOWN, ti.ptr());
    }

    /**
     * After parsing: decides what C cannot take (tr.typedefs), names constants of tr.structs and clashing enum class
     * constants, finds the forward declarations C needs, and analyzes every function (types, wrapper name).
     */
    void resolve() {
        // tr.typedefs that cannot be C
        for (TypedefDef td : new LinkedHashSet<>(tr.typedefs.values())) {          // source order: stable warnings
            TypeInfo ti = td.using ? typeInfo(td.u, td.typeFrom, td.typeTo, -1) : typeInfo(td.u, td.typeFrom, td.typeTo, td.nameIdx);
            if (ti.unsupported() != null) {
                td.unsupported = ti.unsupported();
                tr.warn(td.u, td.first, "typedef " + td.names + " not translated: " + ti.unsupported());
            }
        }
        // constants of tr.structs: Outer_N
        for (ConstDef c : tr.consts) {
            if (c.outer != null) {
                c.cName = c.outer.cName + "_" + c.bare;
                c.keys = Translation.suffixes(c.outer.cppName.substring(2) + "::" + c.bare);
                c.outer.local.put(c.bare, c.cName);
            }
            ConstDef cc = c;
            Sym sym = new Sym(c.cName, s -> tr.evaluate(ConstEval.sigToks(cc.u, cc.exprFrom, cc.exprTo),
                    cc.outer != null ? tr.cview.localSyms(cc.outer.local) : Map.of()));
            tr.syms.putIfAbsent(c.cName, sym);
            for (String k : c.keys) tr.syms.putIfAbsent(k, sym);
        }
        // scoped enum constants keep their name in C unless it clashes with another C identifier
        Map<String, Integer> uses = new HashMap<>();
        for (String m : tr.macros.keySet()) uses.merge(m, 1, Integer::sum);
        for (ConstDef c : tr.consts) uses.merge(c.cName, 1, Integer::sum);
        tr.structs.keySet().forEach(k -> uses.merge(k, 1, Integer::sum));
        tr.enums.keySet().forEach(k -> uses.merge(k, 1, Integer::sum));
        tr.typedefs.keySet().forEach(k -> uses.merge(k, 1, Integer::sum));
        for (Fn f : tr.functions) uses.merge(f.name, 1, Integer::sum);
        for (EnumDef en : tr.allEnums) for (String c : en.cNames.keySet()) uses.merge(c, 1, Integer::sum);
        for (EnumDef en : tr.allEnums) {
            if (!en.scoped || en.cName == null) continue;
            for (String c : List.copyOf(en.cNames.keySet())) {
                if (uses.getOrDefault(c, 0) > 1) {
                    en.cNames.put(c, en.cName + "_" + c);
                    tr.warn(en.u, en.kwIdx, "enum class " + en.cName + ": constant " + c + " is " + en.cName + "_" + c
                            + " in C (the name " + c + " is taken)");
                }
            }
            for (int[] c : en.constants) {                         // the C name, for references from expressions
                String bare = en.u.t(c[0]).text();
                Sym s = en.localSyms.get(bare);
                Sym renamed = new Sym(en.cNames.get(bare), x -> s.value());
                for (String k : Translation.qualifiedKeys(en.cppName.substring(2) + "::" + bare)) tr.syms.put(k, renamed);
            }
        }
        for (EnumDef en : tr.allEnums) {
            if (en.small) {
                tr.warn(en.u, en.kwIdx, "enum " + en.cName + " has underlying type " + en.underlying
                        + ": in C (and Java) it is a " + en.underlying + " with constants");
            }
        }
        // C needs "typedef struct X X;" before a struct that uses X (itself, or a struct defined later) by its name
        List<StructDef> order = new ArrayList<>(tr.structs.values());
        for (int si = 0; si < order.size(); si++) {
            StructDef s = order.get(si);
            if (s.opaque != null) continue;
            for (int[] r : s.typeRanges) {
                for (int i = r[0]; i <= r[1]; i++) {
                    Tok x = s.u.t(i);
                    if (!x.ident() || s.u.t(s.u.prevSig(i)).is("struct") || s.u.t(s.u.prevSig(i)).is("union")) continue;
                    StructDef target = tr.structs.get(x.text());
                    if (target == null || target.tag == null || target.opaque != null || s.forwards.contains(target)) continue;
                    if (target == s || order.indexOf(target) > si && target.u == s.u) s.forwards.add(target);
                }
            }
        }
        for (StructDef s : tr.structs.values()) {
            if (s.opaque != null) {
                tr.warn(s.u, s.kwIdx, s.kw + " " + s.cName + " is opaque in C (" + s.opaque + "): usable through pointers only");
            } else if (s.hasDefaults) {
                tr.warn(s.u, s.kwIdx, "struct " + s.cName + " has default member initializers; in C (and in Java's no-arg"
                        + " constructor) the fields start at zero");
            }
        }
        // tr.functions
        Map<String, Integer> overloads = new HashMap<>();
        Set<String> safeNames = new HashSet<>(Set.of("last_error_function", "last_error_type", "last_error_message"));
        for (StructDef s : tr.structs.values()) safeNames.add("layout_" + s.cName);
        for (Fn f : tr.functions) {
            f.overload = overloads.merge(f.qualified(), 1, Integer::sum);
            analyze(f);
            if (f.problem != null) {
                tr.warn(f.u, f.first, f.qualified() + " not translated: " + f.problem);
                continue;
            }
            f.cView = f.cLinkage && !f.hasBody && !f.isInline && !f.isStatic;
            f.safe = f.u.bridged && !f.variadic && !(f.isStatic && !f.hasBody);
            if (f.variadic && f.u.bridged) tr.warn(f.u, f.first, f.qualified() + " is variadic: no wrapper function");
            if (!f.safe) continue;
            String unique = f.name;                                 // overloads: area, area_2, ...
            for (int k = 2; !safeNames.add(unique); k++) unique = f.name + "_" + k;
            if (!unique.equals(f.name)) {
                tr.warn(f.u, f.first, f.qualified() + ": wrapper name " + tr.prefix + f.name + " is taken, using "
                        + tr.prefix + unique);
            }
            f.safeName = tr.prefix + unique;
        }
    }

    private void analyze(Fn f) {
        Header u = f.u;
        if (f.problem != null) return;
        f.ret = typeInfo(u, f.retIdx.isEmpty() ? f.nameIdx : f.retIdx.get(0),
                f.retIdx.isEmpty() ? f.nameIdx - 1 : f.retIdx.get(f.retIdx.size() - 1), -1);
        if (f.retIdx.isEmpty()) f.problem = "no return type (constructor or macro?)";
        if (f.ret.unsupported() != null) f.problem = "return type " + f.ret.unsupported();
        f.retRes = resolve(f.ret);
        if (f.retRes.kind() == Kind.OPAQUE && f.retRes.ptr() == 0) {
            f.problem = "returns " + f.ret.base() + " by value, which is not a C struct";
        }
        int i = 0;
        for (Param p : f.params) {
            i++;
            if (p.name == null) p.name = "arg" + i;
            p.type = typeInfo(u, p.from, p.typeTo, p.nameIdx);
            if (p.type.unsupported() != null && f.problem == null) {
                f.problem = "parameter " + p.name + " " + p.type.unsupported();
            }
            p.res = resolve(p.type);
            p.ref = p.type.ref();
            if (p.res.kind() == Kind.OPAQUE && p.res.ptr() == 0 && f.problem == null) {
                f.problem = "passes " + p.type.base() + " by value, which is not a C struct";
            }
            p.byValueStruct = !p.funcPtr && p.res.kind() == Kind.STRUCT && p.res.ptr() == 0 && !hasDims(u, p);
            p.constRefValue = p.ref && p.type.ptr() == 1 && p.type.constBase()
                    && (p.res.kind() == Kind.PRIM || p.res.kind() == Kind.ENUM) && p.res.ptr() == 1;
        }
        Set<String> names = new HashSet<>();
        for (Param p : f.params) {
            while (!names.add(p.name)) p.name += "_";
        }
    }

    static boolean hasDims(Header u, Param p) {
        return u.t(p.typeTo).is("]");
    }

    boolean probed(StructDef s) {
        return s.opaque == null && !s.isUnion && !s.directives;
    }
}
