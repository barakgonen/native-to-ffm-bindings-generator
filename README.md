# native-to-ffm-bindings-generator

A Maven plugin that gives Java a clean API for a **provider's C++ library** on Linux, from the provider's headers
and its shared library, as they are shipped. Between Java and the provider it generates a **safe wrapper**: a C function
for every provider function, which no C++ exception gets past.

```
provider headers ─┐   native-to-ffm:generate (generate-sources)
provider library ─┴──►  safe wrapper (C++: generated, compiled, linked to the provider library)
                        jextract bindings of the safe wrapper (low-level FFM)
                        Java API: a value class per struct, an enum per enum, an API class per header
```

No C or JNI code is written by hand. The application calls `RegistryApi.updateRegistry(camera, ...)` with plain
Java objects, and gets a Java exception (or the error handler's decision) when the C++ code throws. The jar the
project builds carries the native libraries too: the application needs it and nothing else.

* [Requirements](#requirements)
* [Quick start](#quick-start)
* [Parameters](#parameters)
* [Why a safe wrapper](#why-a-safe-wrapper)
* [Choosing the provider headers](#choosing-the-provider-headers)
* [The generated packages](#the-generated-packages)
* [What Java gets](#what-java-gets)
* [What the safe wrapper does](#what-the-safe-wrapper-does)
* [Handling C++ exceptions](#handling-c-exceptions)
* [Native libraries in the jar](#native-libraries-in-the-jar)
* [Hiding the jextract bindings](#hiding-the-jextract-bindings)
* [Layout check](#layout-check)
* [Performance](#performance)
* [Compiler](#compiler)
* [Air-gapped environments](#air-gapped-environments)
* [Building and testing the plugin](#building-and-testing-the-plugin)

The examples use the provider in `src/test/cases/device-registry`: one function,

```cpp
#include "device_registry_types.hpp"      // struct Point, Camera, Lidar, Radar; enum Health; #define REGISTRY_CAPACITY 500

void updateRegistry(int reqType, Camera& camera, Lidar& lidar, Radar& radar,
                    double timeTag, double pendingEvents, void* ptr, void** outPtr,
                    Camera* cameras, Lidar* lidars, Radar* radars,
                    int* sizeOfInternalBuffer, double* cameraCount, double* lidarsCount);
```

## Requirements

Linux (x86-64 or aarch64), and:

* **JDK 25**, also for Maven: the plugin runs in Maven's JVM.
* **Maven 3.9** or later.
* **jextract** for that JDK, from https://jdk.java.net/jextract (it is not on Maven Central): its `bin` folder on
  the `PATH`, or `-Djextract.executable=/opt/jextract-25/bin/jextract`.
* **A C++17 compiler of the family the provider library was built with**: `c++`, `g++` or `clang++`. See
  [Compiler](#compiler).

## Quick start

Install the plugin once (`mvn install` here, or `mvn deploy` to your company's repository; offline: see
[Air-gapped environments](#air-gapped-environments)). In the project that should get the Java API, point it at the
provider's folder, as the provider ships it:

```
sdk/
├── include/                       the provider's headers (subfolders too)
└── lib/libdevreg.so               its library (or lib/linux-x86_64/libdevreg.so)
```

```xml
<plugin>
    <groupId>io.nativeffm</groupId>
    <artifactId>native-to-ffm-bindings-generator</artifactId>
    <version>1.0.0-SNAPSHOT</version>
    <executions>
        <execution>
            <goals>
                <goal>generate</goal>       <!-- in generate-sources -->
            </goals>
        </execution>
    </executions>
    <configuration>
        <providerDir>${project.basedir}/sdk</providerDir>
        <!-- only if some pointer parameters point to arrays (a C++ declaration cannot say so) -->
        <providerArrayParameters>
            <providerArrayParameter>cameras=REGISTRY_CAPACITY</providerArrayParameter>
            <providerArrayParameter>lidars=REGISTRY_CAPACITY</providerArrayParameter>
            <providerArrayParameter>radars=REGISTRY_CAPACITY</providerArrayParameter>
        </providerArrayParameters>
    </configuration>
</plugin>
```

That is all a project has to set. Everything else has a default: every header in `include/` is bridged, the
library is the one `lib*.so` in `lib/`, the package is the project's groupId plus the library's name
(`com.example.devreg`), and so on: [Parameters](#parameters) lists them all. The build log starts with what the
defaults became:

```
[INFO] provider headers: [/work/devices/sdk/include], every header
[INFO] provider library: /work/devices/sdk/lib/libdevreg.so (the lib*.so in lib/)
[INFO] Java API: com.example.devreg.api, .types, .runtime (basePackage default), module com.example.devreg; the libraries in the jar, DEVREG_LIBRARY_DIR to load them from another folder
[INFO] safe wrapper: /work/devices/target/safe-wrapper (devreg_safe.h, devreg_safe.cpp), /work/devices/target/safe-wrapper/lib/libdevreg_safe.so
```

`mvn package` then builds a jar that is all an application needs: the Java API and the two native libraries (the
provider's and the safe wrapper's). The application depends on it like on any jar, and runs with native access
enabled. No `java.library.path`:

```sh
java --enable-native-access=com.example.devreg -p app.jar:devices.jar -m app/app.Main
java --enable-native-access=ALL-UNNAMED -cp app.jar:devices.jar app.Main
```

**The examples in this README** choose a few names, which is what most projects do: the Java class of the API
header, the package, and a configuration file to bundle:

```xml
<configuration>
    <providerDir>${project.basedir}/sdk</providerDir>
    <providerHeaders>
        <providerHeader>device_registry_types.hpp</providerHeader>
        <providerHeader>device_registry.hpp=RegistryApi</providerHeader>   <!-- default: DeviceRegistryApi -->
    </providerHeaders>
    <providerArrayParameters>...</providerArrayParameters>
    <basePackage>com.example.devices</basePackage>                       <!-- default: com.example.devreg -->
    <additionalFiles>
        <additionalFile>config/registry.cfg</additionalFile>
    </additionalFiles>
</configuration>
```

What the build writes, all under `target/`:

| folder | what |
|---|---|
| `generated-sources/ffm-api/` | the Java API: `com.example.devices.api`, `.types`, `.runtime`, `.internal`, and `module-info.java` |
| `generated-sources/jextract/` | jextract's bindings of the safe wrapper (`com.example.devices.ffi`) |
| `generated-resources/native-to-ffm/` | `com/example/devices/native/linux-x86_64/`: `libdevreg.so`, `libdevreg_safe.so`, `libraries.txt`; `com/example/devices/native/files/registry.cfg` |
| `safe-wrapper/` | the intermediate step, to read when investigating: C views of the headers (`include/`), the safe wrapper (`include/devreg_safe.h`, `src/devreg_safe.cpp`), its library (`lib/libdevreg_safe.so`), the model (`devreg_model.json`) |

Two runnable examples, each a project of its own: `src/test/samples/matlab` (MATLAB Coder style code) and
`src/test/samples/multi-header` (eight headers, every one bridged).

## Parameters

Each parameter is a `<configuration>` element. A list is written element by element, or comma-separated:
`<providerHeaders>device_registry_types.hpp,device_registry.hpp=RegistryApi</providerHeaders>`. The same
descriptions, from the plugin itself: `mvn help:describe -Dplugin=io.nativeffm:native-to-ffm-bindings-generator
-Ddetail` (needs maven-help-plugin).

**What a project sets**

| parameter | default | |
|---|---|---|
| `providerDir` | none: set it (or `providerIncludeDirs` and `providerLibraryDir`) | the provider's root folder: headers in `include/`, the library in `lib/`. `-Dnative-to-ffm.providerDir=/opt/sdk` |
| `providerHeaders` | every header in the include folders | the headers to bridge to Java, `header[=JavaClass]`; see [below](#choosing-the-provider-headers) |
| `providerArrayParameters` | none | pointer parameters that point to arrays, `[function.]param=size` (a number or a `#define`) |

**Optional: the provider**

| parameter | default | |
|---|---|---|
| `providerIncludeDirs` | `providerDir/include`, else `providerDir` | the include folders: the headers, and where the headers they include are looked up |
| `providerHeaderExcludes` | none | with no `providerHeaders`: the headers to leave out, as globs (`internal/**`) |
| `providerLibraryDir` | the first that exists: `providerDir/lib/linux-<arch>`, `providerDir/lib`, `providerDir` | the folder with the provider library |
| `providerLibrary` | the one `lib*.so` in `providerLibraryDir` | its name, without `lib` and `.so`: `devreg` for `libdevreg.so`. With several libraries there, the build stops and names them |
| `providerRuntimeLibraries` | none | other libraries of the provider that its library needs, in load order: bundled and loaded before it |
| `providerExceptions` | none | the provider's own exception types, `Type[=member]`; see [Handling C++ exceptions](#4-map-the-providers-own-exception-types) |
| `providerExceptionHeaders` | none | headers that declare them, if the bridged headers do not include them |

**Optional: the Java API and the jar**

| parameter | default | |
|---|---|---|
| `basePackage` | groupId + library name: `com.example.devreg` | the Java API's base package: `.api`, `.types`, `.runtime`, `.internal`, `.ffi` under it; see [The generated packages](#the-generated-packages) |
| `moduleName` | `basePackage` | the module in the generated `module-info.java`, which exports `.api`, `.types` and `.runtime`; see [Hiding the jextract bindings](#hiding-the-jextract-bindings) |
| `generateModuleInfo` | `true` | `false`: no `module-info.java` (to write your own, or to have none) |
| `additionalFiles` | none | files or folders to put into the jar with the libraries (configuration files, licences, data); an entry may be a comma-separated list; see [Additional files](#additional-files) |
| `bundleNativeLibraries` | `true` | puts the libraries into the jar; `false`: the API loads them from the folders they are in now. See [Native libraries in the jar](#native-libraries-in-the-jar) |
| `libraryDirVariable` | `<PROVIDERLIBRARY>_LIBRARY_DIR`: `DEVREG_LIBRARY_DIR` | the environment variable (or system property) that names another folder to load the libraries from, at run time |
| `apiOutputDirectory` | `target/generated-sources/ffm-api` | the Java API: a source folder of the project |
| `jextractOutputDirectory` | `target/generated-sources/jextract` | jextract's output: a source folder of the project |

**Optional: the safe wrapper** (an intermediate step; see [Why a safe wrapper](#why-a-safe-wrapper))

| parameter | default | |
|---|---|---|
| `safeWrapperName` | the library's name: `devreg` | base name of its files, `devreg_safe.h`, `devreg_safe.cpp`, `devreg_model.json`, and of jextract's class `devreg_safe_h` |
| `safeWrapperPrefix` | `safe_` | prefix of the wrapper functions: `safe_updateRegistry` |
| `safeWrapperLibrary` | `<providerLibrary>_safe`: `devreg_safe` | the safe wrapper library's name |
| `safeWrapperDirectory` | `target/safe-wrapper` | its header, source and model, and the C views. Set it to a folder of the project to keep them in version control: a change in the provider's headers then shows up as a diff |
| `safeWrapperLibraryDir` | `<safeWrapperDirectory>/lib` | the safe wrapper library |
| `safeWrapperCompiler` | `$CXX`, else `c++` | the C++ compiler; `-Dnative-to-ffm.compiler=clang++` |

**Optional: tools**

| parameter | default | |
|---|---|---|
| `jextractExecutable` | `jextract` (on the PATH) | `-Djextract.executable=/opt/jextract-25/bin/jextract` |
| `skip` | `false` | `-Dnative-to-ffm.skip` |

The Java packages are deleted and rewritten on every build. The safe wrapper library is rebuilt only when its
source is newer, or its compile command changed.

## Why a safe wrapper

```
provider headers                                       the provider's C++ headers, untouched
   │
   │ 1. CppToC (providerHeaders, and the headers they need)
   ├──► safe-wrapper/include/device_registry_types.h   C view of the types: structs, enum, #defines
   ├──► safe-wrapper/include/device_registry.h         C view of the API header (updateRegistry is C++: not in it)
   ├──► safe-wrapper/include/devreg_safe.h             the safe wrapper's API: int safe_updateRegistry(...)
   ├──► safe-wrapper/src/devreg_safe.cpp               the safe wrapper: calls updateRegistry inside try/catch
   └──► safe-wrapper/devreg_model.json                 the model: what each header defines, for RecordGen
            │
            │ 2. BuildSafeLib (c++ -shared ... -ldevreg; skipped when up to date)
            ▼
        safe-wrapper/lib/libdevreg_safe.so   ──links to──►   the provider library, libdevreg.so
   │
   │ 3. the two libraries                     ->  the jar's resources: com/example/devices/native/linux-x86_64/
   │ 4. jextract  devreg_safe.h               ->  com.example.devices.ffi (low-level FFM bindings)
   │ 5. RecordGen model + jextract output     ->  com.example.devices.api (RegistryApi, DeviceRegistryTypes),
   │                                              .types (Camera, Lidar, ...), .runtime (NativeArray, SafeWrapper, ...)
   ▼
RegistryApi.updateRegistry(...)  ──►  safe_updateRegistry (C linkage)  ──►  updateRegistry (C++ linkage, provider)
```

* **FFM calls functions with C linkage only.** A function outside an `extern "C"` block is exported only under its
  mangled C++ name (`_Z14updateRegistryiR6CameraR5Lidar...`, which differs between compilers), so there is no
  symbol for Java to find. The safe wrapper is C++: it calls the provider function by name, and exports
  `safe_updateRegistry` with C linkage.
* **jextract reads C only.** CppToC writes a C view of each header with the same layout: references become
  pointers (a reference *is* passed as a pointer); namespaces, methods, access specifiers and default member
  initializers are dropped (none of them change the layout); `enum class` becomes a C enum; `constexpr` constants
  become `#define`s.
* **A C++ exception must never reach the JVM.** If one unwinds into Java frames, the process ends (exit code 134,
  `terminate called after throwing an instance of 'std::out_of_range'`). The safe wrapper catches everything.

## Choosing the provider headers

CppToC gets the include folders (`providerIncludeDirs`) and the headers to bridge to Java (`providerHeaders`).
Paths are relative to an include folder (`pricing/pricing.hpp`); globs work (`sensor*.h`).

**Minimal conversion to C.** Only the bridged headers are converted, plus the headers they include that define
something they use: a type, typedef, constant, enum or macro. For MATLAB's `sensor.h`, that is `rtwtypes.h` (for
`real_T`, `boolean_T`, `creal_T`...). `coder_array.h` is included too, but only a function that cannot be bridged
uses it (`coder::array` is a C++ template), so it is not converted and its `#include` is left out of the C view.
C library includes (`<cstddef>`, `<cstdlib>`) are kept only if the header uses something from them (`size_t`):
otherwise jextract would bring half of libc into the bindings. Nothing else in the folder is read. The build shows
what it did with each header:

```
CppToC: 4 headers bridged to Java, 1 more converted to C (used by them), 1 included but not needed
  bridged   sensor.h             -> SensorApi: 7 functions (7 with C++ linkage), 1 not translated
  bridged   sensor_initialize.h  -> SensorApi: 1 function (1 with C++ linkage)
  bridged   sensor_terminate.h   -> SensorApi: 1 function (1 with C++ linkage)
  bridged   sensor_types.h       -> SensorTypes: 2 structs, 1 enum
  converted rtwtypes.h           for boolean_T, char_T, creal_T, int32_T, real_T (used by sensor.h, sensor_types.h): ...
  skipped   coder_array.h        not needed: the bridged headers use nothing it defines
  (2 other headers in [include] not used)
```

**Java classes.** For each bridged header:

* **its types are split into classes**: every struct is an immutable class of its own (`Camera.java`,
  `Struct0T.java`), every enum a Java enum (`Health.java`). Types it uses from headers that are converted but not
  bridged (MATLAB's `creal_T`) get classes too;
* **its functions and constants stay together in one class**: `device_registry.hpp=RegistryApi` names it; by
  default it is the header's name, with "Api" for headers with functions (`sensor.h` -> `SensorApi`,
  `sensor_types.h` -> `SensorTypes`). Several headers can name the same class: MATLAB's `sensor_initialize.h` and
  `sensor_terminate.h` go into `SensorApi` with `sensor.h`. A header with types only and no constants gets no class
  of its own.

**Headers in the folder:**

* Includes between headers: `#include "x.h"` (next to the header, or in an include folder) and `#include <x.h>`
  when `x.h` is one of the provider's become includes of the C views.
* One translation unit: the safe wrapper includes the bridged headers no other bridged header includes. A header
  without include guard is fine as long as only one header includes it, or it holds only `#define`s and
  declarations; otherwise CppToC warns, because the C++ compiler would see its structs twice.
* A header that is not self-contained (it uses a type without including the header that defines it, relying on
  "include x.hpp first") is included after a header that brings those types; CppToC says so in a warning.
* Names across headers: a struct defined in two headers (in different C++ namespaces) is reported; two functions
  with the same name get `safe_f` and `safe_f_2`.
* With no `providerHeaders`, every header in the folders is bridged, except the `providerHeaderExcludes`.

### MATLAB Coder output

MATLAB Coder's C++ code works as it is (`src/test/samples/matlab` is in its style).

| MATLAB generates | C view (for jextract) | Java |
|---|---|---|
| `typedef double real_T;` ... in `rtwtypes.h` | the same typedefs, converted only because they are used | `double`, `int`, `boolean`, ... |
| `struct struct0_T { real_T weight; char_T name[16]; };` | `typedef struct struct0_T {...} struct0_T;` | `Struct0T` (`name` is a `String`) |
| `enum class SensorKind : int { Temperature = 0, ... };` | `typedef enum SensorKind {...} SensorKind;` | `enum SensorKind` |
| `namespace sensorlib { extern void sensor(const struct0_T *in, double out[3]); }` | (C++ linkage: through `safe_sensor`) | `SensorApi.sensor(Struct0T in, double[] out)`: `out` is copied back |
| `const double weights[1000]` | the same | `double[] weights`, at least 1000 elements (checked in Java) |
| `void sensor_initialize();` in `sensor_initialize.h` | `int safe_sensor_initialize(void);` | `SensorApi.sensorInitialize()` with `sensor_initialize.h=SensorApi` |
| `coder::array<double, 2U>&` (variable-size arrays) | not translated, with a warning | — |
| `throw std::runtime_error(...)` (run-time checks) | caught in the safe wrapper | `SafeException` / your handler |

`coder::array` is a C++ template, which has no C equivalent: generate the entry points that cross into Java with
fixed-size (or bounded) arguments.

## The generated packages

The generated classes go into five packages under `basePackage`, one per role:

| package | what | for |
|---|---|---|
| `com.example.devices.api` | a class per bridged header, with its functions and constants: `RegistryApi`, `DeviceRegistryTypes` (constants only) | the application |
| `com.example.devices.types` | a value class per struct, an enum per enum: `Camera`, `Lidar`, `Radar`, `Point`, `Health` | the application |
| `com.example.devices.runtime` | what the API is used with: `NativeArray`, `Ref`, `NativeRecord`, `NativeEnum`; error handling: `SafeWrapper`, `SafeError`, `SafeException`, `SafeErrorHandler`; `AdditionalFiles` | the application |
| `com.example.devices.internal` | what the generated classes share: `RecordSupport`, `SafeCalls`, `NativeLibraries` | the generated code only |
| `com.example.devices.ffi` | jextract's bindings of the safe wrapper | the generated code only |

```java
import com.example.devices.api.RegistryApi;
import com.example.devices.types.Camera;
import com.example.devices.runtime.NativeArray;
import com.example.devices.runtime.SafeWrapper;
```

The generated module exports the first three, and the last two cannot be used from outside it (see
[Hiding the jextract bindings](#hiding-the-jextract-bindings)). Without a module (`generateModuleInfo` false, or the
jar on the class path) they are visible, but `internal` and `ffi` are not part of the API: their classes can
change with every generation.

A struct or enum named like a class of `runtime` or `internal`, or like a JDK class the generated code uses
(`Ref`, `String`, `Arena`, ...), gets a `Struct` or `Enum` suffix (`RefStruct`), with a warning.

## What Java gets

| C++ (provider header) | safe wrapper (C) | Java (`RegistryApi`) |
|---|---|---|
| `void updateRegistry(...)` (C++ linkage) | `int safe_updateRegistry(...)` (C linkage) | `void updateRegistry(...)`, plus an overload with `Ref`s for the three structs |
| `Camera& camera` | `Camera *camera` (not NULL) | `Camera camera` (not null), or `Ref<Camera>`: gets back what C++ wrote |
| `const Radar& r` | `const Radar *r` (not NULL) | `Radar r` (not null) |
| `Point point` (by value) | `const Point *point` (not NULL) | `Point point` (not null) |
| `Camera* cameras` (in `providerArrayParameters`) | `Camera *cameras` (not NULL) | `NativeArray<Camera>`: passed as is, not copied; size checked |
| `Camera* camera` (not configured) | `Camera *camera` | `Camera` / `Ref<Camera>`, `null` passes NULL |
| `int*`, `double*`, ... | the same | `Ref<Integer>`, `Ref<Double>`, ...: in/out |
| `void** outPtr` / `void* ptr` | the same | `Ref<MemorySegment>` / `MemorySegment` |
| `R f(...)` (returns a value) | `int safe_f(..., R *result)` | `R f(...)` |
| `enum Health { GREEN, ... }` / `enum class E { ... }` | `typedef enum Health { GREEN, ... } Health;` | `enum Health` |
| `enum class Small : uint8_t` | `typedef uint8_t Small;` + constants | `byte` |
| `#define REGISTRY_CAPACITY 500` / `constexpr int MAX = 8;` | `#define` | `DeviceRegistryTypes.REGISTRY_CAPACITY` (the class of the header that defines it) |
| `extern "C"` functions | declared in the C view too | the same as C++ ones (through the safe wrapper) |
| functions in a `namespace` | `safe_<name>` | `<name>(...)` |
| struct with methods, constructors, defaults | the data members | the value class (fields start at zero) |
| class with virtual functions, private data or a base class | opaque: `typedef struct X X;` | `MemorySegment` (pointers only) |
| templates, operators, `std::string`, `T&&` | skipped, with a warning | — |

CppToC prints a warning for everything it skips or changes, with the header line.

### Value classes

Every struct becomes an immutable `final class` with:

* `EMPTY` and a default constructor (`new Lidar()`, equal to `Lidar.EMPTY`), `isEmpty()`, a getter per field
  (`lidar.getWeight()`; booleans too: `getCalibrated()`), `builder()` / `toBuilder()`
  (`Lidar.builder().weight(2.5).build()`; list and string sizes are checked against the C array sizes in
  `build()`), and `equals` / `hashCode` / `toString` with record semantics (fields kept as `MemorySegment` compare
  by address);
* `from(segment)`, `fromPointer(pointer)`, `toNative(allocator)`, `writeTo(segment, offset, allocator)`, and
  `LAYOUT`, the struct's C layout (checked against the C++ compiler's);
* `allocateArray(arena, n)` for a `NativeArray<Lidar>`, and for every primitive or enum field `xAt(array, i)` and
  `setXAt(array, i, value)`: zero-copy access to one field of one element.

`EMPTY` is what an all-zero struct reads as: 0, `""`, lists of zeros, nested structs of zeros, `null` pointers.

### Arrays: `NativeArray`

```java
try (Arena arena = Arena.ofConfined()) {                // Java owns the memory
    NativeArray<Camera> cameras = Camera.allocateArray(arena, DeviceRegistryTypes.REGISTRY_CAPACITY);
    ...
    RegistryApi.updateRegistry(DeviceRegistryTypes.REQ_ADD, camera, Lidar.EMPTY, Radar.EMPTY, timeTag, pending,
            MemorySegment.NULL, buffer, cameras, lidars, radars, bufferSize, cameraCount, lidarCount);
    Camera first = cameras.get(0);                      // an immutable copy of one element
}                                                       // closing the arena frees the arrays
```

* `get`, `set`, `clear(i)`, `clear()`, `size()`, `elementSize()`, `byteSize()`, `segment()`.
* **The memory is shared with C++**, not copied: what it writes is visible at once, and what Java writes is
  visible to it on its next call. Change an array from Java only if the provider keeps nothing about it between
  calls.
* **The array size is a contract the signature does not carry.** A C++ `Camera*` takes no length, so list such
  parameters in `providerArrayParameters` with the size the provider assumes. The generated code checks every
  `NativeArray` against it and throws `IllegalArgumentException` before the call for a smaller one.
* When a struct with a pointer field (`Lidar.origin`) is passed together with a `NativeArray`, what the pointer
  points to is allocated in that array's arena, since C++ may store the struct in the array: it stays valid until
  the arena is closed.

Other struct and string arguments are copied into per-thread scratch memory that is reused when the call returns.
For a function that keeps such a pointer after returning, call the jextract method (`safe_<name>` in
`<basePackage>.ffi.<name>_safe_h`) with your own `Arena`. In the generated module that package is hidden: such code goes
into the bindings module, or gets an explicit `--add-exports` (see below).

## What the safe wrapper does

For every provider function `F`, CppToC writes a wrapper. For `updateRegistry`:

```cpp
// void updateRegistry(int reqType, Camera& camera, Lidar& lidar, Radar& radar, ...)
SAFE_API int safe_updateRegistry(int reqType, Camera *camera, Lidar *lidar, Radar *radar,
                                 double timeTag, double pendingEvents, void *ptr, void **outPtr,
                                 Camera *cameras, Lidar *lidars, Radar *radars,
                                 int *sizeOfInternalBuffer, double *cameraCount, double *lidarsCount) noexcept {
    if (camera == nullptr) return safe_detail::null_argument("updateRegistry", "camera", "a C++ reference");
    ...                                                      // lidar, radar; the three arrays
    try {
        ::updateRegistry(reqType, *camera, *lidar, *radar, timeTag, pendingEvents, ptr, outPtr,
                         cameras, lidars, radars, sizeOfInternalBuffer, cameraCount, lidarsCount);
        return SAFE_OK;
    } catch (...) {
        return safe_detail::caught("updateRegistry");        // SAFE_CPP_EXCEPTION or SAFE_UNKNOWN_EXCEPTION
    }
}
```

* Return value: a status (`SAFE_OK`, `SAFE_NULL_ARGUMENT`, `SAFE_CPP_EXCEPTION`, `SAFE_UNKNOWN_EXCEPTION`). A
  function that returns something gets one more parameter, `R *result`, for its return value. After a failure,
  `safe_last_error_type()` (the demangled C++ type: `std::invalid_argument`, or `int` for `throw 42`) and
  `safe_last_error_message()` (`what()`) describe it. They are thread-local.
* NULL checks before the call: references, structs passed by value, and the `providerArrayParameters`.
* `Point point` (by value) is passed as `const Point *`; `const int&` as `int`.
* `safe_layout_Camera(...)` and so on: the C++ compiler's `sizeof`, `alignof` and `offsetof` of every field.

Each declaration in the safe wrapper's header carries a `/** @wraps <original declaration> */` line, for the
reader. The Java method looks like the provider function (`Camera camera`, not `MemorySegment`), because RecordGen
works from the model, which records each parameter as the provider declares it.

## Handling C++ exceptions

Every Java call to the provider goes through the safe wrapper, which catches **every** exception the provider's
C++ code throws (`catch (...)` around each call): none reaches the JVM. Java receives it as a `SafeError`, and an
error handler you choose decides what happens.

### 1. What Java gets

| the provider throws | `code()` | `exceptionType()` | `message()` | `isMapped()` |
|---|---|---|---|---|
| a `std::exception` (any subclass) | `CPP_EXCEPTION` | its own type: `std::out_of_range`, `vendor::Locked` | `what()` | `true` |
| a type listed in `providerExceptions` (step 4) | `CPP_EXCEPTION` | its type: `vendor::Error` | the member you named | `true` |
| a string: `throw "busy"`, a `std::string` | `UNKNOWN_EXCEPTION` | `const char*`, `std::string` | the string | `false` |
| a number: `throw 503` | `UNKNOWN_EXCEPTION` | `int`, `long`, `double`, ... | the number | `false` |
| any other type | `UNKNOWN_EXCEPTION` | its type: `vendor::Opaque` | empty | `false` |
| nothing: a NULL reference or array | `NULL_ARGUMENT` | empty | which argument | `false` |

Also `function()` (the provider function, `updateRegistry`), `isException()` (true for the first five rows), and
`toString()`, a one-line report:

```
updateRegistry: std::out_of_range: unknown reqType 7 (SAFE_CPP_EXCEPTION)
updateRegistry: const char*: registry is busy (SAFE_UNKNOWN_EXCEPTION)
updateRegistry: vendor::Opaque, no message (not a std::exception and not mapped) (SAFE_UNKNOWN_EXCEPTION)
```

A *mapped* exception is one whose type the safe wrapper knows, so it can give you its message. Any other exception
is still caught and reported, with its type, and with its value if it is a string or a number.

### 2. Catch it: the default

By default (`SafeErrorHandler.THROW`) a failed call throws `SafeException`, an unchecked exception that carries
the `SafeError`:

```java
try {
    RegistryApi.updateRegistry(request, camera, Lidar.EMPTY, Radar.EMPTY, ...);
} catch (SafeException e) {
    SafeError error = e.error();
    error.exceptionType();   // "std::out_of_range"
    error.message();         // "unknown reqType 7"
    error.isMapped();        // true
}
```

### 3. Decide per kind: handle what you know, report the rest

Set one handler for the whole application (every API class of the package uses it). The handler runs on the
calling thread, right after the failed call. If it throws, the call ends with that exception
(`RegistryBusyException`, `ProviderException` and `log` stand for your own classes):

```java
SafeWrapper.setErrorHandler(error -> {
    if (error.isMapped()) {
        switch (error.exceptionType()) {                       // the provider's errors you know
            case "vendor::Locked" -> throw new RegistryBusyException(error.message());
            case "std::out_of_range" -> throw new IllegalArgumentException(error.message());
            default -> throw new ProviderException(error.toString());
        }
    }
    log.error("unexpected C++ exception: " + error);           // not mapped: report it...
    throw new SafeException(error);                            // ...and still fail the call
});
```

### 4. Map the provider's own exception types

Many SDKs throw their own classes, not derived from `std::exception` (`vendor::Error` with a `message()`). By
default these arrive as `UNKNOWN_EXCEPTION` with their type but no message. To get the message, list them with
the member that gives it:

```xml
<!-- Type[=member]: a function (message()) or a field (code) returning a string or a number; a derived class first -->
<providerExceptions>
    <providerException>vendor::Timeout=message()</providerException>
    <providerException>vendor::Error=message()</providerException>
    <providerException>vendor::Fault=code</providerException>
</providerExceptions>
<!-- only if the bridged headers do not include the header that declares them -->
<providerExceptionHeaders>
    <providerExceptionHeader>vendor/errors.hpp</providerExceptionHeader>
</providerExceptionHeaders>
```

The safe wrapper then catches these types first, before `std::exception`, and reports them as `CPP_EXCEPTION` with
their message: `isMapped()` is true. Two things to check:

* **A typo in the type name** gives a C++ compiler error in `<name>_safe.cpp` naming the type. CppToC also warns
  if it cannot find the type in the headers it read and no `providerExceptionHeaders` are given.
* **List a derived class before its base.** Otherwise the base catches it first. The type reported is still the
  exception's real (derived) type, but its message comes from the base class's member.

`src/test/exceptions.sh` shows the result for each kind, with and without the mapping (expected output in
`src/test/cases/exceptions/expected.txt`).

### 5. Other policies

```java
SafeWrapper.setErrorHandler(SafeErrorHandler.LOG);      // log a warning (System.Logger) and let the call return
SafeWrapper.setErrorHandler(SafeErrorHandler.IGNORE);   // let the call return, silently
SafeWrapper.setErrorHandler(SafeErrorHandler.THROW);    // back to the default

// for one block of code, on this thread only (a ScopedValue): here, collect the failures and carry on
List<SafeError> problems = new ArrayList<>();
SafeWrapper.runWithErrorHandler(problems::add, () -> {
    for (Camera c : batch) RegistryApi.updateRegistry(REQ_ADD, c, Lidar.EMPTY, Radar.EMPTY, ...);
});
double count = SafeWrapper.callWithErrorHandler(SafeErrorHandler.IGNORE, () -> {
    RegistryApi.updateRegistry(REQ_ADD, camera, Lidar.EMPTY, Radar.EMPTY, ...);   // a failure here is ignored
    return cameraCount.get();
});
```

* A handler that returns makes the call return 0, `false` or `null`. In/out `Ref`s keep their previous values:
  nothing is copied back from a failed call.
* After a failure the provider library keeps working: the next call is a normal call.
* `null` for a C++ reference is rejected in Java (`NullPointerException`) before the call. The safe wrapper's own
  NULL check (`NULL_ARGUMENT`) protects code that calls the `safe_*` functions directly.

### 6. What no safe wrapper can catch

* **A crash in the provider's code** (segmentation fault, `abort()`), or memory corruption. It is not an
  exception, and it ends the process. Run the provider in a separate process if you need to survive it.
* **An exception on a thread the provider starts itself**, one that escapes a `noexcept` function or a
  destructor, or one thrown while another is in flight. C++ calls `std::terminate` for these before any catch.
* **A safe wrapper built with a different compiler family or C++ runtime than the provider library.** Its catch
  may then not recognize the provider's exceptions. See [Compiler](#compiler).

## Native libraries in the jar

By default (`bundleNativeLibraries`) the plugin copies the libraries into the project's resources, so they end up
in the jar next to the API:

```
com/example/devices/native/linux-x86_64/libraries.txt      their load order: libdevreg.so, libdevreg_safe.so
com/example/devices/native/linux-x86_64/libdevreg.so       the provider library (and its providerRuntimeLibraries)
com/example/devices/native/linux-x86_64/libdevreg_safe.so  the safe wrapper library
```

The first API class the application uses loads them (`NativeLibraries`, called by `SafeWrapper`). It loads each
one by its full path, with `System.load`. It never calls `System.loadLibrary`, so nothing is looked for on
`java.library.path` or in the system's library folders: the location is the one the build chose, or the one the
variable below names. From the first of:

1. **The folder in `DEVREG_LIBRARY_DIR`** (the environment variable, or a system property of that name, which
   wins), when it is set. All the libraries must be there (`libdevreg.so`, `libdevreg_safe.so`); a missing one is
   an error, not a reason to look elsewhere. The name is
   `<PROVIDERLIBRARY>_LIBRARY_DIR` by default; `libraryDirVariable` sets another.
2. **The jar**, when it has a folder for the machine the JVM runs on (`linux-x86_64`, or `linux-aarch64` for a
   jar built on one): the files are copied into a new temporary folder that only the current user can read, and
   loaded in the order of `libraries.txt`. The folder is deleted when the JVM exits (the folder of a JVM that
   crashes or is killed stays). `-Dnative-to-ffm.extract.dir=/some/dir` makes it there instead of in `java.io.tmpdir` (for a
   `/tmp` mounted `noexec`, where libraries cannot be loaded from).
3. **The folders the libraries were in when the API was generated** (`providerLibraryDir`,
   `safeWrapperLibraryDir`), with `bundleNativeLibraries` false: for a build that runs where it was built.

If none applies (a jar built on x86-64, run on aarch64, and no variable), the `UnsatisfiedLinkError` says what to
set:

```
no native libraries for linux-aarch64: the jar has none for it (com/example/devices/native/linux-aarch64/), and
DEVREG_LIBRARY_DIR is not set. Set it to the folder with libdevreg.so, libdevreg_safe.so.
```

The safe wrapper library finds the provider library next to it (its rpath includes `$ORIGIN`). Code that calls the libraries before any API class has
(white-box tests that call the jextract bindings, say) calls `SafeWrapper.loadNativeLibraries()` first.

**One architecture per build.** The jar carries the libraries of the machine it was built on (`linux-x86_64`),
because the safe wrapper is compiled there.

**The libraries the provider library needs.** `providerRuntimeLibraries` lists the provider's other libraries,
which are bundled (or looked for in the same folders) and loaded first. System libraries (`libstdc++`, `libc`) are
not bundled: the machine that runs the application needs them, as for any C++ program built
with that compiler.

## Additional files

Files that should travel with the native libraries go into `additionalFiles`: configuration files, licences,
data tables the provider library reads. Each entry is a file or a folder, relative to the project:

```xml
<additionalFiles>
    <additionalFile>config/registry.cfg</additionalFile>     <!-- named registry.cfg -->
    <additionalFile>calibration</additionalFile>             <!-- everything in it: calibration/lidar.csv, ... -->
</additionalFiles>
```

They go into the jar (`com/example/devices/native/files/`), and at run time they are where the libraries are:
copied out of the jar into the same temporary folder, before the libraries are loaded, so a provider library that
reads files next to itself finds them. In the folder `DEVREG_LIBRARY_DIR` names, they must be there too. With
`bundleNativeLibraries` false, they stay where they are. Java gets their path from `AdditionalFiles`:

```java
Path config = AdditionalFiles.path("registry.cfg");      // loads the libraries first, if no API class has
List<String> all = AdditionalFiles.names();               // [registry.cfg, calibration/lidar.csv]
```

An entry can also be a comma-separated list, so the files can be a property that the build command sets:

```xml
<properties>
    <additional.files>${project.basedir}/config/registry.cfg</additional.files>
</properties>
...
<additionalFiles>${additional.files}</additionalFiles>
```

```sh
mvn install -Dadditional.files=/etc/devices/site.cfg,/etc/devices/calibration
```

A name that is not one of them is an `IllegalArgumentException`; a file missing from the folder the variable names
is an `IllegalStateException`. Files that only the application reads, and not with the native libraries, belong in
the project's ordinary resources (`src/main/resources`) instead.

## Hiding the jextract bindings

The API classes call jextract's bindings (`<basePackage>.ffi`), and those are public Java classes: on the class
path, any code can call `safe_updateRegistry` directly, with raw `MemorySegment`s, and skip everything the API
checks. Java has no visibility between packages other than `public`, but a **module** decides which of its
packages other code can see. So by default the plugin writes this `module-info.java` next to the API (the
module's name is `moduleName`, by default the `basePackage`; `generateModuleInfo` false writes none):

```java
module com.example.devices {
    exports com.example.devices.api;        // RegistryApi, DeviceRegistryTypes
    exports com.example.devices.types;      // Camera, Lidar, Radar, Point, Health
    exports com.example.devices.runtime;    // NativeArray, Ref, SafeWrapper, SafeError, ...
}                                           // not .internal, not .ffi
```

Code outside the module then gets a compile error for the jextract package, and an `IllegalAccessError` if it
reaches it at run time some other way:

```
error: package com.example.devices.ffi is not visible
  (package com.example.devices.ffi is declared in module com.example.devices, which does not export it)
```

The rule holds only where the API jar is used **as a module, from the module path**:

| the application | the jextract package |
|---|---|
| is a module: `module app { requires com.example.devices; }` (Maven then uses the module path) | hidden |
| is not a module, run with `--module-path api.jar --add-modules com.example.devices` | hidden |
| is not a module, the API jar on the class path (Maven's default for a non-modular project) | visible: `module-info.class` is ignored there |

So the application should be a module too. With modules, native access can also be granted per module, rather
than to everything on the class path: `--enable-native-access=com.example.devices` for the API, plus the
application's module if it calls restricted methods itself (`MemorySegment.reinterpret`, say).

White-box tests that call the jextract bindings on purpose say so with an explicit opt-in, at compile time and at
run time: `--add-exports com.example.devices/com.example.devices.ffi=<the test's module>`.

Set `generateModuleInfo` to false if the project that holds the bindings has a `module-info.java` of its own:
add `exports` for `.api`, `.types` and `.runtime` to it, and do not export `.internal` or `.ffi`.

## Layout check

When the first API class is loaded, `SafeWrapper` compares every struct's Java layout (from the C view) with what
the C++ compiler reports (`safe_layout_*`). A difference throws a `LinkageError` before any call is made:

```
struct Camera does not match the C++ layout; model is at offset 20 in C++, 18 in Java.
Regenerate the bindings from the provider's current header.
```

That catches a provider header that changed without a rebuild, a `#pragma pack` or compiler flag that differs,
and anything CppToC translated wrongly.

## Performance

Measured with a 14-parameter function like `updateRegistry` (three structs by reference, three arrays of 500,
in/out pointers), JDK 25 on a 2-core cloud VM:

* **The safe wrapper costs about 30 ns per call**: one more native call, the NULL checks, the arguments passed
  on. `try`/`catch` costs nothing when nothing is thrown (zero-cost exceptions).
* **The Java API costs about 200 ns per call** on top: the three structs are copied into native memory and the
  in/out parameters read back. Arguments go into a per-thread bump-allocated scratch buffer, released when the
  call returns: no `Arena`, `malloc` or `free` per call.
* **Arrays**: a `NativeArray` is passed without copying. `get(i)` reads straight from memory into the object; for
  hot loops over one field, `Camera.weightAt(cameras, i)` is a single memory read (~1 ns, the same as raw FFM).
  Pointer fields of stored structs are carved out of 64 KB chunks of the array's arena.
* **Big structs**: HotSpot never JIT-compiles a method over 8000 bytes of bytecode, so for structs with more than
  32 fields the per-field code is split into methods of 32 fields (for a 402-field struct: passing it 15x faster,
  reading it 9x faster than one big method).
* **Startup**: the layout check is one native call per struct, when the first API class is loaded.

## Compiler

The safe wrapper catches the provider's exceptions as `std::exception`, which only works when both libraries use
the same C++ runtime: build it with the compiler family the provider used (GCC, or Clang with the same standard
library: libstdc++ or libc++). With a different runtime, `catch (const std::exception&)` does not match the
provider's exceptions: at best `catch (...)` gets them, reported as `SAFE_UNKNOWN_EXCEPTION` without a message.

The compiler is `$CXX`, else `c++`; `-Dnative-to-ffm.compiler=clang++` (or `safeWrapperCompiler`) chooses another.
The command is `c++ -std=c++17 -O2 -fPIC -shared -Wl,--no-undefined`, with two rpaths: `$ORIGIN` (where the jar
puts both libraries) and `$ORIGIN/<the provider library's folder>`. The build log shows it in full.

## Air-gapped environments

The plugin is **one jar with no dependencies**: the generators, the Java runtime classes and the C++ runtime they
copy into their output, and the plugin descriptor. The only classes outside the JDK it uses are Maven's own API
(`maven-plugin-api`, `maven-core`, `maven-model`), which the Maven that runs it provides: they are `provided`
dependencies, and Maven does not look for them in a repository. The build keeps it that way: an enforcer rule
fails it if a dependency with another scope is added.

To use it in a network without access to Maven Central, build it outside (`mvn package`), and take in two files:

| file | |
|---|---|
| `target/native-to-ffm-bindings-generator-<version>.jar` | the plugin |
| `pom.xml` | its POM: no parent, nothing to resolve |

Then install them in the air-gapped network, in one of three ways:

* **A repository manager** (Nexus, Artifactory): upload the two files as `io.nativeffm:native-to-ffm-bindings-generator:<version>`,
  or `mvn deploy:deploy-file -Dfile=<jar> -DpomFile=pom.xml -Durl=<repository> -DrepositoryId=<id>`.
* **A local repository, with Maven**: `mvn install:install-file -Dfile=<jar> -DpomFile=pom.xml`.
* **A local repository, with nothing else**: `install-offline.sh <jar> pom.xml [repository]` copies them into the
  repository's folder layout (default `~/.m2/repository`).

Maven then runs the plugin offline (`mvn -o`) with only those two files in its repository: nothing else is
downloaded. Prefer a release version (no `-SNAPSHOT`) for what you take in.

What the machine that runs the plugin needs besides it are tools, not Maven artifacts: JDK 25, jextract (an
archive from https://jdk.java.net/jextract: unpack it, and point `jextract.executable` at it), and a C++ compiler.
The project that uses the plugin also needs its usual Maven plugins (compiler, resources, jar, ...) in the
air-gapped repository, as any Maven build does. The application that uses the generated jar needs only that jar
and a JDK: the native libraries are in it.

## Building and testing the plugin

```sh
mvn install                                                       # the plugin, into the local repository
./install-offline.sh target/native-to-ffm-bindings-generator-1.0.0-SNAPSHOT.jar pom.xml   # the same, without Maven
JAVA=/path/to/jdk-25/bin/java JEXTRACT=/path/to/jextract src/test/golden.sh
JAVA=/path/to/jdk-25/bin/java src/test/exceptions.sh
JAVA=/path/to/jdk-25/bin/java JEXTRACT=/path/to/jextract src/test/samples.sh
```

`golden.sh` runs the generators on every sample and test header and compares everything they write with
`src/test/expected/`; `exceptions.sh` throws every kind of exception through a compiled safe wrapper; `samples.sh`
runs the goal in Maven on the samples, with their minimal configuration. How the plugin works, and how to review
a change, is in `DESIGN.md`; for an AI agent working on the code, `AGENTS.md` and the skill in
`.claude/skills/native-to-ffm-generator/` are the starting point.

The generators also run without Maven: `java -cp target/classes io.nativeffm.cpptoc.CppToC --help` and
`io.nativeffm.recordgen.RecordGen --help`.
