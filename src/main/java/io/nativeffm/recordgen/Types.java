package io.nativeffm.recordgen;

import java.lang.foreign.*;
import java.util.*;

/**
 * The Java view of the C types: each struct (its layout from jextract, its fields' types from the model) and each
 * enum, with their Java names. A field's Java type follows from its layout and its C type:
 * <pre>
 *   int, double, ...          primitive            enum E                 E (a Java enum)
 *   char[N]                   String               T[N], T[N][M]          List&lt;T&gt;, List&lt;List&lt;T&gt;&gt;
 *   struct S                  S                    struct S *             S (null for NULL; a cycle: MemorySegment)
 *   anonymous struct          nested class         union, other pointers  MemorySegment
 * </pre>
 */
final class Types {

    /** A C struct: top level, or an anonymous struct nested in another one. */
    static final class StructInfo {
        String className;             // jextract class simple name (null for anonymous)
        String layoutExpr;            // Java expression for the GroupLayout, used in generated code
        GroupLayout layout;
        String snippet;               // C declaration, for javadoc
        Map<String, Model.Field> modelFields = Map.of();
        boolean inModel;              // false: from a system header jextract read too
        boolean union;
        StructInfo parent;            // non-null for anonymous nested structs
        String recordName;
        final List<String> aliases = new ArrayList<>();
        final List<Field> fields = new ArrayList<>();
        final List<StructInfo> nested = new ArrayList<>();
        boolean resolved;

        String ref() {
            return parent == null ? recordName : parent.ref() + "." + recordName;
        }
    }

    record Field(String cName, String javaName, String decl, JType type) {}

    sealed interface JType permits Prim, EnumT, ByValue, Ptr, RawPtr, Bytes, Str, Arr {}
    record Prim(ValueLayout layout) implements JType {}
    record EnumT(EnumInfo info) implements JType {}
    record ByValue(StructInfo target) implements JType {}
    static final class Ptr implements JType {                    // pointer to a struct
        final StructInfo target;
        boolean raw;                                              // true -> kept as MemorySegment (cycle)
        Ptr(StructInfo target) {
            this.target = target;
        }
    }
    record RawPtr() implements JType {}
    record Bytes() implements JType {}                            // union: raw copy
    record Str(long size) implements JType {}                     // char[size]
    record Arr(int size, JType elem) implements JType {}

    static final class EnumInfo {
        String cName, javaName;
        final Map<String, Integer> constants = new LinkedHashMap<>();
    }

    final Map<String, StructInfo> byName = new HashMap<>();       // jextract class + typedef alias names
    final List<StructInfo> all = new ArrayList<>();
    final Map<String, EnumInfo> enums = new LinkedHashMap<>();    // by every name of the enum
    private final List<String> warnings;

    Types(List<String> warnings) {
        this.warnings = warnings;
    }

    StructInfo struct(String cTypeName) {
        StructInfo s = byName.get(cTypeName);
        return s == null || s.union ? null : s;
    }

    // ---------------------------------------------------------- structs and enums

    /** Adds a struct jextract generated, with its fields from the model (by C name). */
    void addStruct(String className, GroupLayout layout, String layoutExpr, Model.Struct model) {
        StructInfo s = new StructInfo();
        s.className = className;
        s.layout = layout;
        s.layoutExpr = layoutExpr;
        s.union = layout instanceof UnionLayout;
        if (model != null) {
            s.inModel = true;
            s.modelFields = new HashMap<>();
            for (Model.Field f : model.fields()) s.modelFields.put(f.name(), f);
            StringBuilder b = new StringBuilder((s.union ? "union " : "struct ") + className + " {\n");
            for (Model.Field f : model.fields()) b.append("    ").append(f.decl()).append(";\n");
            s.snippet = b.append("}").toString();
        } else {
            s.snippet = (s.union ? "union " : "struct ") + className;
        }
        byName.put(className, s);
        all.add(s);
    }

    /** typedef struct config_s {...} config_t: jextract's class config_t extends config_s. */
    void addAlias(String alias, StructInfo target) {
        target.aliases.add(alias);
        byName.putIfAbsent(alias, target);
    }

    /** The Java class names of the structs: the typedef name if there is exactly one, else the C name. */
    void nameStructs() {
        for (StructInfo s : all) {
            if (s.parent == null) s.recordName = typeName(s.aliases.size() == 1 ? s.aliases.get(0) : s.className);
        }
    }

