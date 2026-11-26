#!/bin/bash
# Test of the Maven goal (GenerateMojo): runs native-to-ffm:generate in real Maven on the two sample projects, as
# they are configured (providerDir and little else: the defaults do the rest), compiles what it writes, and checks
# the defaults it chose and what it bundled. Then the errors a project gets for a missing or ambiguous setting.
#
#   src/test/samples.sh
#
# Needs the plugin in the local Maven repository (mvn install, or install-offline.sh), Maven (MVN, default: mvn),
# a JDK 25 (JAVA, default: java on the PATH), jextract (JEXTRACT, default: jextract on the PATH) and c++. Extra
# Maven options in MVN_OPTS (offline: MVN_OPTS="-o -Dmaven.repo.local=/path/to/repo"). The samples are copied into
# target/samples-test first: the source tree stays clean. Only the generate-sources phase runs, so the project's
# other Maven plugins (compiler, jar...) are not needed.
set -u
HERE=$(cd "$(dirname "$0")" && pwd)
PLUGIN=$(cd "$HERE/../.." && pwd)
MVN=${MVN:-mvn}
MVN_OPTS=${MVN_OPTS:-}
JAVA=${JAVA:-java}
case $JAVA in */*) JAVAC=${JAVA%/*}/javac ;; *) JAVAC=javac ;; esac
JEXTRACT=${JEXTRACT:-jextract}
VERSION=$(sed -n 's|^    <version>\(.*\)</version>|\1|p' "$PLUGIN/pom.xml" | head -1)
OUT=$PLUGIN/target/samples-test
rm -rf "$OUT" && mkdir -p "$OUT"
fail=0

# Maven runs on the JDK in JAVA: the plugin needs JDK 25 in Maven's JVM
JDK_HOME=$(dirname "$(dirname "$(readlink -f "$(command -v "$JAVA")")")")

# mvn in folder $1, the rest: Maven arguments; output in $1/mvn.log
mvn_in() {
  local dir=$1; shift
  (cd "$dir" && JAVA_HOME=$JDK_HOME "$MVN" -B $MVN_OPTS -Djextract.executable="$JEXTRACT" "$@") > "$dir/mvn.log" 2>&1
}
expect() {  # file, text: the file must contain the text
  grep -qF -- "$2" "$1" || { echo "samples: $(basename "$(dirname "$1")"): no '$2' in $(basename "$1")"; fail=1; }
}
exists() {  # path
  [ -e "$1" ] || { echo "samples: missing ${1#$OUT/}"; fail=1; }
}

# name, library, stand-in source, package: a sample project, its defaults, its jar resources, its Java
sample() {
  local name=$1 lib=$2 src=$3 pkg=$4 w=$OUT/$1
  cp -r "$HERE/samples/$name" "$w" && rm -rf "$w/target" "$w/lib"
  mkdir -p "$w/lib" && c++ -std=c++17 -shared -fPIC -I "$w/include" -o "$w/lib/lib$lib.so" "$w/$src" \
      || { echo "samples: $name: the stand-in library does not build"; fail=1; return; }
  mvn_in "$w" generate-sources || { echo "samples: $name: the goal failed:"; grep "ERROR" "$w/mvn.log" | head -5; fail=1; return; }
  expect "$w/mvn.log" "provider library: $w/lib/lib$lib.so (the lib*.so in lib/)"
  expect "$w/mvn.log" "Java API: $pkg.api, .types, .runtime (basePackage default), module $pkg"
  expect "$w/mvn.log" "safe wrapper: $w/target/safe-wrapper (${lib}_safe.h, ${lib}_safe.cpp)"
  local res=$w/target/generated-resources/native-to-ffm/${pkg//./\/}/native
  exists "$res/linux-$(uname -m | sed 's/amd64/x86_64/; s/arm64/aarch64/')/lib$lib.so"
  exists "$res/linux-$(uname -m | sed 's/amd64/x86_64/; s/arm64/aarch64/')/lib${lib}_safe.so"
  exists "$w/target/generated-sources/ffm-api/module-info.java"
  "$JAVAC" -proc:none -d "$w/target/classes" $(find "$w/target/generated-sources" -name '*.java') > "$w/javac.log" 2>&1 \
      || { echo "samples: $name: the generated Java does not compile:"; grep -v "^Picked up" "$w/javac.log" | head -5; fail=1; }
}

sample matlab       sensor sensor_impl.cpp io.nativeffm.samples.sensor
expect "$OUT/matlab/mvn.log" "and in io/nativeffm/samples/sensor/native/files/: [sensor.cfg]"
sample multi-header devreg provider.cpp     io.nativeffm.samples.devreg
expect "$OUT/multi-header/mvn.log" "every header but [internal/**]"
expect "$OUT/multi-header/mvn.log" "excluded  device_registry_impl.hpp"

# The errors: no configuration at all, then a provider folder with two libraries
e=$OUT/errors && mkdir -p "$e"
cat > "$e/pom.xml" <<POM
<project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion>
  <groupId>org.example</groupId><artifactId>errors</artifactId><version>1</version>
  <build><plugins><plugin><groupId>io.nativeffm</groupId><artifactId>native-to-ffm-bindings-generator</artifactId>
    <version>$VERSION</version><executions><execution><goals><goal>generate</goal></goals></execution></executions>
  </plugin></plugins></build>
</project>
POM
mvn_in "$e" generate-sources && { echo "samples: errors: no providerDir, and the goal did not fail"; fail=1; }
expect "$e/mvn.log" "set providerDir: the provider's root folder"
cp "$OUT/multi-header/lib/libdevreg.so" "$OUT/multi-header/lib/libother.so"
mvn_in "$e" generate-sources -Dnative-to-ffm.providerDir="$OUT/multi-header" \
    && { echo "samples: errors: two libraries, and the goal did not fail"; fail=1; }
expect "$e/mvn.log" "several libraries in $OUT/multi-header/lib (libdevreg.so, libother.so): set providerLibrary"

[ $fail = 0 ] && echo "samples: the goal works with the defaults (2 samples, 2 errors)"
exit $fail
