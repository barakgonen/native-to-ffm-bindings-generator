# Sample: a provider with several headers

8 headers in the style of the device registry (plain C++ declarations, C++ linkage, a types header), used to test
CppToC on a folder: with every header bridged, or a few:

| header | what it tests |
|---|---|
| `device_registry_types.hpp`, `device_registry.hpp` | the device registry's two headers (`src/test/cases/device-registry`) |
| `inventory_types.hpp` | includes `device_registry_types.hpp` and `units.hpp`; `enum class`; a struct with a struct member |
| `inventory.hpp` | no include guard; functions taking `Crate&`, returning an `enum class` |
| `pricing/pricing.hpp` | a subfolder; `#include <device_registry_types.hpp>` and `"../units.hpp"`; a namespace; a struct passed by value |
| `units.hpp` | no include guard, included by two headers (only `#define`s: harmless) |
| `analytics.hpp` | not self-contained: uses `Radar` without including its header |
| `internal/device_registry_impl.hpp` | the provider's own header, `#error`s outside its build: to exclude |

`provider.cpp` is a stand-in implementation. CppToC alone, from the plugin's folder after `mvn install`:

```sh
S=src/test/samples/multi-header
# every header (but the internal one)
java -cp target/classes io.nativeffm.cpptoc.CppToC --include-dir $S/include --exclude='internal/**' \
     --out /tmp/multi-out --name device_registry
# two of them: CppToC converts the headers they need too (device_registry_types.hpp, inventory_types.hpp, units.hpp)
java -cp target/classes io.nativeffm.cpptoc.CppToC --include-dir $S/include \
     --headers inventory.hpp,pricing/pricing.hpp=PricingApi --out /tmp/multi-out2 --name inventory
```

The whole pipeline: `pom.xml` here is a project of its own that uses the plugin: it sets the provider's folder
(this one: `include/` and `lib/`) and the headers to leave out; the rest is defaults (package
`io.nativeffm.samples.devreg`). Build the stand-in library into `lib/`, then run Maven in this folder:

```sh
mkdir -p lib && c++ -std=c++17 -shared -fPIC -I include -o lib/libdevreg.so provider.cpp
mvn package
```
