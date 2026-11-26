package io.nativeffm.cpptoc;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Compiles the safe wrapper (CppToC's &lt;name&gt;_safe.cpp) into a shared library linked to the provider's library.
 * <pre>
 * usage: java io.nativeffm.cpptoc.BuildSafeLib --source &lt;file.cpp&gt; --include &lt;dir&gt;[,...] --provider-lib-dir &lt;dir&gt;
 *                                 --provider-lib &lt;name&gt; --out &lt;dir&gt; --name &lt;name&gt; [--cxx &lt;compiler&gt;]
 * </pre>
 * Linux only: {@code c++ -shared} (libdevreg_safe.so), with two rpaths: $ORIGIN-relative to the provider's library,
 * and $ORIGIN itself, where a bindings jar puts both libraries.
 * <p>
 * Use the compiler family and C++ runtime the provider's library was built with: only then can the safe wrapper catch
 * the provider's exceptions as std::exception. Skips the build when the library is newer than its inputs and was built with the same command
 * (kept next to it, in &lt;library&gt;.command).
 */
public final class BuildSafeLib {

    public static void main(String[] args) throws IOException, InterruptedException {
        try {
            run(args, System.out::println);
        } catch (Fail e) {
            System.err.println("BuildSafeLib: " + e.getMessage());
            System.exit(1);
        }
    }

    /**
     * Builds the library, unless it is newer than its inputs (the Maven plugin calls this too).
     *
     * @param info the compiler's command line and output
     * @throws Fail for a missing input, a compiler that cannot be run, or a failed compilation
     */
    public static void run(String[] args, Consumer<String> info) throws IOException, InterruptedException {
        Path source = null, providerLibDir = null, out = null;
        List<Path> includes = new ArrayList<>();
        String providerLib = null, name = null, cxx = null;
        for (int i = 0; i < args.length; i++) {
            String v = i + 1 < args.length ? args[i + 1] : null;
            switch (args[i]) {
                case "--source" -> source = Path.of(v);
                case "--include" -> {
                    for (String d : v.split(",")) if (!d.isBlank()) includes.add(Path.of(d.strip()));
                }
                case "--provider-lib-dir" -> providerLibDir = Path.of(v);
                case "--provider-lib" -> providerLib = v;
                case "--out" -> out = Path.of(v);
                case "--name" -> name = v;
                case "--cxx" -> cxx = v;
                default -> throw new Fail("unknown option " + args[i]);
            }
            i++;
        }
        if (source == null || providerLibDir == null || providerLib == null || out == null || name == null) {
            throw new Fail("--source, --provider-lib-dir, --provider-lib, --out and --name are required");
        }
        if (cxx == null || cxx.isBlank()) cxx = System.getenv().getOrDefault("CXX", "c++");

        Path lib = out.resolve("lib" + name + ".so");
        Path providerFile = providerLibDir.resolve("lib" + providerLib + ".so");
        if (!Files.isRegularFile(source)) throw new Fail("no safe wrapper source " + source + " (run CppToC first)");
        if (!Files.isRegularFile(providerFile)) throw new Fail("no provider library " + providerFile);
        List<Path> inputs = new ArrayList<>(List.of(source, providerFile));
        for (Path d : includes) {
            try (Stream<Path> w = Files.walk(d)) {
                w.filter(Files::isRegularFile).forEach(inputs::add);
            }
        }

        String rel = out.toAbsolutePath().normalize().relativize(providerLibDir.toAbsolutePath().normalize()).toString();
        List<String> cmd = new ArrayList<>(List.of(cxx, "-std=c++17", "-O2", "-Wall", "-fPIC"));
        for (Path d : includes) cmd.add("-I" + d.toAbsolutePath());
        // --no-undefined: a provider function the library does not export fails here, not at run time
        cmd.addAll(List.of("-shared", "-Wl,--no-undefined", "-Wl,-rpath,$ORIGIN/" + rel, "-Wl,-rpath,$ORIGIN",
                "-o", lib.toAbsolutePath().toString(), source.toAbsolutePath().toString(),
                "-L" + providerLibDir.toAbsolutePath(), "-l" + providerLib));
        Path stamp = out.resolve(lib.getFileName() + ".command");
        String command = String.join(" ", cmd);
        if (upToDate(lib, inputs) && Files.isRegularFile(stamp) && Files.readString(stamp).equals(command)) {
            info.accept("BuildSafeLib: " + lib + " is up to date");
            return;
        }
        Files.createDirectories(out);
        Files.deleteIfExists(stamp);
        info.accept("BuildSafeLib: " + command);
        Process p;
        try {
            p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        } catch (IOException e) {
            throw new Fail("cannot run the C++ compiler '" + cxx + "' (" + e.getMessage() + "): install one, or choose another"
                    + " (--cxx; in Maven -Dnative-to-ffm.compiler=...)");
        }
        try (BufferedReader output = p.inputReader()) {
            output.lines().forEach(info);
        }
        int exit = p.waitFor();
        if (exit != 0) throw new Fail("the C++ compiler failed (exit code " + exit + ")");
        Files.writeString(stamp, command);
        info.accept("BuildSafeLib: built " + lib);
    }

    private static boolean upToDate(Path lib, List<Path> inputs) throws IOException {
        if (!Files.isRegularFile(lib)) return false;
        FileTime built = Files.getLastModifiedTime(lib);
        for (Path in : inputs) if (Files.getLastModifiedTime(in).compareTo(built) > 0) return false;
        return true;
    }
}
