#!/usr/bin/env bash
set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
JAR="$SCRIPT_DIR/target/chess-bot.jar"

# Build if JAR is missing or sources are newer
if [ ! -f "$JAR" ] || find "$SCRIPT_DIR/src" -newer "$JAR" | grep -q .; then
    echo "Building chess-bot..."
    mvn -q -f "$SCRIPT_DIR/pom.xml" package -DskipTests
    echo "Build complete."
fi

exec java -jar "$JAR" "$@"
