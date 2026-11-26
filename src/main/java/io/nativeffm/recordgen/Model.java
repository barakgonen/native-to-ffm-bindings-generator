package io.nativeffm.recordgen;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * The model CppToC writes (&lt;name&gt;_model.json): the bridged headers and their Java classes, every struct with
 * its fields' types, every enum, and every wrapper function as the safe wrapper header declares it. RecordGen gets struct
 * layouts and the downcall methods from jextract, and everything else from here.
 */
record Model(String safePrefix, List<Header> headers, List<Struct> structs, List<Enum> enums, List<String> probes,
             List<Function> functions) {

    /** A bridged header: its Java class (functions and constants) and the types it defines (a class each). */
    record Header(String file, String javaClass, List<String> types, List<String> functions, Map<String, Long> constants) {}

    record Struct(String name, List<Field> fields) {}

    /** A data member; anon: the fields of an anonymous struct or union member, else null. decl: C, for javadoc. */
    record Field(String name, Type type, String decl, List<Field> anon) {}

    /** name: the base type (Camera, double, real_T); ptr: pointer depth; isChar: char, also through typedefs. */
    record Type(String name, int ptr, boolean isConst, boolean isChar, boolean fn) {}

    record Enum(String name, List<String> names, List<Constant> constants) {}

    /** value: null if CppToC could not compute it. */
    record Constant(String name, Long value) {}

    /** result: null for void. decl: the provider's declaration. condition: the #if it is declared under, or null. */
    record Function(String safe, String provider, String decl, String condition, Type result, List<Param> params) {}

    /**
     * type: as the safe wrapper header declares the parameter (references are pointers, structs passed by value const
     * pointers, const references to numbers values); ref / byValue: how the provider declares it. array: the
     * element count of an array parameter, -1 if unknown, null if it is not an array.
     */
    record Param(String name, Type type, boolean ref, boolean byValue, Long array) {}

    Struct struct(String name) {
        return structs.stream().filter(s -> s.name().equals(name)).findFirst().orElse(null);
    }

    // ---------------------------------------------------------- reading

    static Model read(Path file) throws IOException {
        Map<String, Object> m = obj(Json.parse(Files.readString(file)));
        return new Model((String) m.get("safePrefix"),
                list(m.get("headers")).stream().map(h -> {
                    Map<String, Object> x = obj(h);
                    return new Header((String) x.get("file"), (String) x.get("javaClass"), strings(x.get("types")),
                            strings(x.get("functions")), longs(x.get("constants")));
                }).toList(),
                list(m.get("structs")).stream().map(s -> new Struct((String) obj(s).get("name"), fields(obj(s).get("fields")))).toList(),
                list(m.get("enums")).stream().map(e -> {
                    Map<String, Object> x = obj(e);
                    return new Enum((String) x.get("name"), strings(x.get("names")), list(x.get("constants")).stream()
                            .map(c -> new Constant((String) obj(c).get("name"), (Long) obj(c).get("value"))).toList());
                }).toList(),
                strings(m.get("probes")),
                list(m.get("functions")).stream().map(f -> {
                    Map<String, Object> x = obj(f);
                    return new Function((String) x.get("safe"), (String) x.get("provider"), (String) x.get("decl"),
                            (String) x.get("if"), x.get("result") == null ? null : type(obj(x.get("result"))),
                            list(x.get("params")).stream().map(p -> {
                                Map<String, Object> y = obj(p);
                                return new Param((String) y.get("name"), type(y), (Boolean) y.get("ref"),
                                        (Boolean) y.get("byValue"), (Long) y.get("array"));
                            }).toList());
                }).toList());
    }

    private static List<Field> fields(Object v) {
        return list(v).stream().map(f -> {
            Map<String, Object> x = obj(f);
            return new Field((String) x.get("name"), type(x), (String) x.get("decl"),
                    x.containsKey("anon") ? fields(x.get("anon")) : null);
        }).toList();
    }

    private static Type type(Map<String, Object> x) {
        return new Type((String) x.get("type"), ((Long) x.get("ptr")).intValue(), (Boolean) x.get("const"),
                (Boolean) x.get("char"), (Boolean) x.get("fn"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> obj(Object v) {
        return (Map<String, Object>) v;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Object v) {
        return (List<Object>) v;
    }

    private static List<String> strings(Object v) {
        return list(v).stream().map(x -> (String) x).toList();
    }

    private static Map<String, Long> longs(Object v) {
        Map<String, Long> r = new java.util.LinkedHashMap<>();
        obj(v).forEach((k, x) -> r.put(k, (Long) x));
        return r;
    }
}