    void addEnum(Model.Enum e) {
        EnumInfo info = new EnumInfo();
        info.cName = e.name();
        info.javaName = typeName(e.name());
        for (Model.Constant c : e.constants()) {
            if (c.value() == null) {
                warnings.add("enum " + e.name() + ": the value of " + c.name() + " is unknown, constant skipped");
                continue;
            }
            if (c.value() != c.value().intValue()) {
                warnings.add("enum " + e.name() + " has values outside the int range, kept as a number");
                return;
            }
            info.constants.put(RESERVED.contains(c.name()) ? c.name() + "_" : c.name(), c.value().intValue());
        }
        for (String n : e.names()) enums.putIfAbsent(n, info);
    }

    // ---------------------------------------------------------- field types

    /** The Java types of s's fields (may add anonymous nested structs to {@link #all}). */
    void resolve(StructInfo s) {
        if (s.resolved || s.union) return;
        s.resolved = true;
        Set<String> javaNames = new HashSet<>();
        for (MemoryLayout m : s.layout.memberLayouts()) {
            if (m instanceof PaddingLayout) continue;
            if (m.name().isEmpty()) {
                warnings.add(where(s) + ": unnamed member skipped");
                continue;
            }
            String c = m.name().get();
            Model.Field f = s.modelFields.get(c);
            if (f == null) {
                if (s.inModel) warnings.add(where(s) + "." + c + ": not in the model, type names cannot be resolved");
                f = new Model.Field(c, new Model.Type("", 0, false, false, false), c, null);
            }
            String j = memberName(c);
            if (!javaNames.add(j)) throw new GenException(where(s) + ": two fields map to the Java name " + j);
            List<String> path = new ArrayList<>(List.of("groupElement(\"" + c + "\")"));
            JType t = resolveType(m, f, s, j, path);
            if (t != null) s.fields.add(new Field(c, j, f.decl(), t));
        }
    }

    private JType resolveType(MemoryLayout m, Model.Field f, StructInfo owner, String javaName, List<String> path) {
        Model.Type type = f.type();
        String at = where(owner) + "." + javaName;
        switch (m) {
            case SequenceLayout seq -> {
                long n = seq.elementCount();
                if (n <= 0 || n > Integer.MAX_VALUE) {
                    warnings.add(at + ": array of unsupported size " + n + " skipped");
                    return null;
                }
                if (seq.elementLayout() instanceof ValueLayout.OfByte && type.isChar() && type.ptr() == 0) return new Str(n);
                List<String> elemPath = new ArrayList<>(path);
                elemPath.add("sequenceElement()");
                JType elem = resolveType(seq.elementLayout(), f, owner, javaName, elemPath);
                return elem == null ? null : new Arr((int) n, elem);
            }
            case AddressLayout addr -> {
                if (type.ptr() == 1 && f.anon() == null && !type.fn()) {
                    StructInfo target = byName.get(type.name());
                    if (target != null && !target.union) return new Ptr(target);
                }
                return new RawPtr();
            }
            case ValueLayout v -> {
                if (v instanceof ValueLayout.OfInt && type.ptr() == 0 && enums.containsKey(type.name())) {
                    return new EnumT(enums.get(type.name()));
                }
                return new Prim(v);
            }
            case UnionLayout union -> {
                return new Bytes();
            }
            case StructLayout sl -> {
                if (f.anon() != null) {                               // struct { ... } name: a nested class
                    StructInfo child = new StructInfo();
                    child.parent = owner;
                    child.layout = sl;
                    child.snippet = f.decl();
                    child.inModel = true;
                    child.modelFields = new HashMap<>();
                    for (Model.Field x : f.anon()) child.modelFields.put(x.name(), x);
                    child.recordName = nestedName(owner, javaName);
                    child.layoutExpr = "(GroupLayout) " + owner.ref() + ".LAYOUT.select(" + String.join(", ", path) + ")";
                    owner.nested.add(child);
                    all.add(child);
                    return new ByValue(child);
                }
                StructInfo target = byName.get(type.name());
                if (target != null && !target.union) return new ByValue(target);
                warnings.add(at + ": struct type '" + type.name() + "' not found, mapped to MemorySegment");
                return new Bytes();
            }
            default -> {
                warnings.add(at + ": unsupported layout " + m + " skipped");
                return null;
            }
        }
    }

    static String where(StructInfo s) {
        return s.parent == null ? s.className : where(s.parent) + "." + s.recordName;
    }

    /** Pointers whose target can reach back to the owner would recurse forever on cyclic data: keep them raw. */
    void markCyclicPointers() {
        for (StructInfo s : all) {
            for (Field f : s.fields) {
                for (JType t : leaves(f.type())) {
                    if (t instanceof Ptr p && reaches(p.target, s, new HashSet<>())) {
                        p.raw = true;
                        warnings.add(where(s) + "." + f.javaName() + ": pointer to " + p.target.recordName
                                + " closes a cycle, kept as MemorySegment (use " + p.target.recordName + ".fromPointer)");
                    }
                }
            }
        }
    }

