#!/bin/bash
# Compiles and runs the JUnit 5 tests with plain javac/java. Integration tests need the PostgreSQL test database (see README).
set -e
cd "$(dirname "$0")"
./build.sh
JDBC=${JDBC:-$(ls /usr/share/java/postgresql*.jar ~/.m2/repository/org/postgresql/postgresql/*/postgresql-*.jar lib/postgresql*.jar 2>/dev/null | head -1)}
JUNIT=${JUNIT:-$(ls /usr/share/java/junit-platform-console-standalone.jar lib/junit-platform-console-standalone*.jar 2>/dev/null | head -1)}
rm -rf out/test-classes && mkdir -p out/test-classes
find src/test/java -name '*.java' > out/test-sources.txt
javac --release 17 -encoding UTF-8 -Xlint:-options -cp "out/classes:$JUNIT:$JDBC" -d out/test-classes @out/test-sources.txt
java -Dfile.encoding=UTF-8 -jar "$JUNIT" -cp "out/classes:out/test-classes:$JDBC" --scan-classpath out/test-classes --include-classname=".*(Test|IT)" --details=tree --disable-banner --disable-ansi-colors "$@"
