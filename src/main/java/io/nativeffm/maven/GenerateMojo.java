package io.nativeffm.maven;

import io.nativeffm.cpptoc.BuildSafeLib;
import io.nativeffm.cpptoc.CppToC;
import io.nativeffm.cpptoc.Fail;
import io.nativeffm.recordgen.GenException;
import io.nativeffm.recordgen.RecordGen;
import org.apache.maven.model.Resource;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;

import javax.lang.model.SourceVersion;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Generates a Java API for a provider's C++ shared library (Linux), and puts the library into the jar with it.
 * <p>
 * <b>What a project sets:</b> {@code providerDir}, the provider's root folder (headers in {@code include/}, the library
 * in {@code lib/}); {@code providerHeaders}, if not every header is to be bridged; {@code providerArrayParameters}, if
 * some pointer parameters point to arrays. Everything else has a default, printed at the start of the goal's output;
 * each parameter below says what its default is, and can be set to change it.
 * <p>
 * The goal runs five steps (see DESIGN.md):
 * <ol>
 *   <li>CppToC: the provider headers -&gt; C views of them, the safe wrapper's source (C++) and the model, in
 *       {@code safeWrapperDirectory};</li>
 *   <li>BuildSafeLib: the safe wrapper -&gt; its shared library, linked to the provider library, in
 *       {@code safeWrapperLibraryDir};</li>
 *   <li>with {@code bundleNativeLibraries}: the libraries and the {@code additionalFiles} -&gt; the project's
 *       resources ({@code <basePackage>/native/}), so the jar carries them and the API loads them from it;</li>
 *   <li>jextract: the safe wrapper's header -&gt; low-level FFM bindings, in {@code jextractOutputDirectory}
 *       ({@code <basePackage>.ffi});</li>
 *   <li>RecordGen: the model and the FFM bindings -&gt; the Java API, in {@code apiOutputDirectory}:
 *       {@code <basePackage>.api} (a class per header), {@code .types} (a class per struct, an enum per enum),
 *       {@code .runtime} (what they are used with) and {@code .internal} (what they share).</li>
 * </ol>
 * Both Java folders are added to the project's sources, so the project's normal compile step compiles them.
 */
@Mojo(name = "generate", defaultPhase = LifecyclePhase.GENERATE_SOURCES, threadSafe = true)
public class GenerateMojo extends AbstractMojo {

    // ==== What a project sets ====

    /**
     * The provider's root folder, as the provider ships it: the headers in {@code include/} (or in the folder itself)
     * and the library in {@code lib/linux-<arch>/}, {@code lib/} or the folder itself, the first that exists.
     * {@code providerIncludeDirs} and {@code providerLibraryDir} override the two. On the command line:
     * {@code -Dnative-to-ffm.providerDir=/opt/vendor-sdk}.
     */
    @Parameter(property = "native-to-ffm.providerDir")
    private File providerDir;

    /**
     * The provider headers to bridge to Java, each {@code header[=JavaClass]}: relative to an include folder, globs
     * allowed. A header's functions and constants go into its Java class (default: the header's name, with "Api" for
     * headers with functions); its structs and enums get a class each. Default: every header in the include folders
     * (see {@code providerHeaderExcludes}).
     */
    @Parameter
    private List<String> providerHeaders = new ArrayList<>();

    /**
     * The provider functions' pointer parameters that point to arrays, each {@code [function.]param=size} (size: a
     * number or a #define): a C++ declaration cannot say so. The safe wrapper rejects NULL for them; Java takes a
     * NativeArray and checks its size. Default: none (a pointer is a single value, or a {@code Ref}).
     */
    @Parameter
    private List<String> providerArrayParameters = new ArrayList<>();

    // ==== Optional: the provider ====

    /**
     * The provider's include folders: where the bridged headers are, and where the headers they include are looked
     * up (for MATLAB Coder output: the code folder, and extern/include for tmwtypes.h, say). Default:
     * {@code providerDir/include}, or {@code providerDir} if it has no include folder.
     */
    @Parameter
    private List<File> providerIncludeDirs = new ArrayList<>();

    /** With no {@code providerHeaders}: the headers to leave out, as globs (internal/**, *_impl.hpp). Default: none. */
    @Parameter
    private List<String> providerHeaderExcludes = new ArrayList<>();

    /**
     * The folder with the provider library. Default: the first that exists of {@code providerDir/lib/linux-<arch>}
     * (linux-x86_64 on a 64-bit Intel/AMD machine), {@code providerDir/lib}, {@code providerDir}.
     */
    @Parameter
    private File providerLibraryDir;

