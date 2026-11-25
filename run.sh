#!/bin/sh
# Branch. - build and start. Needs a JDK (17 or newer).
cd "$(dirname "$0")" || exit 1
command -v javac >/dev/null 2>&1 || { echo "Please install a JDK (version 17 or newer) first."; exit 1; }
mkdir -p out && javac -d out src/branch/*.java || exit 1
exec java -cp "out:lib/sqlite-jdbc.jar" branch.Main
