#!/bin/bash
# Starts the UEMS desktop application. Needs uems.properties (copy from uems.properties.example).
# Works after "./build.sh" (plain javac) or "mvn package". Usage: ./start.sh            -> application
#                                                                  ./start.sh setup [--reset] [--demo] -> database setup
cd "$(dirname "$0")"
CP="out/classes"
[ -d target/classes ] && CP="target/classes:target/lib/*"
JDBC=$(ls /usr/share/java/postgresql*.jar ~/.m2/repository/org/postgresql/postgresql/*/postgresql-*.jar lib/postgresql*.jar 2>/dev/null | head -1)
[ -n "$JDBC" ] && CP="$CP:$JDBC"
if [ "$1" = "setup" ]; then shift; exec java -Dfile.encoding=UTF-8 -cp "$CP" uems.DbSetup --config uems.properties "$@"; fi
exec java -Dfile.encoding=UTF-8 -cp "$CP" uems.UemsApp uems.properties
