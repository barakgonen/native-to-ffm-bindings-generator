package io.nativeffm.recordgen;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.lang.foreign.*;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * jextract's output, read by reflection: the struct layouts (which give offsets, sizes and array dimensions), the
 * typedef classes of structs, and the header classes with the downcall methods and the primitive layouts (C_INT,
 * int32_t, real_T, ...). Nothing is parsed: the model tells the C types.
 */
final class Jextract {

    /** The classes that are neither structs nor struct typedefs: the header classes (device_registry_safe_h, ...). */
    final List<Class<?>> headerClasses = new ArrayList<>();

    private Jextract() {
    }

    /** Compiles the jextract output in --jextract and loads its package: structs go into types. */
    static Jextract load(Path src, String pkg, Types types, Model model, List<String> warnings) throws Exception {
        Path pkgDir = src.resolve(pkg.replace('.', '/'));
        if (!Files.isDirectory(pkgDir)) throw new GenException("no package directory " + pkgDir);
        Jextract jx = new Jextract();
        Path classDir = compile(src);
        try (URLClassLoader loader = new URLClassLoader(new URL[]{classDir.toUri().toURL()}, Jextract.class.getClassLoader());
             Stream<Path> files = Files.list(pkgDir)) {
            Map<String, Class<?>> others = new LinkedHashMap<>();
            for (Path f : files.filter(f -> f.toString().endsWith(".java") && !f.getFileName().toString().contains("$"))
                    .sorted().toList()) {
                String simple = f.getFileName().toString().replaceFirst("\\.java$", "");
                Class<?> c;
                try {
                    c = Class.forName(pkg + "." + simple, false, loader);
                } catch (Throwable t) {
                    warnings.add("cannot load " + simple + ": " + t);
                    continue;
                }
                Method layout = staticLayoutMethod(c);
                Object value = null;
                if (layout != null) {
                    try {
                        value = layout.invoke(null);
                    } catch (ReflectiveOperationException | LinkageError e) {
                        warnings.add("cannot read the layout of " + simple + ": " + e);
                    }
                }
                if (value instanceof GroupLayout groupLayout) {
                    String expr = (GroupLayout.class.isAssignableFrom(layout.getReturnType()) ? "" : "(GroupLayout) ")
                            + pkg + "." + simple + "." + layout.getName() + "()";
                    types.addStruct(simple, groupLayout, expr, model.struct(simple));
                } else {
                    others.put(simple, c);
                }
            }
            for (var e : others.entrySet()) {                     // typedef struct config_s {...} config_t
                Class<?> sup = e.getValue().getSuperclass();
                Types.StructInfo target = sup == null ? null : types.byName.get(sup.getSimpleName());
                if (target != null && sup.getPackageName().equals(pkg)) types.addAlias(e.getKey(), target);
                else jx.headerClasses.add(e.getValue());
            }
            // the methods and layout constants are read now, while the class loader is open
            for (Class<?> h : jx.headerClasses) {
                for (Method m : h.getDeclaredMethods()) {
                    if (Modifier.isPublic(m.getModifiers()) && Modifier.isStatic(m.getModifiers()) && !m.getName().contains("$")) {
                        jx.methods.computeIfAbsent(m.getName(), k -> new ArrayList<>()).add(m);
                    }
                }
                for (java.lang.reflect.Field f : h.getFields()) {
                    if (!Modifier.isStatic(f.getModifiers()) || !ValueLayout.class.isAssignableFrom(f.getType())) continue;
                    try {
                        if (f.get(null) instanceof ValueLayout v && !(v instanceof AddressLayout) && v.carrier().isPrimitive()) {
                            jx.layouts.put(h.getName() + "#" + f.getName(), new Cell(h.getName() + "." + f.getName(), v.carrier()));
                        }
                    } catch (ReflectiveOperationException | LinkageError e) {
                        // not readable: not a usable layout constant
                    }
                }
            }
        } finally {
            deleteTree(classDir.getParent());
        }
        return jx;
    }

    /** A primitive layout constant of a header class (C_INT, int32_t, ...) and its Java carrier. */
    record Cell(String layoutExpr, Class<?> carrier) {}

