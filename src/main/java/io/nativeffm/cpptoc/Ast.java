package io.nativeffm.cpptoc;

import java.util.*;
import java.util.function.Function;

/**
 * What the parser finds in the headers: macros, structs, enums, constants, typedefs and functions, each with the
 * token positions the C view and the safe wrapper files are rendered from. Plus Edits and Item, the pieces of rendering.
 */
final class Ast {

    private Ast() {
    }

    enum MacroKind { DECOR, SCOPE_C, SCOPE_CXX, SCOPE_END, OTHER }

    /** A #define. DECOR: expands to nothing but linkage/attributes (export macros like DEVICE_API). */
    record Macro(String name, boolean fnLike, String value, MacroKind kind, boolean cLinkage) {}

    record Scope(String kind, List<String> names) {}             // "ns", "C", "C++"

    /** A preprocessor conditional the C view keeps (or, if it is only about __cplusplus, evaluates). */
    static final class Cond {
        final boolean cxx;
        boolean taken;
        final List<String> previous = new ArrayList<>();
        String current;

        Cond(boolean cxx) {
            this.cxx = cxx;
        }
    }

    /** Token edits for rendering a range of source with the original layout. */
    static final class Edits {
        final Map<Integer, String> replace = new HashMap<>();
        final BitSet drop = new BitSet();
        final Map<Integer, String> before = new HashMap<>();
        final Map<Integer, String> after = new HashMap<>();

        void drop(int from, int toInclusive) {
            if (toInclusive >= from) drop.set(from, toInclusive + 1);
        }

        Edits copy() {
            Edits e = new Edits();
            e.replace.putAll(replace);
            e.drop.or(drop);
            e.before.putAll(before);
            e.after.putAll(after);
            return e;
        }
    }

    /** A piece of a header in source order, covering tokens [first, last]; render gives its C text (null: dropped). */
    static final class Item {
        final int first;
        int last;
        Function<Item, String> render;

        Item(int first, int last) {
            this.first = first;
            this.last = last;
        }
    }

    static final class StructDef {
        final Header u;
        String kw, tag, cName, cppName;
        StructDef outer;
        boolean typedefForm, inline, directives, hasDefaults, hasAnonymous, isUnion;
        boolean forwardDeclared;                                  // "typedef struct X X;" already written
        final List<StructDef> forwards = new ArrayList<>();       // structs it uses before they are defined
        int first, kwIdx, open, close, semi = -1, declFrom, declTo;
        String opaque;
        Edits e = new Edits();
        final List<String> fields = new ArrayList<>();          // direct named members, for the layout probe
        final List<FieldDef> fieldDefs = new ArrayList<>();     // the same members with their types, for the model
        final List<Object> hoisted = new ArrayList<>();          // nested types and constants, emitted before it
        final List<int[]> typeRanges = new ArrayList<>();
        final List<int[]> exprRanges = new ArrayList<>();
        final Map<String, String> local = new HashMap<>();       // static constants: bare name -> C name
        List<String> conds = List.of();
        int hoistFrom = -1;                                       // nested: render range start (leading comments)

        StructDef(Header u) {
            this.u = u;
        }
    }

    static final class EnumDef {
        final Header u;
        String tag, cName, cppName, underlying;
        boolean scoped, typedefForm, small;
        StructDef outer;
        int first, kwIdx, scopedIdx = -1, tagIdx = -1, colonIdx = -1, underTo = -1, open, close, semi = -1;
        int hoistFrom = -1;
        final List<int[]> constants = new ArrayList<>();          // {nameIdx, eqIdx, exprFrom, exprTo}
        final Map<String, String> cNames = new LinkedHashMap<>(); // C++ name -> C name
        final Map<String, Sym> localSyms = new HashMap<>();
        List<String> conds = List.of();

        EnumDef(Header u) {
            this.u = u;
        }
    }

    static final class ConstDef {
        final Header u;
        String bare, cName;
        List<String> keys;
        int exprFrom, exprTo, first, last;
        StructDef outer;
        List<String> ns;

        ConstDef(Header u) {
            this.u = u;
        }
    }

    static final class TypedefDef {
        final Header u;
        final List<String> names = new ArrayList<>();
        int first, semi, nameIdx = -1, typeFrom, typeTo;
        boolean using;
        StructDef outer;
        String unsupported;

        TypedefDef(Header u) {
            this.u = u;
        }
    }

    static final class Param {
        int from, typeTo, nameIdx = -1, eqIdx = -1, to;
        boolean funcPtr;
        String name;
        TypeInfo type;
        Resolved res;
        // wrapper mapping
        boolean byValueStruct, constRefValue, ref;
    }

    static final class Fn {
        final Header u;
        String name, safeName, problem, javaNote;
        List<String> ns;
        boolean hasBody, isStatic, isInline, cLinkage, variadic, cView, safe;
        int first, last, nameIdx, open, close, externIdx = -1;
        final List<Integer> retIdx = new ArrayList<>();
        final List<Param> params = new ArrayList<>();
        List<String> conds = List.of();
        TypeInfo ret;
        Resolved retRes;
        int overload;                                              // 1-based among functions with the same name

        Fn(Header u) {
            this.u = u;
        }

        String qualified() {
            return ns.isEmpty() ? name : String.join("::", ns) + "::" + name;
        }
    }

    /**
     * A data member, for the model: its type (base name and qualifiers from the declaration's specifiers), the
     * pointer depth and array dimensions of its declarator, the C declaration text (for javadoc), and for an
     * anonymous struct or union member its own fields.
     */
    record FieldDef(String name, TypeInfo spec, String specText, int ptr, int dims, boolean funcPtr, String decl,
                    StructDef anon) {}

    /** A parsed type: base name (C spelling, qualifiers stripped), pointer depth (a reference counts as one). */
    record TypeInfo(String base, int ptr, boolean ref, boolean constBase, boolean funcPtr, String unsupported) {}

    enum Kind { VOID, PRIM, ENUM, STRUCT, OPAQUE, UNKNOWN, FUNCPTR }

    record Resolved(Kind kind, int ptr) {}

    /** A constant the expression evaluator can use: an enum constant, constexpr, static member or #define. */
    static final class Sym {
        final String cName;
        final Function<Sym, Long> compute;
        Long value;
        boolean busy, done;

        Sym(String cName, Function<Sym, Long> compute) {
            this.cName = cName;
            this.compute = compute;
        }

        Long value() {
            if (done) return value;
            if (busy) return null;
            busy = true;
            try {
                value = compute.apply(this);
            } finally {
                busy = false;
            }
            done = true;
            return value;
        }
    }
}