    /**
     * The provider library's name, without "lib" and ".so": devreg for libdevreg.so. Default: the one lib*.so file in
     * {@code providerLibraryDir} (other than the {@code providerRuntimeLibraries}); with several, the build stops and
     * names them.
     */
    @Parameter
    private String providerLibrary;

    /**
     * Other libraries of the provider that its library needs at run time, without "lib" and ".so", in load order:
     * bundled with it (see {@code bundleNativeLibraries}) and loaded before it. Default: none.
     */
    @Parameter
    private List<String> providerRuntimeLibraries = new ArrayList<>();

    /**
     * The provider's own exception types that do not derive from std::exception, each {@code Type[=member]}, a
     * derived class before its base: vendor::Error=message(), vendor::Fault=code. Java then gets their message, not
     * only their type. Every other exception is caught and reported too. Default: none.
     */
    @Parameter
    private List<String> providerExceptions = new ArrayList<>();

    /**
     * Headers that declare the {@code providerExceptions} types, relative to an include folder, if the bridged
     * headers do not include them. Default: none.
     */
    @Parameter
    private List<String> providerExceptionHeaders = new ArrayList<>();

    // ==== Optional: the Java API and the jar ====

    /**
     * The base package of the Java API. The generated classes go into five packages under it:
     * <ul>
     *   <li>{@code .api}: a class per bridged header, with its functions and constants ({@code FruitApi});</li>
     *   <li>{@code .types}: a value class per struct, an enum per enum ({@code Orange}, {@code Ripeness});</li>
     *   <li>{@code .runtime}: what the API is used with: {@code NativeArray}, {@code Ref}, {@code SafeWrapper} and the
     *       error types, {@code AdditionalFiles};</li>
     *   <li>{@code .internal}: what the generated classes share (not part of the API);</li>
     *   <li>{@code .ffi}: jextract's bindings of the safe wrapper (not part of the API).</li>
     * </ul>
     * Default: the project's groupId, then the provider library's name: com.example.devreg.
     */
    @Parameter
    private String basePackage;

    /**
     * The Java module of the API, in the module-info.java the goal writes: it exports {@code .api}, {@code .types} and
     * {@code .runtime}, so the internal and jextract packages are visible to the generated classes and hidden from
     * the code that uses the API (when that code reads the API as a module, from the module path). Default:
     * {@code basePackage}.
     */
    @Parameter
    private String moduleName;

    /** Writes module-info.java (see {@code moduleName}). false: no module (to write your own, or none). */
    @Parameter(defaultValue = "true")
    private boolean generateModuleInfo;

    /**
     * Puts the native libraries into the jar: {@code providerRuntimeLibraries}, the provider library and the safe
     * wrapper library, in {@code <basePackage>/native/linux-<arch>/} with a {@code libraries.txt} that gives their
     * load order. At run time the API copies them into a private temporary folder and loads them from there. With
     * false, the API loads them from the folders they are in now ({@code providerLibraryDir},
     * {@code safeWrapperLibraryDir}). Either way, {@code libraryDirVariable} can name another folder at run time.
     */
    @Parameter(defaultValue = "true")
    private boolean bundleNativeLibraries;

    /**
     * Additional files to put into the jar with the native libraries: configuration files, licences, data the provider
     * library reads. Each is a file or a folder (relative to the project); a folder brings everything in it, named
     * {@code folder/path-in-it}. At run time they are on disk next to the libraries, where the provider library can
     * find them, and Java gets their path with {@code AdditionalFiles.path(name)}. With {@code bundleNativeLibraries}
     * false, they stay where they are. An entry may also be a comma-separated list, so the files can come from one
     * property: {@code <additionalFiles>${my.files}</additionalFiles>}, with {@code -Dmy.files=a.cfg,data/}.
     * Default: none.
     */
    @Parameter
    private List<String> additionalFiles = new ArrayList<>();

    /**
     * The environment variable (or system property) that, when set at run time, names the folder the API loads the
     * native libraries from, instead of the jar or the folders they were generated in. Default:
     * &lt;PROVIDERLIBRARY&gt;_LIBRARY_DIR (FRUITSTORE_LIBRARY_DIR for fruitstore).
     */
    @Parameter
    private String libraryDirVariable;

    /** The Java API (a source folder of the project). */
    @Parameter(defaultValue = "${project.build.directory}/generated-sources/ffm-api")
    private File apiOutputDirectory;

