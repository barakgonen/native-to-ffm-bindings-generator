package io.nativeffm.cpptoc;

import io.nativeffm.cpptoc.Ast.*;
import io.nativeffm.cpptoc.Lexer.Tok;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Writes &lt;name&gt;_model.json: everything RecordGen needs to know about the bridged headers, so that it never
 * parses C. For each bridged header its Java class, types, wrapper functions and integer constants; every struct with
 * its fields' types; every enum with its constants; every wrapper function with its parameters and result as the
 * safe wrapper header declares them (references are pointers, by-value structs const pointers) plus how the provider
 * declares them (a C++ reference, a struct by value), the size of array parameters, and the preprocessor condition
 * it is declared under ("if": jextract leaves out what the condition excludes on its platform).
 * <p>
 * A type is {"type": base name, "ptr": pointer depth, "const": const pointee, "char": the base is char (through
 * typedefs), "fn": a function pointer}. RecordGen takes struct layouts and the downcall methods from jextract.
 */
final class ModelWriter {

    /** #define NAME &lt;integer literal&gt;: the constants Java gets. */
    private static final Pattern INT_DEFINE = Pattern.compile(
            "(?m)^\\s*#\\s*define\\s+(\\w+)\\s+\\(?\\s*(-?(?:0[xX][0-9a-fA-F]+|\\d+))[uUlL]*\\s*\\)?\\s*$");

    private final Translation tr;
    private final Map<Header, String> cViews;
    /** Every integer #define of the C views, for array sizes given by name. */
    private final Map<String, Long> defines = new LinkedHashMap<>();

    ModelWriter(Translation tr, Map<Header, String> cViews) {
        this.tr = tr;
        this.cViews = cViews;
        cViews.keySet().stream().sorted(Comparator.comparing(u -> Translation.cHeaderName(u.rel)))
                .forEach(u -> intDefines(cViews.get(u)).forEach(defines::putIfAbsent));
    }

