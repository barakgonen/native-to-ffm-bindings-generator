#!/bin/bash
# Runtime test of the safe wrapper's exception reporting: builds the provider in cases/exceptions (it throws every
# kind of C++ exception), its safe wrapper with three types mapped, and a C program that calls the wrapper for each
# kind; compares what it prints with cases/exceptions/expected.txt. Needs a JDK 25 (JAVA) and c++ / cc.
set -eu
HERE=$(cd "$(dirname "$0")" && pwd)
. "$HERE/generators.sh"
CASE=$HERE/cases/exceptions
OUT=$PLUGIN/target/exceptions-test
rm -rf "$OUT" && mkdir -p "$OUT"
build() {  # name, extra CppToC options...: the safe wrapper and the check program, run
  local w=$OUT/$1; shift
  "$JAVA" -cp "$GENERATORS" io.nativeffm.cpptoc.CppToC --include-dir "$CASE/include" --headers store.hpp --out "$w/c" --name store "$@" \
      > "$w.log" 2>&1 || { cat "$w.log"; exit 1; }
  c++ -std=c++17 -shared -fPIC -I "$CASE/include" -I "$w/c/include" -o "$w/libstore_safe.so" "$w/c/src/store_safe.cpp" \
      -L "$OUT" -lstore
  cc -std=c11 -I "$w/c/include" -o "$w/check" "$CASE/check.c" -L "$w" -lstore_safe -L "$OUT" -lstore
  LD_LIBRARY_PATH="$w:$OUT" DYLD_LIBRARY_PATH="$w:$OUT" "$w/check"
}
c++ -std=c++17 -shared -fPIC -I "$CASE/include" -o "$OUT/libstore.so" "$CASE/store.cpp"
mkdir -p "$OUT/mapped" "$OUT/unmapped"
{
  echo "with --exceptions 'vendor::Timeout=message(),vendor::Error=message(),vendor::Fault=code' --exception-headers errors.hpp:"
  build mapped --exceptions 'vendor::Timeout=message(),vendor::Error=message(),vendor::Fault=code' --exception-headers errors.hpp
  echo
  echo "without --exceptions:"
  build unmapped
} > "$OUT/actual.txt"
if [ "${1:-}" = "--update" ]; then cp "$OUT/actual.txt" "$CASE/expected.txt"; fi
if diff "$CASE/expected.txt" "$OUT/actual.txt"; then echo "exceptions: every kind reported as expected"; else exit 1; fi
