# How it works

This is the maintainers' guide: what each part of the plugin does, the contract between the two generators, and
how to test a change. How to use the plugin is in `README.md`. For an AI agent (or a new maintainer) working on
the code, `AGENTS.md` is the short entry point, and `.claude/skills/native-to-ffm-generator/SKILL.md` the working
plan. The plugin is for Linux only: there is no Windows or macOS code, and none is to be added.

Two programs turn the provider headers into a Java API, CppToC and RecordGen. Both are plain Java with no
dependencies. The plugin's goal runs them with jextract between them.

```
src/main/java/io/nativeffm/maven/GenerateMojo.java   the goal: its parameters, and the five steps
src/main/java/io/nativeffm/cpptoc/                   CppToC (and BuildSafeLib, which compiles the safe wrapper)
src/main/java/io/nativeffm/recordgen/                RecordGen
src/main/resources/io/nativeffm/                     copied into the output: cpptoc/safe_runtime.cpp, recordgen/template/
src/test/                                            golden.sh, exceptions.sh, their samples, cases and expected output
```

```
provider headers ──► CppToC ──► safe-wrapper/include/<header>.h      C views: what jextract reads
                                 safe-wrapper/include/<name>_safe.h   safe wrapper API: int safe_F(..., R *result)
                                 safe-wrapper/src/<name>_safe.cpp     safe wrapper: NULL checks, try/catch
                                 safe-wrapper/<name>_model.json       the model: everything RecordGen needs
                                       │
              jextract (<name>_safe.h) │  struct layouts, downcall methods
                                       ▼
                                  RecordGen ──► <basePackage>.api, .types, .runtime, .internal
```

The division of work: **CppToC is the only program that reads C or C++.** It writes the model, a JSON file
with the Java class of each header, every struct's field types, every enum and every function's parameters.
**RecordGen reads no C**: it takes the model, plus what only a C compiler knows (struct layouts: offsets,
sizes, alignment) from jextract's compiled output, by reflection.

## The goal (`GenerateMojo`)

`execute()` first checks that it runs on Linux, then `settings()` fills in every default and logs the result
(the four `provider headers:` / `provider library:` / `Java API:` / `safe wrapper:` lines): the only thing a
project must set is `providerDir` (or `providerIncludeDirs` and `providerLibraryDir`). The defaults, in one place:

| setting | default |
|---|---|
| include folders | `providerDir/include`, else `providerDir` |
| library folder | the first that exists: `providerDir/lib/linux-<arch>`, `providerDir/lib`, `providerDir` |
| library name | the one `lib*.so` in the library folder, other than `providerRuntimeLibraries` (several: an error) |
| headers | every header in the include folders, but `providerHeaderExcludes` |
| base package | groupId + library name, each part made a Java identifier (`defaultPackage`) |
| module | the base package; none with `generateModuleInfo` false |
| safe wrapper name / library | the library name / `<library>_safe` |
| folder variable | `<LIBRARY>_LIBRARY_DIR` (`RecordGen.defaultLibraryDirVariable`) |

A new parameter gets its default there too, and a line in README's parameter tables. Then the pipeline, in five
steps:

1. `CppToC.run(...)`: provider headers -> C views, safe wrapper source, model, in `safeWrapperDirectory`;
2. `BuildSafeLib.run(...)`: the safe wrapper -> its library, in `safeWrapperLibraryDir` (skipped when up to date);
3. with `bundleNativeLibraries`, the libraries -> `target/generated-resources/native-to-ffm/<basePackage>/native/
   <platform>/`, with `libraries.txt` (their load order), and the `additionalFiles` -> `.../native/files/`, added to
   the project's resources;
4. jextract, as a process (it is a program of its own, with its own JDK): the safe wrapper header -> `<basePackage>.ffi`.
   Without `--library` (and without `--use-system-load-library`): the generated `NativeLibraries` loads the libraries
   by their full path (from the folder in `libraryDirVariable`, the jar, or the folders they were generated in), and
   jextract's bindings find their functions with `SymbolLookup.loaderLookup()`;
5. `RecordGen.run(...)`: model + jextract output -> `<basePackage>.api`, `.types`, `.runtime` and `.internal` (and
   with `moduleName`, `module-info.java`, which exports the first three);

then it adds both Java folders to the project's sources. The generators run inside Maven's JVM, and get the
goal's parameters as the same options they take on the command line (`<providerHeaders>` becomes `--headers`), so the
command line, the tests and the plugin share one option parser. Their summary and warnings go to Maven's log.
A failed step stops the build with its message (`--headers: nope.h is not in [...]`); the generated packages are
deleted and rewritten on every build, so nothing stale is left behind.

Each generator also has a `main`, for running it alone: `java -cp target/classes io.nativeffm.cpptoc.CppToC --help`.

## Testing a change

```sh
JAVA=/path/to/jdk-25/bin/java JEXTRACT=/path/to/jextract src/test/golden.sh
JAVA=/path/to/jdk-25/bin/java src/test/exceptions.sh
JAVA=/path/to/jdk-25/bin/java JEXTRACT=/path/to/jextract src/test/samples.sh    # after mvn install
```

