# Sourced by golden.sh and exceptions.sh: compiles the generators (src/main/java, without the Maven goal) with the
# JDK in JAVA, into target/generators, and sets GENERATORS (the class path) and JAVA.
JAVA=${JAVA:-java}
case $JAVA in */*) JAVAC=${JAVA%/*}/javac ;; *) JAVAC=javac ;; esac
v=$("$JAVA" -XshowSettings:properties -version 2>&1 | sed -n 's/^ *java.specification.version = //p')
[ "${v:-0}" -ge 25 ] 2>/dev/null || { echo "$0: $JAVA is Java ${v:-?}: the generators need JDK 25 (set JAVA)"; exit 2; }
PLUGIN=$(cd "$(dirname "$0")/../.." && pwd)
GENERATORS=$PLUGIN/target/generators
rm -rf "$GENERATORS" && mkdir -p "$GENERATORS"
"$JAVAC" -proc:none -d "$GENERATORS" $(find "$PLUGIN/src/main/java/io/nativeffm/cpptoc" "$PLUGIN/src/main/java/io/nativeffm/recordgen" -name '*.java') \
    2>&1 | grep -v '^Picked up' || true
cp -r "$PLUGIN/src/main/resources/." "$GENERATORS/"
[ -f "$GENERATORS/io/nativeffm/cpptoc/CppToC.class" ] || { echo "$0: the generators do not compile"; exit 2; }
