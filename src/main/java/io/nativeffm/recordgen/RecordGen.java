package io.nativeffm.recordgen;

import io.nativeffm.recordgen.Api.ApiFunction;
import io.nativeffm.recordgen.Api.Facade;
import io.nativeffm.recordgen.Types.EnumInfo;
import io.nativeffm.recordgen.Types.StructInfo;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Consumer;

/**
 * RecordGen: the Java side of the bindings pipeline. It reads the model CppToC wrote and jextract's output for the
 * safe wrapper header, and writes into --out, under --package &lt;base&gt;:
 * <pre>
 *   &lt;base&gt;.api        one API class per header (or per Java class several headers share): its #define
 *                     constants, and a static method per function, calling the safe wrapper (ApiWriter)
 *   &lt;base&gt;.types      one value class per struct: immutable, with a builder, read from / written to native memory
 *                     (RecordWriter); one Java enum per C enum (EnumWriter)
 *   &lt;base&gt;.runtime    what the API is used with: NativeArray, Ref, NativeRecord, NativeEnum, SafeWrapper and the
 *                     error types, AdditionalFiles (template/runtime/, copied with the package replaced)
 *   &lt;base&gt;.internal   what the generated classes share: RecordSupport, SafeCalls (filled in with the layout
 *                     checks), NativeLibraries (filled in with the libraries and files) (template/internal/)
 * </pre>
 * jextract's bindings are in --jextract-package, &lt;base&gt;.ffi for the Maven goal. With --module,
 * --out/module-info.java: a module that exports api, types and runtime, so internal and the jextract package are
 * visible to the generated classes and to nothing outside the module.
 * From jextract it takes only what C compilers know and the model does not: struct layouts (offsets, sizes,
 * array dimensions) and the downcall methods. It compiles the jextract output in a temporary directory and reads
 * it by reflection.
 * <pre>
 * usage: java io.nativeffm.recordgen.RecordGen --model &lt;name&gt;_model.json
 *            --jextract &lt;jextract --output dir&gt; --jextract-package &lt;pkg&gt; --package &lt;pkg&gt; --out &lt;dir&gt;
 *            [--libraries &lt;name&gt;,...] [--library-dir-variable &lt;NAME&gt;] [--library-dirs &lt;dir&gt;,...]
 *            [--files &lt;name&gt;,...] [--file-paths &lt;path&gt;,...] [--module &lt;module name&gt;]
 * </pre>
 * For NativeLibraries, which loads them by their full path: --libraries, the native libraries in load order (the
 * provider's, then the safe wrapper's); --library-dir-variable, the environment variable that can name their
 * folder (default: &lt;PROVIDER&gt;_LIBRARY_DIR); --library-dirs, one folder per library, where they are when they
 * are not in the jar; --files, the additional files' names (next to the libraries), and --file-paths, one per file,
 * where they are when they are not in the jar.
 */
public final class RecordGen {

    /** The classes copied as they are into &lt;base&gt;.runtime and &lt;base&gt;.internal (template/&lt;package&gt;/). */
    private static final List<String> RUNTIME = List.of("NativeRecord", "NativeEnum", "NativeArray", "Ref",
            "SafeWrapper", "SafeError", "SafeException", "SafeErrorHandler", "AdditionalFiles");
    private static final List<String> INTERNAL = List.of("RecordSupport");

    /**
     * Names a struct or enum class cannot have, because the generated API classes import their package together
     * with these (they get a Struct or Enum suffix).
     */
    private static final Set<String> RESERVED = Set.of("NativeRecord", "NativeEnum", "NativeArray", "Ref",
            "SafeWrapper", "SafeError", "SafeException", "SafeErrorHandler", "AdditionalFiles", "RecordSupport",
            "SafeCalls", "NativeLibraries", "Arena", "MemorySegment", "MemoryLayout", "SegmentAllocator", "ValueLayout",
            "AddressLayout", "GroupLayout", "StructLayout", "SequenceLayout", "Linker", "SymbolLookup",
            "FunctionDescriptor", "Objects", "Object", "String", "Integer", "Long", "Short", "Byte", "Double", "Float",
            "Boolean", "Character", "Math", "System", "List");

    private Path modelFile, jextract, out;
    private String jextractPkg, pkg, module;
    private List<String> libraries = List.of(), libraryDirs = List.of(), files = List.of(), filePaths = List.of();
    private String libraryDirVariable;