`golden.sh` compiles the generators, runs them (and jextract between them) on every sample and test header, and
compares everything they write with `src/test/expected/`: the C views, the safe wrapper files, the model, the Java classes and the
console output. A change that should not change the output must pass as is. For a change that should, run it
with `--update` and review the diff of `src/test/expected/` like code: it shows exactly what the change does to the
generated files. (The expected Java is from Linux x86-64.)

| case | input | what it covers |
|---|---|---|
| device-registry | `src/test/cases/device-registry` | a typical provider API: references, arrays, enums, strings, `void**`; `--module` |
| multi-header-all / -two | `src/test/samples/multi-header` | eight headers, subfolders, `--exclude` (an internal header), only the headers needed |
| matlab | `src/test/samples/matlab` | MATLAB Coder style: `rtwtypes.h` typedefs, namespaces, `double y[3]`, `coder::array` |
| cpp-features | `src/test/cases/cpp-features` | the C++ that real headers contain: classes, `enum class`, `constexpr`, overloads, macros |
| include-guards, c-enums, array-params, many-fields | `src/test/cases/*` | guards, every C enum form, array sizes, 40 fields (chunked code) |
| exceptions | `src/test/cases/exceptions` | `--exceptions` and `--exception-headers`: the mapping in `_safe.cpp` |
| missing-header, unknown-option | | the error messages |