    String json(List<Fn> fns, List<StructDef> probed) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("safePrefix", tr.prefix);
        m.put("headers", headers());
        m.put("structs", tr.structs.values().stream().filter(s -> s.u.needed && s.opaque == null && !s.isUnion)
                .map(s -> (Object) map("name", s.cName, "fields", fields(s))).toList());
        m.put("enums", enums());
        m.put("probes", probed.stream().map(s -> s.cName).toList());
        m.put("functions", fns.stream().map(this::function).toList());
        return Json.write(m);
    }

    /** The bridged headers: the Java class of each, and what goes into it. */
    private List<Object> headers() {
        List<Object> r = new ArrayList<>();
        for (Header u : tr.units.values().stream().filter(x -> x.bridged).sorted(Comparator.comparing(x -> x.rel)).toList()) {
            List<String> types = new ArrayList<>();
            for (StructDef x : tr.structs.values()) if (x.u == u && x.opaque == null && !x.isUnion) types.add(x.cName);
            for (EnumDef x : tr.allEnums) if (x.u == u && x.cName != null && !x.small) types.add(x.cName);
            Map<String, Long> constants = new LinkedHashMap<>(intDefines(cViews.get(u)));
            r.add(map("file", Translation.cHeaderName(u.rel), "javaClass", u.javaClass, "types", types,
                    "functions", tr.functions.stream().filter(f -> f.u == u && f.safe).map(f -> f.safeName).toList(),
                    "constants", constants));
        }
        return r;
    }

    private static Map<String, Long> intDefines(String cView) {
        Map<String, Long> r = new LinkedHashMap<>();
        Matcher m = INT_DEFINE.matcher(cView.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("//[^\\n]*", " "));
        while (m.find()) {
            try {
                r.putIfAbsent(m.group(1), Long.decode(m.group(2)));
            } catch (NumberFormatException e) {
                // not a plain integer literal
            }
        }
        return r;
    }

    private List<Object> fields(StructDef s) {
        List<Object> r = new ArrayList<>();
        for (FieldDef f : s.fieldDefs) {
            Map<String, Object> m = type(f.spec().base(), f.ptr(), f.spec().constBase(), f.funcPtr());
            m.put("name", f.name());
            m.put("decl", f.decl());
            if (f.anon() != null) m.put("anon", fields(f.anon()));
            r.add(reorder(m, "name"));
        }
        return r;
    }

    private List<Object> enums() {
        List<Object> r = new ArrayList<>();
        for (EnumDef en : tr.allEnums) {
            if (!en.u.needed || en.cName == null || en.small) continue;
            Set<String> names = new LinkedHashSet<>();
            String name = en.cName;
            for (var td : tr.typedefs.entrySet()) {                // typedef enum color_e Color: Java calls it Color
                TypeInfo ti = target(td.getValue());
                if (ti != null && ti.ptr() == 0 && (ti.base().equals(en.cName) || ti.base().equals(en.tag))) {
                    if (name.equals(en.tag) && names.isEmpty()) name = td.getKey();
                    names.add(td.getKey());
                }
            }
            names.add(en.cName);
            if (en.tag != null) names.add(en.tag);
            List<Object> constants = new ArrayList<>();
            for (int[] c : en.constants) {
                String bare = en.u.t(c[0]).text();
                constants.add(map("name", en.cNames.get(bare), "value", en.localSyms.get(bare).value()));
            }
            Set<String> all = new LinkedHashSet<>(List.of(name));
            all.addAll(names);
            r.add(map("name", name, "names", List.copyOf(all), "constants", constants));
        }
        return r;
    }

    private Map<String, Object> function(Fn f) {
        List<Object> params = new ArrayList<>();
        for (Param p : f.params) {
            Header u = f.u;
            List<int[]> dims = new ArrayList<>();                 // T a[N][M]: [N], [M]
            for (int end = p.typeTo; u.t(end).is("]"); ) {
                int j = end, depth = 0;
                for (; j >= p.from; j--) {
                    if (u.t(j).is("]")) depth++;
                    if (u.t(j).is("[") && --depth == 0) break;
                }
                dims.addFirst(new int[]{j, end});
                end = u.prevSig(j);
            }
            int ptr = p.type.ptr() + (p.byValueStruct ? 1 : 0) - (p.constRefValue ? 1 : 0) + dims.size();
            Map<String, Object> m = type(p.type.base(), ptr, p.type.constBase() || p.byValueStruct, p.funcPtr);
            m.put("name", p.name);
            m.put("ref", p.ref);
            m.put("byValue", p.byValueStruct);
            m.put("array", arraySize(f, p, dims));
            params.add(reorder(m, "name"));
        }
        boolean hasResult = tr.safe.hasResult(f);
        return map("safe", f.safeName, "provider", f.qualified(), "decl", tr.safe.wraps(f).replace("*/", "* /").strip(),
                "if", f.conds.isEmpty() ? null : String.join(" && ", f.conds),
                "result", hasResult ? type(f.ret.base(), f.ret.ptr(), f.ret.constBase(), false) : null, "params", params);
    }

    /**
     * The element count of an array parameter, -1 if unknown, null if it is not an array: declared T a[N] (N
     * evaluated) or T a[], or listed in --array-params (a number or a #define; a reference is never an array).
     */
    private Long arraySize(Fn f, Param p, List<int[]> dims) {
        if (dims.size() > 1) return null;                         // T a[N][M]: a pointer to arrays
        if (dims.size() == 1) {
            List<Tok> expr = ConstEval.sigToks(f.u, dims.get(0)[0] + 1, dims.get(0)[1] - 1);
            Long v = expr.isEmpty() ? null : tr.evaluate(expr, Map.of());
            return v == null ? -1L : v;
        }
        String given = tr.arraySizes.getOrDefault(f.name + "." + p.name, tr.arraySizes.get(p.name));
        if (given == null || p.ref) return null;
        if (given.isEmpty()) return -1L;
        if (given.matches("\\d+")) return Long.parseLong(given);
        Long v = defines.get(given);
        if (v == null) tr.warn(f.u, f.first, f.name + "." + p.name + ": array size '" + given + "' is not a number or #define");
        return v == null ? -1L : v;
    }

    private Map<String, Object> type(String base, int ptr, boolean constPointee, boolean fn) {
        return map("type", base, "ptr", ptr, "const", constPointee, "char", isChar(base), "fn", fn);
    }

    /** char, or a typedef of it (MATLAB's char_T): a char array or const char * is a Java String. */
    private boolean isChar(String base) {
        String b = base;
        for (int i = 0; i < 16; i++) {
            TypedefDef td = tr.typedefs.get(b);
            TypeInfo ti = td == null ? null : target(td);
            if (ti == null || ti.ptr() != 0 || ti.funcPtr()) break;
            for (int k = td.typeFrom; k <= td.typeTo; k++) if (td.u.t(k).is("[")) return false;   // typedef char s[8]
            b = ti.base();
        }
        return b.equals("char");
    }

    /** What a typedef stands for, or null if C cannot take it. */
    private TypeInfo target(TypedefDef td) {
        if (td.unsupported != null) return null;
        return td.using ? tr.resolver.typeInfo(td.u, td.typeFrom, td.typeTo, -1)
                : tr.resolver.typeInfo(td.u, td.typeFrom, td.typeTo, td.nameIdx);
    }

    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    /** The same map with {@code first} first (for reading). */
    private static Map<String, Object> reorder(Map<String, Object> m, String first) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put(first, m.get(first));
        r.putAll(m);
        return r;
    }
}
