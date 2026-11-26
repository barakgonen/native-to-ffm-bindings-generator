package io.nativeffm.cpptoc;

import io.nativeffm.cpptoc.Ast.Fn;
import io.nativeffm.cpptoc.Ast.StructDef;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * CppToC: the C++ to C step of the bindings pipeline. jextract reads C, and FFM calls only C-linkage functions;
 * provider headers are C++ (references, namespaces, enum class, constexpr, export macros, C++ linkage). CppToC
 * reads them and writes, into --out:
 * <pre>
 *   include/&lt;header&gt;.h      C view of each header that is needed: the same structs, enums, constants and
 *                              macros in C (references become pointers: the same ABI)
 *   include/&lt;name&gt;_safe.h    the safe wrapper API: int safe_F(&lt;F's parameters&gt;, R *result) for every function F,
 *                              returning a status code instead of letting a C++ exception through
 *   src/&lt;name&gt;_safe.cpp      the safe wrapper in C++ (NULL checks, try/catch), compiled by BuildSafeLib
 *   &lt;name&gt;_model.json        what RecordGen needs: Java classes per header, structs, enums, functions, constants
 * </pre>
 * Stages, in order (each one a class):
 * <ol>
 *   <li>Selection: the bridged headers (--headers) and the headers they include, each parsed by
 *       Parser / Preprocessor / TypeParser into a Header (tokens + Items) and the Translation's definitions;</li>
 *   <li>Resolver: types through typedefs, names C needs, each function's analysis and wrapper name;</li>
 *   <li>Selection.markNeeded: which included headers must be converted too (they define something used);</li>
 *   <li>CView, SafeWrapper, ModelWriter: the output files.</li>
 * </ol>
 * <pre>
 * usage: java io.nativeffm.cpptoc.CppToC --include-dir &lt;dir&gt;[,...] [--headers &lt;header&gt;[=JavaClass],...]
 *            --out &lt;dir&gt; [--name &lt;base&gt;] [--safe-prefix safe_] [--exclude &lt;glob&gt;,...]
 *            [--array-params [fn.]param[=size],...] [--exceptions Type[=member],...] [--exception-headers h,...]
 * </pre>
 * --headers: relative to an include dir (pricing/pricing.hpp), globs allowed (sensor*.h); without it every header is
 * bridged except --exclude. "=JavaClass" names the Java class of the header's functions and constants (several
 * headers may share one); the default is the header's name (sensor_types.h -> SensorTypes, sensor.h -> SensorApi).
 * --array-params: pointer parameters that point to arrays: NULL is rejected, and Java gets a NativeArray (size: a
 * number or a #define, checked in Java). --exceptions: the provider's own exception types that do not derive from
 * std::exception (vendor::Error=message()): the safe wrapper reports their message (member: a function or a field
 * whose value is a string or a number), not only their type. --exception-headers: the headers that declare them,
 * if the bridged headers do not include them. Options also take the form --name=value.
 */
public final class CppToC {

    static final String USAGE = "usage: java io.nativeffm.cpptoc.CppToC --include-dir <dir>[,...]"
            + " [--headers <header>[=JavaClass],...] --out <dir> [--name <base>] [--safe-prefix safe_]"
            + " [--exclude <glob>,...] [--array-params [fn.]param[=size],...]"
            + " [--exceptions Type[=member],...] [--exception-headers <header>,...]";

    /** What a run wrote: the safe wrapper files' base name, and the files the next steps of the pipeline read. */
    public record Result(String name, Path includeDir, Path safeHeader, Path safeSource, Path model) {
    }

    public static void main(String[] args) throws IOException {
        if (Arrays.asList(args).contains("-h") || Arrays.asList(args).contains("--help")) {
            System.out.println(USAGE);
            return;
        }
        try {
            run(args, System.out::println, w -> System.err.println("warning: " + w));
        } catch (Fail e) {
            System.err.println("CppToC: " + e.getMessage());
            System.exit(1);
        }
    }

    /**
     * Runs CppToC with the command line's options (the Maven plugin calls this too).
     *
     * @param info the summary, a line at a time
     * @param warn the warnings
     * @throws Fail for a wrong option or a header that cannot be translated
     */
    public static Result run(String[] args, Consumer<String> info, Consumer<String> warn) throws IOException {
        Translation tr = new Translation();
        parseArgs(tr, args);
        return run(tr, info, warn);
    }

    private static void parseArgs(Translation tr, String[] given) {
        List<String> args = new ArrayList<>();                 // --opt=value -> --opt value (an empty value is fine)
        for (String a : given) {
            int eq = a.indexOf('=');
            if (a.startsWith("--") && eq > 2) {
                args.add(a.substring(0, eq));
                args.add(a.substring(eq + 1));
            } else {
                args.add(a);
            }
        }
        for (int i = 0; i < args.size(); i++) {
            String a = args.get(i);
            switch (a) {
                case "--include-dir" -> {
                    for (String d : list(value(args, ++i, a))) tr.includeDirs.add(Path.of(d));
                }
                case "--headers" -> {
                    for (String h : list(value(args, ++i, a))) {
                        String[] kv = h.split("=", 2);
                        if (kv.length == 2 && !kv[1].strip().matches("[A-Za-z_$][\\w$]*")) {
                            throw new Fail("--headers: '" + kv[1] + "' is not a Java class name (in " + h + ")");
                        }
                        tr.headerSpecs.add(new String[]{kv[0].strip(), kv.length == 2 ? kv[1].strip() : null});
                    }
                }
                case "--exclude" -> tr.excludes.addAll(list(value(args, ++i, a)));
                case "--out" -> tr.out = Path.of(value(args, ++i, a));
                case "--name" -> tr.baseName = value(args, ++i, a);
                case "--safe-prefix" -> tr.prefix = value(args, ++i, a);
                case "--array-params" -> {
                    for (String s : list(value(args, ++i, a))) {
                        String[] kv = s.split("=", 2);
                        tr.arrayParams.add(kv[0].strip());
                        tr.arraySizes.put(kv[0].strip(), kv.length == 2 ? kv[1].strip() : "");
                    }
                }
                case "--exceptions" -> {
                    for (String s : list(value(args, ++i, a))) {
                        String[] kv = s.split("=", 2);
                        if (!kv[0].strip().matches("(::)?[A-Za-z_]\\w*(::[A-Za-z_]\\w*)*")) {
                            throw new Fail("--exceptions: '" + kv[0] + "' is not a C++ type name");
                        }
                        if (kv.length == 2 && !kv[1].strip().matches("[A-Za-z_]\\w*(\\(\\))?")) {
                            throw new Fail("--exceptions: '" + kv[1] + "' is not a member (message() or text) in " + s);
                        }
                        tr.exceptionTypes.add(new String[]{kv[0].strip(), kv.length == 2 ? kv[1].strip() : null});
                    }
                }
                case "--exception-headers" -> tr.exceptionHeaders.addAll(list(value(args, ++i, a)));
                default -> throw new Fail("unknown option " + a + " (try --help)");
            }
        }
        if (tr.includeDirs.isEmpty() || tr.out == null) throw new Fail("--include-dir and --out are required (try --help)");
        if (!tr.prefix.matches("[A-Za-z_]\\w*")) throw new Fail("--safe-prefix must be a C identifier: " + tr.prefix);
    }

    private static List<String> list(String s) {
        return Stream.of(s.split(",")).map(String::strip).filter(x -> !x.isEmpty()).toList();
    }

    private static String value(List<String> args, int i, String opt) {
        if (i >= args.size()) throw new Fail("missing value for " + opt);
        return args.get(i);
    }

    private static Result run(Translation tr, Consumer<String> info, Consumer<String> warn) throws IOException {
        tr.selection.read();
        List<Header> bridged = tr.units.values().stream().filter(u -> u.bridged).toList();
        if (tr.baseName == null) tr.baseName = Translation.stem(bridged.get(0).rel.replaceFirst(".*/", ""));
        tr.resolver.resolve();
        tr.selection.markNeeded();
        for (String[] x : tr.exceptionTypes) {               // a typo would only show as a C++ compiler error
            String type = x[0].replaceFirst("^::", "");
            boolean declared = tr.structs.values().stream().anyMatch(s -> type.equals(s.cppName.replaceFirst("^::", "")));
            if (!declared && tr.exceptionHeaders.isEmpty()) {
                tr.warnings.add(new Object[]{null, "--exceptions: " + type + " is not declared in the headers read;"
                        + " name the header that declares it in --exception-headers"});
            }
        }
        for (Header u : bridged) {
            boolean hasFunctions = tr.functions.stream().anyMatch(f -> f.u == u && f.safe);
            u.javaClass = tr.selection.explicitClass.getOrDefault(u.file, Selection.defaultJavaClass(u, hasFunctions));
        }

        Path includeDir = tr.out.resolve("include");
        int written = 0;
        Set<String> outNames = new HashSet<>();
        Map<Header, String> cViews = new LinkedHashMap<>();
        for (Header u : tr.units.values()) {
            if (!u.needed) continue;
            String name = Translation.cHeaderName(u.rel);
            if (!outNames.add(name)) throw new Fail("two headers map to " + name);
            cViews.put(u, tr.cview.header(u));
            written += write(includeDir.resolve(name), cViews.get(u)) ? 1 : 0;
        }
        List<Fn> safeFns = tr.functions.stream().filter(f -> f.safe).toList();
        List<StructDef> probed = tr.structs.values().stream().filter(x -> x.u.needed && tr.resolver.probed(x)).toList();
        Result result = new Result(tr.baseName, includeDir, includeDir.resolve(tr.baseName + "_safe.h"),
                tr.out.resolve("src").resolve(tr.baseName + "_safe.cpp"), tr.out.resolve(tr.baseName + "_model.json"));
        written += write(result.safeHeader(), tr.safe.header(safeFns, probed)) ? 1 : 0;
        written += write(result.safeSource(), tr.safe.source(safeFns, probed)) ? 1 : 0;
        written += write(result.model(), new ModelWriter(tr, cViews).json(safeFns, probed)) ? 1 : 0;

        for (Object[] w : tr.warnings) {
            if (w[0] == null || ((Header) w[0]).needed) warn.accept(w[1].toString());
        }
        summary(tr, bridged, safeFns, probed, includeDir, written, info);
        return result;
    }

    private static void summary(Translation tr, List<Header> bridged, List<Fn> safeFns, List<StructDef> probed,
                                Path includeDir, int written, Consumer<String> info) throws IOException {
        long notNeeded = tr.units.values().stream().filter(u -> !u.needed).count();
        long converted = tr.units.values().stream().filter(u -> u.needed && !u.bridged).count();
        int others = 0;
        for (Path dir : tr.includeDirs) for (Path f : Selection.headersIn(dir)) if (!tr.units.containsKey(f)) others++;
        info.accept("CppToC: " + Selection.plural(bridged.size(), "header") + " bridged to Java, " + converted
                + " more converted to C (used by them), " + notNeeded + " included but not needed -> " + includeDir);
        int width = tr.units.values().stream().mapToInt(u -> u.rel.length()).max().orElse(0);
        for (Header u : tr.units.values().stream().sorted(Comparator.comparing((Header x) -> !x.bridged)
                .thenComparing(x -> !x.needed).thenComparing(x -> x.rel)).toList()) {
            String status = u.bridged ? "bridged  " : u.needed ? "converted" : "skipped  ";
            info.accept(String.format("  %s %-" + width + "s  %s", status, u.rel, tr.selection.contents(u)));
        }
        for (Path x : tr.excluded) info.accept("  excluded  " + x.getFileName());
        if (others > 0 && !tr.headerSpecs.isEmpty()) {
            info.accept("  (" + Selection.plural(others, "other header") + " in " + tr.includeDirs.stream()
                    .map(d -> d.getFileName().toString()).toList() + " not used)");
        }
        long cFns = tr.functions.stream().filter(f -> f.cView && f.u.needed).count();
        info.accept("  safe wrapper: " + Selection.plural(safeFns.size(), "function") + ", "
                + Selection.plural(probed.size(), "layout probe") + ", includes "
                + tr.selection.roots().stream().map(r -> r.rel).toList() + "; " + cFns
                + " with C linkage also declared in the C views (" + written + " file(s) changed)");
    }

    /** Writes the file only if its content changed (keeps timestamps for up-to-date checks). */
    private static boolean write(Path file, String content) throws IOException {
        if (Files.isRegularFile(file) && Files.readString(file).equals(content)) return false;
        Files.createDirectories(file.toAbsolutePath().getParent());
        Files.writeString(file, content);
        return true;
    }
}