    private final Map<String, List<Method>> methods = new LinkedHashMap<>();
    private final Map<String, Cell> layouts = new LinkedHashMap<>();

    /** The downcall method {@code name} with {@code paramCount} parameters, or null. */
    Method method(String name, int paramCount) {
        return methods.getOrDefault(name, List.of()).stream().filter(m -> m.getParameterCount() == paramCount)
                .findFirst().orElse(null);
    }

    /** The header class that declares {@code name}, or null. */
    Class<?> declaring(String name) {
        List<Method> ms = methods.get(name);
        return ms == null ? null : ms.get(0).getDeclaringClass();
    }

    private static final Map<String, String> C_PRIMITIVES = Map.ofEntries(
            Map.entry("int", "C_INT"), Map.entry("signed int", "C_INT"), Map.entry("signed", "C_INT"),
            Map.entry("unsigned int", "C_INT"), Map.entry("unsigned", "C_INT"),
            Map.entry("short", "C_SHORT"), Map.entry("short int", "C_SHORT"), Map.entry("unsigned short", "C_SHORT"),
            Map.entry("long", "C_LONG"), Map.entry("long int", "C_LONG"), Map.entry("unsigned long", "C_LONG"),
            Map.entry("long long", "C_LONG_LONG"), Map.entry("unsigned long long", "C_LONG_LONG"),
            Map.entry("float", "C_FLOAT"), Map.entry("double", "C_DOUBLE"), Map.entry("bool", "C_BOOL"),
            Map.entry("_Bool", "C_BOOL"));

    /**
     * The layout constant for a primitive C type: C_INT, C_DOUBLE, ..., or the typedef constant jextract generates (int32_t, size_t, real_T). Not char: a char * is a string
     * or a buffer, not a single value. Null if there is none. header: the class whose constant is used.
     */
    Cell primitive(String cBase, Class<?> header) {
        String name = C_PRIMITIVES.getOrDefault(cBase, cBase.matches("\\w+") ? cBase : null);
        return name == null || cBase.contains("char") ? null : layouts.get(header.getName() + "#" + name);
    }

    // ---------------------------------------------------------- compiling

    private static Method staticLayoutMethod(Class<?> c) {
        try {
            Method m = c.getDeclaredMethod("layout");
            if (Modifier.isStatic(m.getModifiers()) && MemoryLayout.class.isAssignableFrom(m.getReturnType())) return m;
        } catch (NoSuchMethodException | LinkageError e) {
            // not a struct
        }
        return null;
    }

    /**
     * Compiles a private copy of the jextract output in which the native libraries are not loaded (the header
     * classes load them in their static initializer, which the struct layouts depend on): only layouts and
     * signatures are read.
     */
    private static Path compile(Path src) throws IOException {
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        if (javac == null) throw new GenException("no Java compiler available: run RecordGen with a JDK");
        Path dir = Files.createTempDirectory("recordgen");
        Path copy = dir.resolve("src"), classesOut = dir.resolve("classes");
        List<String> args = new ArrayList<>(List.of("-d", classesOut.toString(), "-proc:none", "-nowarn", "-Xlint:none",
                "-parameters"));
        Pattern load = Pattern.compile("System\\.load(Library)?\\([^;]*\\);");
        try (Stream<Path> w = Files.walk(src)) {
            for (Path f : w.filter(f -> f.toString().endsWith(".java")).toList()) {
                Path target = copy.resolve(src.relativize(f).toString());
                Files.createDirectories(target.getParent());
                String text = Files.readString(f).replace("\r\n", "\n").replace('\r', '\n');
                Files.writeString(target, load.matcher(text).replaceAll("/* not loaded by RecordGen */"));
                args.add(target.toString());
            }
        }
        if (javac.run(null, null, System.err, args.toArray(String[]::new)) != 0) {
            throw new GenException("compiling the jextract output failed (see errors above)");
        }
        return classesOut;
    }

    private static void deleteTree(Path dir) throws IOException {
        try (Stream<Path> w = Files.walk(dir)) {
            for (Path p : w.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
        }
    }
}
