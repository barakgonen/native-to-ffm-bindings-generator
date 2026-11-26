#!/bin/bash
# Golden test of the generators: runs CppToC -> jextract -> RecordGen on every case below and compares what they
# write (C views, safe wrapper files, the model, the Java classes, their console output) with expected/<case>/,
# and compiles the Java classes with jextract's (a case whose Java does not compile gets a javac.log).
#
#   src/test/golden.sh            compare (exit 1 and the list of differing files if anything changed)
#   src/test/golden.sh --update   accept the current output as expected (review the diff in git first)
#
# Needs a JDK 25 (JAVA, default: java on the PATH) and jextract (JEXTRACT, default: jextract on the PATH). The
# generators run from the command line here, with the options the Maven goal passes them.
# The expected Java depends on the platform's C type sizes: it was generated on Linux x86-64.
set -u
HERE=$(cd "$(dirname "$0")" && pwd)
. "$HERE/generators.sh"
OUT=$PLUGIN/target/golden
JEXTRACT=${JEXTRACT:-jextract}
rm -rf "$OUT" && mkdir -p "$OUT"

# The console output, without what depends on the machine: JVM notes and absolute paths.
log() { grep -v "^Picked up " | sed "s#$OUT#<out>#g; s#$PLUGIN#<project>#g; s#/tmp/recordgen[0-9]*#<tmp>#g"; }

# MODULE=<name>, FILES=<names> before a case: RecordGen's --module, --files. Then: case name, safe wrapper base name, --array-params, CppToC's header options...
case_() {
  local c=$1 name=$2 arrays=$3; shift 3
  local w=$OUT/$c
  mkdir -p "$w"
  "$JAVA" -cp "$GENERATORS" io.nativeffm.cpptoc.CppToC --out "$w/c" --name "$name" --array-params="$arrays" "$@" 2>&1 | log > "$w/cpptoc.log"
  [ -f "$w/c/include/${name}_safe.h" ] || return 0
  "$JEXTRACT" --output "$w/jextract" --target-package gen.ffi \
      -I "$w/c/include" "$w/c/include/${name}_safe.h" > /dev/null 2>&1
  "$JAVA" --enable-native-access=ALL-UNNAMED -cp "$GENERATORS" io.nativeffm.recordgen.RecordGen --model "$w/c/${name}_model.json" \
      --jextract "$w/jextract" --jextract-package gen.ffi --package gen --out "$w/java" --libraries "$name,${name}_safe" \
      --library-dir-variable "$(echo "$name" | tr a-z A-Z)_LIBRARY_DIR" ${MODULE:+--module "$MODULE"} ${FILES:+--files "$FILES"} \
      2>&1 | log > "$w/recordgen.log"
  # the generated Java must compile, with jextract's: a javac.log in the output means it does not
  "$JAVAC" -proc:none -d "$w.classes" $(find "$w/java" "$w/jextract" -name '*.java') > "$w/javac.log" 2>&1 || true
  grep -v '^Picked up ' "$w/javac.log" | log > "$w/javac.log.tmp"; mv "$w/javac.log.tmp" "$w/javac.log"
  [ -s "$w/javac.log" ] || rm -f "$w/javac.log"
  rm -rf "$w/jextract" "$w.classes"                        # jextract's own output is not ours to check
  if [ "$c" != device-registry ]; then                     # the runtime classes are the same in every case
    rm -rf "$w/java/gen/runtime"
    rm -f "$w/java/gen/internal/RecordSupport.java"
  fi
}

SAMPLES=$HERE/samples
CASES=$HERE/cases
AP=cameras=REGISTRY_CAPACITY,lidars=REGISTRY_CAPACITY,radars=REGISTRY_CAPACITY
MODULE=gen.devices FILES=registry.cfg,calibration/lidar.csv \
case_ device-registry  device_registry "$AP" --include-dir "$CASES/device-registry/include" --headers=device_registry_types.hpp,device_registry.hpp=RegistryApi
case_ multi-header-all device_registry ""    --include-dir "$SAMPLES/multi-header/include" --exclude='internal/**'
case_ multi-header-two inventory   ""    --include-dir "$SAMPLES/multi-header/include" --headers inventory.hpp,pricing/pricing.hpp=PricingApi
case_ matlab           sensor      ""    --include-dir "$SAMPLES/matlab/include" --headers=sensor_types.h,sensor.h=SensorApi,sensor_initialize.h=SensorApi,sensor_terminate.h=SensorApi
case_ cpp-features     nasty       "values,items" --include-dir "$CASES/cpp-features/include" --headers nasty.hpp
case_ include-guards   guard       ""    --include-dir "$CASES/include-guards/include"
case_ many-fields      big         ""    --include-dir "$CASES/many-fields/include"
case_ c-enums          e           ""    --include-dir "$CASES/c-enums/include"
case_ array-params     a           "camera=REGISTRY_CAPACITY,registry_init.cameras" --include-dir "$CASES/array-params/include"
case_ exceptions       store       ""    --include-dir "$CASES/exceptions/include" --headers store.hpp \
      --exceptions='vendor::Timeout=message(),vendor::Error=message(),vendor::Fault=code' --exception-headers=errors.hpp
case_ missing-header   x           ""    --include-dir "$CASES/device-registry/include" --headers nope.h
case_ unknown-option   x           ""    --include-dir "$CASES/device-registry/include" --frobnicate

if [ "${1:-}" = "--update" ]; then
  rm -rf "$HERE/expected" && cp -r "$OUT" "$HERE/expected"
  echo "golden: expected/ updated ($(find "$HERE/expected" -type f | wc -l) files)"
  exit 0
fi
if diff -r -q "$HERE/expected" "$OUT" > "$OUT.diff"; then
  echo "golden: identical ($(find "$OUT" -type f | wc -l) files)"
else
  sed "s#$HERE/expected#expected#g; s#$OUT#<out>#g" "$OUT.diff"
  echo "golden: differences (diff -r $HERE/expected $OUT)"
  exit 1
fi