    private boolean reaches(StructInfo from, StructInfo goal, Set<StructInfo> seen) {
        if (from == goal) return true;
        if (!seen.add(from)) return false;
        for (StructInfo next : edges(from, true)) if (reaches(next, goal, seen)) return true;
        return false;
    }

    static List<JType> leaves(JType t) {
        return t instanceof Arr a ? leaves(a.elem()) : List.of(t);
    }

    private static List<StructInfo> edges(StructInfo s, boolean includeRawPointers) {
        List<StructInfo> r = new ArrayList<>();
        for (Field f : s.fields) {
            for (JType t : leaves(f.type())) {
                if (t instanceof ByValue b) r.add(b.target());
                if (t instanceof Ptr p && (includeRawPointers || !p.raw)) r.add(p.target);
            }
        }
        return r;
    }

    /** The top-level structs to generate: the roots, and every struct they contain or point to. */
    static Set<StructInfo> closure(List<StructInfo> roots) {
        Set<StructInfo> seen = new LinkedHashSet<>();
        Deque<StructInfo> todo = new ArrayDeque<>(roots);
        Set<StructInfo> top = new LinkedHashSet<>();
        while (!todo.isEmpty()) {
            StructInfo s = todo.pop();
            if (!seen.add(s)) continue;
            StructInfo t = s;
            while (t.parent != null) t = t.parent;
            top.add(t);
            todo.addAll(edges(s, false));
            todo.addAll(s.nested);
        }
        return top;
    }

    /** The enums the generated structs use (nested ones included). */
    static Set<EnumInfo> enumsOf(Set<StructInfo> structs) {
        Set<EnumInfo> used = new LinkedHashSet<>();
        Deque<StructInfo> todo = new ArrayDeque<>(structs);
        Set<StructInfo> seen = new HashSet<>();
        while (!todo.isEmpty()) {
            StructInfo st = todo.pop();
            if (!seen.add(st)) continue;
            todo.addAll(st.nested);
            for (Field f : st.fields) {
                JType t = f.type();
                while (t instanceof Arr a) t = a.elem();
                if (t instanceof EnumT e) used.add(e.info());
            }
        }
        return used;
    }

    // ---------------------------------------------------------- naming

    static final Set<String> RESERVED = Set.of(
            "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class", "const", "continue",
            "default", "do", "double", "else", "enum", "extends", "final", "finally", "float", "for", "goto", "if",
            "implements", "import", "instanceof", "int", "interface", "long", "native", "new", "package", "private",
            "protected", "public", "return", "short", "static", "strictfp", "super", "switch", "synchronized", "this",
            "throw", "throws", "transient", "try", "void", "volatile", "while", "true", "false", "null", "_",
            "var", "yield", "record", "sealed", "permits",
            // methods every object has
            "clone", "finalize", "getClass", "hashCode", "notify", "notifyAll", "toString", "wait",
            // generated methods
            "builder", "toBuilder", "allocateArray");

    /** snake_case field or parameter -> camelCase (MAX_SIZE -> maxSize); Java keywords get a trailing _. */
    static String memberName(String c) {
        String n = camel(c, false);
        return RESERVED.contains(n) ? n + "_" : n;
    }

    /** snake_case type -> PascalCase, without a trailing _t (shape_info_t -> ShapeInfo). */
    static String typeName(String c) {
        String n = camel(c.replaceFirst("(?<=.)_t$", ""), true);
        return RESERVED.contains(n) ? n + "_" : n;
    }

    private static String nestedName(StructInfo owner, String javaName) {
        String n = Character.toUpperCase(javaName.charAt(0)) + javaName.substring(1).replaceAll("_$", "");
        Set<String> taken = new HashSet<>(Set.of("Builder"));
        for (StructInfo p = owner; p != null; p = p.parent) taken.add(p.recordName);
        owner.nested.forEach(s -> taken.add(s.recordName));
        while (taken.contains(n)) n += "Struct";
        return n;
    }

    static String camel(String s, boolean upperFirst) {
        StringBuilder b = new StringBuilder();
        for (String part : s.split("_+")) {
            if (part.isEmpty()) continue;
            String p = part.equals(part.toUpperCase()) ? part.toLowerCase() : part; // MAX -> max
            if (b.isEmpty() && !upperFirst) {
                b.append(Character.toLowerCase(p.charAt(0))).append(p.substring(1));
            } else {
                b.append(Character.toUpperCase(p.charAt(0))).append(p.substring(1));
            }
        }
        return b.isEmpty() ? s : b.toString();
    }

    /** fieldName -> FIELD_NAME. */
    static String constName(String javaName) {
        return javaName.replaceAll("_$", "").replaceAll("([a-z0-9])([A-Z])", "$1_$2").toUpperCase();
    }
}
