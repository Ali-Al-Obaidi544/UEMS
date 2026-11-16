#!/bin/bash
# Runs a UEMS main class with plain java. Usage: ./run.sh <main-class> [args]   e.g. ./run.sh uems.DbSetup --reset --demo
cd "$(dirname "$0")"
JDBC=${JDBC:-$(ls /usr/share/java/postgresql*.jar ~/.m2/repository/org/postgresql/postgresql/*/postgresql-*.jar lib/postgresql*.jar 2>/dev/null | head -1)}
exec java -Dfile.encoding=UTF-8 -cp "out/classes:$JDBC" "$@"
