#!/bin/bash
# Compiles with plain javac (no Maven needed). Usage: ./build.sh
set -e
cd "$(dirname "$0")"
rm -rf out && mkdir -p out/classes
find src/main/java -name '*.java' > out/sources.txt
javac --release 17 -encoding UTF-8 -Xlint:-options -d out/classes @out/sources.txt
cp -r src/main/resources/* out/classes/ 2>/dev/null || true
echo "BUILD OK"
