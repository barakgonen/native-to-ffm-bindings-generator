# AGENTS.md: native-to-ffm-bindings-generator

Read this first. It is the short version of everything an agent (or a new maintainer) needs before touching the code;
the working plan is the skill in `.claude/skills/native-to-ffm-generator/SKILL.md`, and it points to the details.

## What this is

A Maven plugin (goal `native-to-ffm:generate`, phase `generate-sources`) that turns a provider's **C++ headers and
Linux shared library** into a **Java 25 FFM API**, and puts the library into the jar with it:

```
provider/include/*.hpp ─► CppToC ─► C views + safe wrapper (C++) + model.json ─► BuildSafeLib ─► lib<name>_safe.so
provider/lib/lib<x>.so ─────────────────────────────────────────────────────────────────────────┘
                                   safe wrapper header ─► jextract ─► <base>.ffi
                                   model.json + <base>.ffi ─► RecordGen ─► <base>.api / .types / .runtime / .internal
```

A project sets only `providerDir` (plus `providerHeaders` and `providerArrayParameters` when it needs them);
everything else has a default (`GenerateMojo.settings()`).

## Rules that must hold

1. **Linux only.** No Windows or macOS code, scripts, branches or docs. (The C++ parser still skips `__declspec`,
   `__stdcall` and the like in provider headers: cross-platform SDK headers contain them on Linux too.)
2. **No dependencies.** The plugin jar is self-contained for air-gapped networks: only `provided` Maven API, an
   enforcer rule bans anything else. Do not add libraries (there is a small JSON reader/writer in each generator).
3. **Minimal configuration.** A new parameter is optional, gets its default in `GenerateMojo.settings()`, is logged
   there when it matters, and is documented in README's parameter tables (with its default) and in its Javadoc.
4. **Libraries load by full path only**: `NativeLibraries` (generated) uses `System.load`, from the
   `<LIB>_LIBRARY_DIR` folder, the jar, or the generation folders. Never `System.loadLibrary`, never jextract's
   `--library` or `--use-system-load-library`.
5. **CppToC is the only code that reads C/C++.** RecordGen reads the model (JSON) and jextract's compiled output
   (by reflection), nothing else. The model is the contract between them.
6. **The generated API**: `.api`, `.types`, `.runtime` are exported by the generated module; `.internal` and `.ffi`
   are not. The Java runtime classes are templates in `src/main/resources/io/nativeffm/recordgen/template/`.
7. **Output is golden-tested.** Every change to generated files must be intended: run `src/test/golden.sh`, review
   the diff, then `--update`.

## Commands

```sh
JAVA=/path/jdk-25/bin/java JEXTRACT=/path/jextract src/test/golden.sh      # generators: output vs src/test/expected
JAVA=/path/jdk-25/bin/java src/test/exceptions.sh                         # safe wrapper: every exception kind, at run time
JAVA=/path/jdk-25/bin/java JEXTRACT=/path/jextract src/test/samples.sh    # the Maven goal and its defaults (plugin installed)
mvn install                                                               # build and install the plugin
java -cp target/classes io.nativeffm.cpptoc.CppToC --help                 # each generator runs alone too (RecordGen, BuildSafeLib)
```

## Where to read more

| need | file |
|---|---|
| the plan for a change, the file map, playbooks | `.claude/skills/native-to-ffm-generator/SKILL.md` and its `references/` |
| how each stage works, the model format, review order | `DESIGN.md` |
| what users see: parameters, generated API, exceptions, loading | `README.md` |