`golden.sh` checks what the safe wrapper's code looks like; `exceptions.sh` checks what it does. It compiles
the exceptions case's provider and safe wrapper library, with and without the mapping, throws every kind of exception
through them (a `std::exception`, the provider's own classes, a string, a number, an unknown type) and compares
the status, type and message of each with `src/test/cases/exceptions/expected.txt`. It needs `c++`, `cc` and a
JDK 25 (`JAVA=...`).

`samples.sh` tests the goal itself, in real Maven: it runs it on the two sample projects with their minimal
configuration, compiles the output, checks the defaults the goal logged and the files it bundled, and the errors a
project gets with no `providerDir` or with two libraries in the provider folder. It needs the plugin installed
(`MVN`, `MVN_OPTS` for an offline repository).

The three are scripts, not part of `mvn install`: they need jextract and a C compiler. Run `golden.sh` for every
change in `src/main`, the other two when their part changed (`.claude/skills/native-to-ffm-generator/references/
testing.md` has the details).

## CppToC (`io/nativeffm/cpptoc/`)

Stages, in order (`CppToC.run`):

1. **Selection.read**: finds the bridged headers (`--headers`), then parses each one and every provider header
   it includes. Each header becomes a **Header**: its tokens (**Lexer**) and a list of *Items*, the pieces its C
   view will be rendered from. The parser has three passes over a header's tokens:
   - **Parser**: the top level, namespaces, `extern "C"`, functions and their parameters, constants.
   - **Preprocessor**: `#include`, `#define` (export macros), and `#if`. Conditions on `__cplusplus` are decided;
     the others are kept.
   - **TypeParser**: struct, class, union, enum, typedef and using. It also decides what makes a class opaque in
     C, and the field types for the model.

   What they find goes into the **Translation**, the run's shared state: macros, structs, enums, typedefs,
   constants and functions, as the classes in **Ast**.
2. **Resolver**: types through typedefs; names C needs (constants of structs, clashing `enum class` constants);
   forward declarations; each function's analysis and its wrapper name (overloads: `safe_area_2`).
3. **Selection.markNeeded**: of the included headers, which must be converted too (they define a name a
   converted header uses: `rtwtypes.h` for `real_T`).
4. Output:
   - **CView**: a header's C view. The Items are rendered in source order, so comments and layout are kept.
   - **SafeWrapper**: `_safe.h` and `_safe.cpp`. The C++ that does not depend on the headers is in
     `src/main/resources/io/nativeffm/cpptoc/safe_runtime.cpp`, a real C++ file the generated `.cpp` copies. Its `caught()` sorts an exception into
     mapped (`SAFE_CPP_EXCEPTION`: a `std::exception`, or a type from `--exceptions`, which SafeWrapper emits as
     the `SAFE_CATCH_MAPPED_EXCEPTIONS` catch clauses) and unmapped (`SAFE_UNKNOWN_EXCEPTION`: anything else,
     with its type, and its value for a string or a number).
   - **ModelWriter**: the model (**Json**).

Small pieces: **ConstEval** (integer constant expressions: array sizes, enum values, `constexpr`), **Words**
(keyword and standard-type lists), **HeaderPass** (short names for the three parser passes), **Fail** (an error
that stops the run). **BuildSafeLib** compiles the safe wrapper into a shared library linked to the provider's
(`c++ -shared -Wl,--no-undefined`, rpaths `$ORIGIN` and `$ORIGIN/<provider lib folder>`).

## The model (`<name>_model.json`)

The contract between the two programs. An excerpt (`src/test/expected/device-registry/c/device_registry_model.json`):

```json
"headers":   [{"file": "device_registry_types.h", "javaClass": "DeviceRegistryTypes",
               "types": ["Point", "Camera", "Lidar", "Radar", "Health"], "functions": [],
               "constants": {"REGISTRY_CAPACITY": 500, ...}}, ...],
"structs":   [{"name": "Lidar", "fields": [
               {"name": "origin", "type": "Point", "ptr": 1, "const": false, "char": false, "fn": false,
                "decl": "Point *origin"}, ...]}, ...],
"enums":     [{"name": "Health", "names": ["Health"], "constants": [{"name": "GREEN", "value": 0}, ...]}],
"probes":    ["Point", "Camera", "Lidar", "Radar"],
"functions": [{"safe": "safe_updateRegistry", "provider": "updateRegistry", "decl": "void updateRegistry(...)",
               "if": null, "result": null, "params": [
               {"name": "camera", "type": "Camera", "ptr": 1, "const": false, "char": false, "fn": false,
                "ref": true, "byValue": false, "array": null},
               {"name": "cameras", "type": "Camera", "ptr": 1, ..., "ref": false, "byValue": false, "array": 500}, ...]}]
```

A parameter's type is as the safe wrapper header declares it (a reference is a pointer). `ref` and `byValue` say how
the provider declares it. `array` is the element count of an array parameter (`-1` if unknown). `char` means
the base type is `char`, also through typedefs (MATLAB's `char_T`). `if` is the `#if` the function is declared
under: jextract leaves out what that condition excludes on its platform.

## RecordGen (`io/nativeffm/recordgen/`)

`RecordGen.run`, in order:

1. **Model**: reads the model (**Json**).
2. **Jextract**: compiles jextract's output in a temporary folder, with the library loading taken out, and reads
   it by reflection: struct layouts, typedef classes, the downcall methods, primitive layouts (`C_INT`, `real_T`).
3. **Types**: the Java view of each struct (layout + model fields → Java types: primitives, enums, `String`,
   `List`, nested records, `MemorySegment`) and each enum; naming (`snake_case` → `camelCase`); pointer cycles.
4. **Api**: each wrapper function as a Java method. How a parameter is passed (`ParamKind`) follows from its type in
   the model: `Camera&` → `Camera`, `Camera*` → `Camera` + an overload with `Ref<Camera>`, an array → `NativeArray`,
   `const char*` → `String`, `int*` → `Ref<Integer>`. The methods are grouped into one class per `javaClass`.
5. Output:
   - **RecordWriter**: a value class per struct: getters `getX()`, a builder, reading and writing native memory.
     Its `CHUNK` constant splits the per-field code of big structs.
   - **EnumWriter**: a Java enum per C enum.
   - **ApiWriter**: the API classes.
   - The classes the generated code uses, copied from `src/main/resources/io/nativeffm/recordgen/template/` with
     the package replaced. They are ordinary Java: review them as such.
     - `template/runtime/` -> `<basePackage>.runtime`, public: `NativeArray`, `Ref`, `NativeRecord`, `NativeEnum`,
       `SafeWrapper` (the error handler; loading and checking on demand), the error types `SafeError`,
       `SafeException` and `SafeErrorHandler`, and `AdditionalFiles`.
     - `template/internal/` -> `<basePackage>.internal`, public for the generated packages but not exported by
       `module-info.java`: `RecordSupport` (reading and writing fields, the scratch arena), `SafeCalls` (loads the
       libraries, checks the struct layouts, turns a status into a `SafeError`; RecordGen fills in one
       `checkLayout` per struct) and `NativeLibraries` (RecordGen fills in the library names, the folder
       variable, the additional files and the folders they were generated in).

   A struct or enum named like one of these classes, or like a class the generated code uses unqualified
   (`Arena`, `MemorySegment`, `String`, `List`, ...), gets a `Struct` or `Enum` suffix, with a warning.

## Reading order for a review

1. `README.md`, this file, then `src/test/expected/device-registry/`: what comes out for a typical provider.
2. `GenerateMojo.java`, then `CppToC.java` and `RecordGen.java`: the `run` methods are the outline.
3. The model's two sides: `cpptoc/ModelWriter.java` and `recordgen/Model.java`.
4. The parts you care about. The parser (`Parser`, `TypeParser`, `Preprocessor`) is the largest part, because C++
   headers are varied. `src/test/cases/cpp-features/include/nasty.hpp` shows what it handles.

## Limits

- What has no C form is skipped with a warning, and the warning gives the header line: templates (MATLAB's
  `coder::array`), operators, member functions, `std::` types, rvalue references.
- Classes with virtual functions, base classes or private data are opaque: Java gets them as pointers only.
- jextract built with libclang 18 writes Java that does not compile for a *named* anonymous struct member
  (`struct { short a, b; } pair;`). Official jextract builds handle it, and RecordGen turns it into a nested class.