    /** jextract's output (a source folder of the project). */
    @Parameter(defaultValue = "${project.build.directory}/generated-sources/jextract")
    private File jextractOutputDirectory;

    // ==== Optional: the safe wrapper (an intermediate step: a C function for every provider function, that
    // ==== catches what it throws)

    /**
     * Base name of the safe wrapper's files (&lt;name&gt;_safe.h, &lt;name&gt;_safe.cpp, &lt;name&gt;_model.json)
     * and of jextract's class (&lt;name&gt;_safe_h). Default: the provider library's name.
     */
    @Parameter
    private String safeWrapperName;

    /** Prefix of the wrapper functions: safe_updateRegistry wraps updateRegistry. */
    @Parameter(defaultValue = "safe_")
    private String safeWrapperPrefix;

    /** The safe wrapper library's name. Default: {@code providerLibrary} + "_safe" (libdevreg_safe.so). */
    @Parameter
    private String safeWrapperLibrary;

    /**
     * The safe wrapper's header, source and model, with the C views of the provider headers: to read when
     * investigating, or to keep in version control (set it to a folder of the project, and a change in the provider
     * headers shows up as a diff).
     */
    @Parameter(defaultValue = "${project.build.directory}/safe-wrapper")
    private File safeWrapperDirectory;

    /** The safe wrapper library. Default: {@code safeWrapperDirectory}/lib. */
    @Parameter
    private File safeWrapperLibraryDir;

    /**
     * The C++ compiler that builds the safe wrapper: the family the provider library was built with (GCC or Clang).
     * Default: $CXX, else c++.
     */
    @Parameter(property = "native-to-ffm.compiler")
    private String safeWrapperCompiler;

    // ==== Optional: tools

    /** jextract (https://jdk.java.net/jextract): on the PATH, or its path (/opt/jextract-25/bin/jextract). */
    @Parameter(property = "jextract.executable", defaultValue = "jextract")
    private String jextractExecutable;