    static final String USAGE = "usage: java io.nativeffm.recordgen.RecordGen --model <file> --jextract <dir>"
            + " --jextract-package <pkg> --package <pkg> --out <dir> [--libraries <name>,...]"
            + " [--library-dir-variable <NAME>] [--library-dirs <dir>,...] [--files <name>,...] [--file-paths <path>,...]"
            + " [--module <name>]";

    public static void main(String[] args) throws Exception {
        if (Arrays.asList(args).contains("-h") || Arrays.asList(args).contains("--help")) {
            System.out.println(USAGE);
            return;
        }
        try {
            run(args, System.out::println, w -> System.err.println("warning: " + w));
        } catch (GenException e) {
            System.err.println("RecordGen: " + e.getMessage());
            System.exit(1);
        }
    }

    /**
     * Runs RecordGen with the command line's options (the Maven plugin calls this too).
     *
     * @param info the summary
     * @param warn the warnings
     * @throws GenException for a wrong option, or a model and jextract output that do not match
     */
    public static void run(String[] args, Consumer<String> info, Consumer<String> warn) throws Exception {
        RecordGen g = new RecordGen();
        g.parseArgs(args);
        g.run(info, warn);
    }

    private void parseArgs(String[] args) {
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            String v = a.contains("=") ? a.substring(a.indexOf('=') + 1) : null;   // --opt=value or --opt value
            String opt = v != null ? a.substring(0, a.indexOf('=')) : a;
            if (v == null) {
                if (++i >= args.length) throw new GenException("missing value for " + opt);
                v = args[i];
            }
            switch (opt) {
                case "--model" -> modelFile = Path.of(v);
                case "--jextract" -> jextract = Path.of(v);
                case "--jextract-package" -> jextractPkg = v;
                case "--package" -> pkg = v;
                case "--out" -> out = Path.of(v);
                case "--module" -> module = v.isBlank() ? null : v;
                case "--libraries" -> libraries = list(v);
                case "--library-dir-variable" -> libraryDirVariable = v.isBlank() ? null : v.strip();
                case "--library-dirs" -> libraryDirs = list(v);
                case "--files" -> files = list(v);
                case "--file-paths" -> filePaths = list(v);
                default -> throw new GenException("unknown option " + opt + " (try --help)");
            }
        }
        if (modelFile == null || jextract == null || jextractPkg == null || pkg == null || out == null) {
            throw new GenException("--model, --jextract, --jextract-package, --package and --out are required (try --help)");
        }
        if (Set.of(pkg, pkg + ".api", pkg + ".types", pkg + ".runtime", pkg + ".internal").contains(jextractPkg)) {
            throw new GenException("--jextract-package " + jextractPkg + " is one of the packages RecordGen writes (" + pkg
                    + ".api, .types, .runtime, .internal): use " + pkg + ".ffi");
        }
        for (String name : libraries) {
            if (!name.matches("[A-Za-z0-9_.+-]+")) throw new GenException("--libraries: " + name + " is not a library name");
        }
        if (libraryDirVariable == null && !libraries.isEmpty()) {
            libraryDirVariable = defaultLibraryDirVariable(libraries.get(Math.max(0, libraries.size() - 2)));
        }
        if (libraryDirVariable != null && !libraryDirVariable.matches("[A-Za-z_][A-Za-z0-9_.]*")) {
            throw new GenException("--library-dir-variable: " + libraryDirVariable + " is not a variable name");
        }
        if (!libraryDirs.isEmpty() && libraryDirs.size() != libraries.size()) {
            throw new GenException("--library-dirs: one folder per library in --libraries (" + libraries.size() + ")");
        }
        for (String file : files) {
            if (file.startsWith("/") || file.contains("\\") || file.contains(":") || Arrays.asList(file.split("/")).contains("..")) {
                throw new GenException("--files: " + file + " is not a relative name");
            }
        }
        if (!filePaths.isEmpty() && filePaths.size() != files.size()) {
            throw new GenException("--file-paths: one path per file in --files (" + files.size() + ")");
        }
        if (module != null && !module.matches("[A-Za-z_$][\\w$]*(\\.[A-Za-z_$][\\w$]*)*")) {
            throw new GenException("--module: " + module + " is not a Java module name");
        }
    }

    private void run(Consumer<String> info, Consumer<String> warn) throws Exception {
        Model model = Model.read(modelFile);
        List<String> warnings = new ArrayList<>();
        Types types = new Types(warnings);
        model.enums().forEach(types::addEnum);
        Jextract jx = Jextract.load(jextract, jextractPkg, types, model, warnings);
        types.nameStructs();
        for (StructInfo s : types.all) {
            if (s.parent == null && RESERVED.contains(s.recordName)) {
                warnings.add("struct " + s.recordName + ": named " + s.recordName + "Struct (" + s.recordName
                        + " is a class the generated API uses)");
                s.recordName += "Struct";
            }
        }
        for (EnumInfo e : types.enums.values()) {
            if (RESERVED.contains(e.javaName)) {
                warnings.add("enum " + e.javaName + ": named " + e.javaName + "Enum (" + e.javaName
                        + " is a class the generated API uses)");
                e.javaName += "Enum";
            }
        }
        for (int i = 0; i < types.all.size(); i++) types.resolve(types.all.get(i));   // may add nested structs
        types.markCyclicPointers();
        List<Facade> facades = new Api(types, jx, model.safePrefix(), warnings).facades(model);

        // what to generate: the bridged headers' types, the structs the functions use, and what those contain
        List<StructInfo> roots = new ArrayList<>();
        Set<EnumInfo> enums = new LinkedHashSet<>();
        for (Model.Header h : model.headers()) {
            for (String t : h.types()) {
                StructInfo st = types.struct(t);
                if (st != null) roots.add(st);
                else if (types.enums.containsKey(t)) enums.add(types.enums.get(t));
                else warnings.add(h.file() + ": type " + t + " not found in the jextract output");
            }
        }
        for (Facade fa : facades) {
            for (ApiFunction fn : fa.functions()) {
                if (fn.retStruct() != null) roots.add(fn.retStruct());
                fn.params().stream().filter(p -> p.struct() != null).forEach(p -> roots.add(p.struct()));
            }
        }
        Set<StructInfo> structs = Types.closure(roots);
        enums.addAll(Types.enumsOf(structs));
        for (Facade fa : facades) {
            for (ApiFunction fn : fa.functions()) {
                if (fn.retEnum() != null) enums.add(fn.retEnum());
                fn.params().stream().filter(p -> p.enumInfo() != null).forEach(p -> enums.add(p.enumInfo()));
            }
        }

        Path base = out.resolve(pkg.replace('.', '/'));
        Path apiDir = base.resolve("api"), typesDir = base.resolve("types"), runtimeDir = base.resolve("runtime"),
                internalDir = base.resolve("internal");
        for (Path d : List.of(apiDir, typesDir, runtimeDir, internalDir)) Files.createDirectories(d);
        Set<String> usedNames = new HashSet<>();
        RecordWriter records = new RecordWriter(pkg + ".types", pkg);
        for (StructInfo s : structs) {
            if (!usedNames.add(s.recordName)) throw new GenException("two structs map to record name " + s.recordName);
            Files.writeString(typesDir.resolve(s.recordName + ".java"), records.write(s));
        }
        EnumWriter enumWriter = new EnumWriter(pkg + ".types", pkg);
        for (EnumInfo e : enums) {
            if (!usedNames.add(e.javaName)) throw new GenException("enum name " + e.javaName + " clashes with a record");
            Files.writeString(typesDir.resolve(e.javaName + ".java"), enumWriter.write(e));
        }
        for (String name : RUNTIME) Files.writeString(runtimeDir.resolve(name + ".java"), template("runtime", name));
        for (String name : INTERNAL) Files.writeString(internalDir.resolve(name + ".java"), template("internal", name));
        ApiWriter api = new ApiWriter(pkg + ".api", pkg, !structs.isEmpty() || !enums.isEmpty());
        List<Facade> written = new ArrayList<>();
        for (Facade fa : facades) {
            if (!usedNames.add(fa.name())) {                // point.h with struct Point: PointConstants
                String other = fa.name() + (fa.functions().isEmpty() ? "Constants" : "Api");
                if (!usedNames.add(other)) throw new GenException("API class name " + fa.name() + " clashes with a class");
                warnings.add("class " + fa.name() + " (" + String.join(", ", fa.headers()) + ") clashes with a type of"
                        + " that name: named " + other);
                fa = new Facade(other, fa.functions(), fa.constants(), fa.headers());
            }
            written.add(fa);
            Files.writeString(apiDir.resolve(fa.name() + ".java"), api.write(fa));
        }
        Files.writeString(internalDir.resolve("SafeCalls.java"), safeCalls(model, types, jx, structs));
        Files.writeString(internalDir.resolve("NativeLibraries.java"), template("internal", "NativeLibraries")
                .replace("/*LIBRARIES*/", literals(libraries))
                .replace("/*VARIABLE*/\"\"", literal(libraryDirVariable == null ? "" : libraryDirVariable))
                .replace("/*GENERATION_DIRS*/", literals(libraryDirs))
                .replace("/*FILES*/", literals(files))
                .replace("/*GENERATION_FILES*/", literals(filePaths)));
        Path moduleInfo = out.resolve("module-info.java");
        if (module != null) Files.writeString(moduleInfo, moduleInfo());
        else Files.deleteIfExists(moduleInfo);                         // from an earlier run with --module

        warnings.forEach(warn);
        info.accept("RecordGen: wrote " + structs.size() + " classes, " + enums.size() + " enums"
                + written.stream().map(fa -> ", " + fa.name() + " (" + plural(fa.functions().size(), "function")
                        + (fa.constants().isEmpty() ? "" : ", " + plural(fa.constants().size(), "constant")) + ")")
                        .reduce("", String::concat)
                + ", SafeWrapper, NativeLibraries (" + (libraries.isEmpty() ? "no libraries" : String.join(", ", libraries)
                        + (libraryDirVariable != null ? "; " + libraryDirVariable : "")
                        + (libraryDirs.isEmpty() ? "" : "; from their folders, not the jar"))
                + (files.isEmpty() ? "" : ", " + plural(files.size(), "additional file"))
                + ") to " + base + "/{api,types,runtime,internal}"
                + (module != null ? "; module-info.java: module " + module + " exports " + pkg + ".api, .types and .runtime" : ""));
    }

    /** The environment variable that can name the libraries' folder: FRUITSTORE_LIBRARY_DIR for fruitstore. */
    public static String defaultLibraryDirVariable(String providerLibrary) {
        return providerLibrary.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "_") + "_LIBRARY_DIR";
    }

    private static List<String> list(String commaSeparated) {
        return Arrays.stream(commaSeparated.split(",")).map(String::strip).filter(n -> !n.isEmpty()).toList();
    }

    private static String literals(List<String> strings) {
        return String.join(", ", strings.stream().map(RecordGen::literal).toList());
    }

    /** A Java string literal. */
    private static String literal(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    /** The module: the API's packages are exported; internal and the jextract package are not. */
    private String moduleInfo() {
        return """
                /**
                 * The Java API of the provider library, generated by native-to-ffm-bindings-generator. Do not edit.
                 * <p>
                 * Exported: the API classes ({@code %2$s.api}), the types ({@code %2$s.types}) and what they are used
                 * with ({@code %2$s.runtime}). Not exported: {@code %2$s.internal}, which the generated classes share, and
                 * jextract's bindings of the safe wrapper, {@code %3$s}. Code that uses the API cannot see them, so every
                 * call goes through the API classes.
                 */
                module %1$s {
                    exports %2$s.api;
                    exports %2$s.types;
                    exports %2$s.runtime;
                }
                """.formatted(module, pkg, jextractPkg);
    }

    /** SafeCalls: the template, with the class of the safe wrapper's error functions and one layout check per struct. */
    private String safeCalls(Model model, Types types, Jextract jx, Set<StructInfo> generated) throws IOException {
        String prefix = model.safePrefix();
        Class<?> errors = jx.declaring(prefix + "last_error_type");
        StringBuilder checks = new StringBuilder();
        for (String name : model.probes()) {
            StructInfo st = types.struct(name);
            Class<?> probe = jx.declaring(prefix + "layout_" + name);
            if (st == null || !generated.contains(st) || probe == null) continue;
            checks.append("        RecordSupport.checkLayout(\"").append(name).append("\", ").append(pkg).append(".types.").append(st.ref())
                    .append(".LAYOUT, ").append(probe.getName()).append("::").append(prefix).append("layout_")
                    .append(name).append(");\n");
        }
        return template("internal", "SafeCalls")
                .replace("SafeHeader.last_error_", (errors != null ? errors.getName() : jextractPkg + ".unknown") + "." + prefix + "last_error_")
                .replace("        // checkLayout(...) for each struct\n", checks);
    }

    /** A template: template/&lt;subpackage&gt;/&lt;name&gt;.java without its first line, in the base package. */
    private String template(String subpackage, String name) throws IOException {
        String file = "template/" + subpackage + "/" + name + ".java";
        try (InputStream in = RecordGen.class.getResourceAsStream(file)) {
            if (in == null) throw new GenException(file + " is missing next to RecordGen.class");
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return text.substring(text.indexOf('\n') + 1).replace("io.nativeffm.recordgen.template", pkg);
        }
    }

    private static String plural(long n, String word) {
        return n + " " + word + (n == 1 ? "" : "s");
    }
}
