# Sample: MATLAB Coder C++ output

Headers in the style of MATLAB Coder's C++ code generation (`codegen -lang:c++`) for an entry point `sensor`:

| header | what it is | bridged? |
|---|---|---|
| `sensor.h` | the entry points, in `namespace sensorlib`, C++ linkage: fixed-size arrays (`double out[3]`), structs, `creal_T`, `boolean_T`, one `coder::array` function | yes, `SensorApi` |
| `sensor_initialize.h`, `sensor_terminate.h` | `sensor_initialize()` / `sensor_terminate()` | yes, into `SensorApi` too |
| `sensor_types.h` | `struct0_T`, `struct1_T`, `enum class SensorKind : int` | yes (types: a class each) |
| `rtwtypes.h` | `real_T`, `boolean_T`, `char_T`, `creal_T`..., `MAX_int8_T`... | no: converted to C because the others use it |
| `coder_array.h` | `coder::array` (templates) | no, and not converted: nothing bridged uses it |
| `rt_nonfinite.h`, `sensor_internal_types.h` | support headers | no, not read |

`sensor_impl.cpp` is a stand-in for the generated `.cpp` files (it throws `std::runtime_error` like MATLAB's run-time
checks). CppToC alone, from the plugin's folder after `mvn install`:

```sh
java -cp target/classes io.nativeffm.cpptoc.CppToC --include-dir src/test/samples/matlab/include \
     --out /tmp/matlab-out --name sensor \
     --headers sensor_types.h,sensor.h=SensorApi,sensor_initialize.h=SensorApi,sensor_terminate.h=SensorApi
```

The whole pipeline: `pom.xml` here is a project of its own that uses the plugin, as any project would: it sets the
provider's folder (this one: `include/` and `lib/`), the headers to bridge, and an additional file; the rest is
defaults. Build the stand-in library into `lib/`, then run Maven in this folder:

```sh
mkdir -p lib && c++ -std=c++17 -shared -fPIC -I include -o lib/libsensor.so sensor_impl.cpp
mvn package
```

`target/matlab-bindings-1.0.0-SNAPSHOT.jar` is then the module `io.nativeffm.samples.sensor` (the default package:
the groupId and the library's name), with `libsensor.so` and `libsensor_safe.so` (in
`io/nativeffm/samples/sensor/native/linux-x86_64/`, loaded from there at run time), `sensor.cfg` (an additional
file, from `config/`: `AdditionalFiles.path("sensor.cfg")`), `io.nativeffm.samples.sensor.api.SensorApi` with
`sensor(Struct0T in, double[] out)`, `sensorTotal(double[] weights)`, `sensorInitialize()`, ..., and in
`io.nativeffm.samples.sensor.types` the classes `Struct0T`, `Struct1T`, `CrealT` and the enum `SensorKind`.