    /** Skips the generation (and adds no source folders). */
    @Parameter(property = "native-to-ffm.skip", defaultValue = "false")
    private boolean skip;

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    /** The configuration with every default filled in. */
    private record Settings(List<Path> includeDirs, Path libraryDir, String library, List<String> libraries,
                            String basePackage, String moduleName, String name, String wrapperLibrary,
                            Path wrapperLibraryDir, String libraryDirVariable) {
    }

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        if (skip) {
            getLog().info("skipped (native-to-ffm.skip)");
            return;
        }
        if (!"Linux".equals(System.getProperty("os.name"))) {
            throw new MojoFailureException("native-to-ffm-bindings-generator runs on Linux only, not on "
                    + System.getProperty("os.name"));
        }
        Log log = getLog();
        Path jextractOut = Path.of(path(jextractOutputDirectory)), apiOut = Path.of(path(apiOutputDirectory));
        try {
            Settings s = settings();
            String ffmPackage = s.basePackage() + ".ffi";
            String includes = String.join(",", s.includeDirs().stream().map(Path::toString).toList());
            Map<String, Path> files = additionalFiles(s.libraries());

            // 1. provider headers -> C views, safe wrapper source, model
            CppToC.Result c = CppToC.run(args(
                    "--include-dir", includes,
                    "--headers=" + String.join(",", providerHeaders),
                    "--exclude=" + String.join(",", providerHeaderExcludes),
                    "--out", path(safeWrapperDirectory),
                    "--name", s.name(),
                    "--safe-prefix", safeWrapperPrefix,
                    "--array-params=" + String.join(",", providerArrayParameters),
                    "--exceptions=" + String.join(",", providerExceptions),
                    "--exception-headers=" + String.join(",", providerExceptionHeaders)), log::info, log::warn);

            // 2. safe wrapper -> its library, linked to the provider library (skipped when up to date)
            String compiler = safeWrapperCompiler == null || safeWrapperCompiler.isBlank() ? null : safeWrapperCompiler;
            BuildSafeLib.run(args(
                    "--source", c.safeSource().toString(),
                    "--include", includes,
                    "--provider-lib-dir", s.libraryDir().toString(),
                    "--provider-lib", s.library(),
                    "--out", s.wrapperLibraryDir().toString(),
                    "--name", s.wrapperLibrary(),
                    compiler != null ? "--cxx" : null, compiler), log::info);

            // 3. the native libraries -> the jar's resources (or not: the API loads them from their folders)
            bundle(s, files);

            // 4. safe wrapper header -> FFM bindings. No --library (and no --use-system-load-library): the API loads
            //    the libraries by their full path (NativeLibraries), and the bindings find their functions with
            //    SymbolLookup.loaderLookup()
            deletePackage(apiOut, s.basePackage());
            deletePackage(jextractOut, ffmPackage);
            runJextract(List.of(jextractExecutable, "--output", jextractOut.toString(),
                    "--target-package", ffmPackage, "-I", c.includeDir().toString(), c.safeHeader().toString()));

            // 5. model + FFM bindings -> Java API
            RecordGen.run(args(
                    "--model", c.model().toString(),
                    "--jextract", jextractOut.toString(),
                    "--jextract-package", ffmPackage,
                    "--package", s.basePackage(),
                    "--out", apiOut.toString(),
                    "--libraries=" + String.join(",", s.libraries()),
                    "--library-dir-variable=" + s.libraryDirVariable(),
                    bundleNativeLibraries ? null : "--library-dirs=" + String.join(",", libraryDirs(s)),
                    "--files=" + String.join(",", files.keySet()),
                    bundleNativeLibraries ? null : "--file-paths=" + String.join(",",
                            files.values().stream().map(Path::toString).toList()),
                    s.moduleName() != null ? "--module=" + s.moduleName() : null), log::info, log::warn);
        } catch (Fail | GenException e) {
            throw new MojoFailureException(e.getMessage(), e);
        } catch (MojoFailureException e) {
            throw e;
        } catch (Exception e) {
            throw new MojoExecutionException(e.getMessage(), e);
        }
        project.addCompileSourceRoot(jextractOut.toString());
        project.addCompileSourceRoot(apiOut.toString());
    }

    /** Fills in the defaults, checks what was set, and logs the result: what the build uses, and where from. */
    private Settings settings() throws IOException, MojoFailureException {
        Path root = providerDir == null ? null : Path.of(path(providerDir));
        if (root != null && !Files.isDirectory(root)) throw new MojoFailureException("providerDir: no folder " + root);
        if (root == null && (providerIncludeDirs.isEmpty() || providerLibraryDir == null)) {
            throw new MojoFailureException("set providerDir: the provider's root folder, with the headers in include/ and"
                    + " the library in lib/ (or set both providerIncludeDirs and providerLibraryDir)");
        }

        List<Path> includeDirs = new ArrayList<>();
        for (File d : providerIncludeDirs) includeDirs.add(Path.of(path(d)));
        if (includeDirs.isEmpty()) includeDirs.add(Files.isDirectory(root.resolve("include")) ? root.resolve("include") : root);
        for (Path d : includeDirs) if (!Files.isDirectory(d)) throw new MojoFailureException("providerIncludeDirs: no folder " + d);

        Path libraryDir;
        if (providerLibraryDir != null) {
            libraryDir = Path.of(path(providerLibraryDir));
        } else {
            libraryDir = Stream.of(root.resolve("lib").resolve(platform()), root.resolve("lib"), root)
                    .filter(Files::isDirectory).findFirst().orElseThrow();
        }
        String library = providerLibrary != null && !providerLibrary.isBlank() ? providerLibrary.strip() : findLibrary(libraryDir);

        List<String> libraries = new ArrayList<>(providerRuntimeLibraries);
        libraries.add(library);
        String wrapperLibrary = blank(safeWrapperLibrary) ? library + "_safe" : safeWrapperLibrary.strip();
        libraries.add(wrapperLibrary);

        String base = blank(basePackage) ? defaultPackage(project.getGroupId(), library) : basePackage.strip();
        String module = !generateModuleInfo ? null : blank(moduleName) ? base : moduleName.strip();
        String name = blank(safeWrapperName) ? library.replaceAll("[^A-Za-z0-9_]", "_") : safeWrapperName.strip();
        Path wrapperLibraryDir = Path.of(path(safeWrapperLibraryDir != null ? safeWrapperLibraryDir
                : new File(safeWrapperDirectory, "lib")));
        String variable = blank(libraryDirVariable) ? RecordGen.defaultLibraryDirVariable(library) : libraryDirVariable.strip();

        Log log = getLog();
        log.info("provider headers: " + includeDirs.stream().map(Path::toString).toList()
                + (providerHeaders.isEmpty() ? ", every header" + (providerHeaderExcludes.isEmpty() ? ""
                : " but " + providerHeaderExcludes) : ", " + providerHeaders));
        log.info("provider library: " + libraryDir.resolve(fileName(library))
                + (providerLibrary == null ? " (the lib*.so in " + libraryDir.getFileName() + "/)" : "")
                + (providerRuntimeLibraries.isEmpty() ? "" : ", after " + providerRuntimeLibraries));
        log.info("Java API: " + base + ".api, .types, .runtime" + (blank(basePackage) ? " (basePackage default)" : "")
                + (module != null ? ", module " + module : ", no module-info.java")
                + (bundleNativeLibraries ? "; the libraries in the jar" : "; the libraries loaded from their folders")
                + ", " + variable + " to load them from another folder");
        log.info("safe wrapper: " + path(safeWrapperDirectory) + " (" + name + "_safe.h, " + name + "_safe.cpp), "
                + wrapperLibraryDir.resolve(fileName(wrapperLibrary)));
        return new Settings(includeDirs, libraryDir, library, libraries, base, module, name, wrapperLibrary,
                wrapperLibraryDir, variable);
    }

    /** The provider library in dir: its one lib*.so file, other than the runtime libraries. */
    private String findLibrary(Path dir) throws IOException, MojoFailureException {
        List<String> found;
        try (Stream<Path> list = Files.list(dir)) {
            found = list.filter(Files::isRegularFile).map(f -> f.getFileName().toString())
                    .filter(f -> f.startsWith("lib") && f.endsWith(".so") && f.length() > 6)
                    .map(f -> f.substring(3, f.length() - 3))
                    .filter(n -> !providerRuntimeLibraries.contains(n)).sorted().toList();
        }
        if (found.size() == 1) return found.getFirst();
        if (found.isEmpty()) {
            throw new MojoFailureException("no provider library (lib*.so) in " + dir + ": the library goes into"
                    + " providerDir/lib/" + platform() + "/ or providerDir/lib/ (or set providerLibraryDir)");
        }
        throw new MojoFailureException("several libraries in " + dir + " (" + String.join(", ",
                found.stream().map(GenerateMojo::fileName).toList()) + "): set providerLibrary to the one whose"
                + " functions the headers declare, and the ones it needs in providerRuntimeLibraries");
    }

    /** com.example + devreg -> com.example.devreg, each part made a Java identifier (a-z, 0-9, _). */
    static String defaultPackage(String groupId, String library) {
        List<String> parts = new ArrayList<>();
        for (String part : (groupId + "." + library).split("\\.")) {
            String p = part.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "_");
            if (p.isEmpty()) continue;
            if (Character.isDigit(p.charAt(0))) p = "_" + p;
            if (SourceVersion.isKeyword(p)) p += "_";
            parts.add(p);
        }
        return String.join(".", parts);
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    /** devreg -> libdevreg.so */
    private static String fileName(String library) {
        return "lib" + library + ".so";
    }

    /**
     * The additionalFiles: their names (relative, with /) and where they are. A folder brings what is in it, as
     * folder/path-in-it.
     */
    private Map<String, Path> additionalFiles(List<String> libraries) throws IOException, MojoFailureException {
        Set<String> libraryFiles = new HashSet<>(libraries.stream().map(GenerateMojo::fileName).toList());
        Map<String, Path> files = new LinkedHashMap<>();
        List<String> entries = new ArrayList<>();
        for (String entry : additionalFiles) {
            if (entry != null) entries.addAll(List.of(entry.split(",")));
        }
        for (String entry : entries) {
            if (entry.isBlank()) continue;
            Path p = Path.of(entry.strip());
            p = (p.isAbsolute() ? p : project.getBasedir().toPath().resolve(p)).normalize();
            List<Path> found;
            if (Files.isRegularFile(p)) {
                found = List.of(p);
            } else if (Files.isDirectory(p)) {
                try (Stream<Path> walk = Files.walk(p)) {
                    found = walk.filter(Files::isRegularFile).sorted().toList();
                }
            } else {
                throw new MojoFailureException("additionalFiles: no " + p);
            }
            for (Path f : found) {
                String name = f.equals(p) ? p.getFileName().toString()
                        : p.getFileName() + "/" + p.relativize(f);
                if (libraryFiles.contains(name)) throw new MojoFailureException("additionalFiles: " + name + " is a library's name");
                if (files.put(name, f) != null) throw new MojoFailureException("additionalFiles: two files named " + name);
            }
        }
        return files;
    }

    /**
     * Copies the libraries into target/generated-resources/native-to-ffm/&lt;basePackage&gt;/native/linux-&lt;arch&gt;/,
     * with libraries.txt (their load order), the additional files into .../native/files/, and adds that folder to the
     * project's resources.
     */
    private void bundle(Settings s, Map<String, Path> additional) throws IOException, MojoFailureException {
        List<String> libraries = s.libraries();
        Path root = Path.of(project.getBuild().getDirectory(), "generated-resources", "native-to-ffm");
        Path natives = root.resolve(s.basePackage().replace('.', '/')).resolve("native");
        Path dir = natives.resolve(platform()), filesDir = natives.resolve("files");
        deleteTree(dir);
        deleteTree(filesDir);
        if (!bundleNativeLibraries) return;
        Files.createDirectories(dir);
        List<String> files = new ArrayList<>();
        for (int i = 0; i < libraries.size(); i++) {
            String file = fileName(libraries.get(i));                  // libdevreg.so
            boolean wrapper = i == libraries.size() - 1;               // the provider's, then the safe wrapper's
            Path from = (wrapper ? s.wrapperLibraryDir() : s.libraryDir()).resolve(file);
            if (!Files.isRegularFile(from)) throw new MojoFailureException("bundleNativeLibraries: no " + from);
            Files.copy(from, dir.resolve(file));
            files.add(file);
        }
        Files.writeString(dir.resolve("libraries.txt"), "# load order\n" + String.join("\n", files) + "\n");
        for (Map.Entry<String, Path> f : additional.entrySet()) {
            Path to = filesDir.resolve(f.getKey());
            Files.createDirectories(to.getParent());
            Files.copy(f.getValue(), to);
        }
        Resource resource = new Resource();
        resource.setDirectory(root.toString());
        project.addResource(resource);
        getLog().info("bundled into the jar: " + root.relativize(dir) + "/" + files + (additional.isEmpty() ? ""
                : ", and in " + root.relativize(filesDir) + "/: " + additional.keySet()));
    }

    /** Each library's folder now: the provider's for all but the last, the safe wrapper's for the last. */
    private static List<String> libraryDirs(Settings s) {
        List<String> dirs = new ArrayList<>();
        for (int i = 0; i < s.libraries().size() - 1; i++) dirs.add(s.libraryDir().toString());
        dirs.add(s.wrapperLibraryDir().toString());
        return dirs;
    }

    /**
     * linux-x86_64 (linux-aarch64, ...): this machine's folder, in providerDir/lib/ and in the jar. The same as
     * NativeLibraries.platform() in the generated code, which looks for it at run time.
     */
    static String platform() {
        String arch = System.getProperty("os.arch");
        return "linux-" + switch (arch) {
            case "amd64", "x86_64" -> "x86_64";
            case "aarch64", "arm64" -> "aarch64";
            default -> arch.replaceAll("[^a-z0-9_]", "");
        };
    }

    /** An absolute path without "..": Maven gives ${project.basedir}/../provider as it is. */
    private static String path(File f) {
        return f.toPath().toAbsolutePath().normalize().toString();
    }

    /** The arguments, without the nulls (an option left out). */
    private static String[] args(String... args) {
        return Stream.of(args).filter(a -> a != null).toArray(String[]::new);
    }

    /** Runs jextract; its output goes to the log. */
    private void runJextract(List<String> command) throws IOException, InterruptedException, MojoFailureException {
        getLog().info(String.join(" ", command));
        Process p;
        try {
            p = new ProcessBuilder(command).redirectErrorStream(true).start();
        } catch (IOException e) {
            throw new MojoFailureException("cannot run jextract '" + jextractExecutable + "' (" + e.getMessage() + "): put its"
                    + " bin folder on the PATH, or pass -Djextract.executable=/opt/jextract-25/bin/jextract");
        }
        try (BufferedReader out = p.inputReader()) {
            out.lines().forEach(line -> {
                if (line.startsWith("WARNING")) getLog().warn(line);
                else getLog().info(line);
            });
        }
        int exit = p.waitFor();
        if (exit != 0) throw new MojoFailureException("jextract failed (exit code " + exit + ")");
    }

    /** A generated package is written from scratch: what the headers no longer have must not stay behind. */
    private static void deletePackage(Path root, String pkg) throws IOException {
        deleteTree(root.resolve(pkg.replace('.', '/')));
    }

    private static void deleteTree(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) return;
        try (Stream<Path> files = Files.walk(dir)) {
            for (Path f : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(f);
        }
    }
}
