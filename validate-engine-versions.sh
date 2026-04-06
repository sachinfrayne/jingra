#!/usr/bin/env bash
# TODO: add this to Makefile and delete this script if it will not make the makefile to complex
set -euo pipefail

ERRORS=0

# Loop through all version files in engine-versions/
for version_file in engine-versions/.*; do
    # Skip . and .. directories
    [[ "$version_file" == "engine-versions/." || "$version_file" == "engine-versions/.." ]] && continue

    # Skip if not a file
    [[ ! -f "$version_file" ]] && continue

    # Extract engine name (remove ./ and leading dot)
    engine_name=$(basename "$version_file" | sed 's/^\.//')

    # Read expected version from file
    expected_version=$(cat "$version_file")

    # Extract version from pom.xml (look for <engine_name.version>)
    pom_version=$(grep "<${engine_name}.version>" pom.xml | grep -oE '[0-9]+\.[0-9]+\.[0-9]+' | head -1)

    if [ -z "$pom_version" ]; then
        echo "❌ ${engine_name} version not found in pom.xml"
        ERRORS=1
        continue
    fi

    # Compare versions
    if [ "$pom_version" != "$expected_version" ]; then
        echo "❌ ${engine_name} version mismatch!"
        echo "   engine-versions/.${engine_name}: $expected_version"
        echo "   pom.xml:                         $pom_version"
        ERRORS=1
    fi
done

if [ $ERRORS -eq 0 ]; then
    echo "✅ Engine versions consistent:"
    for version_file in engine-versions/.*; do
        [[ "$version_file" == "engine-versions/." || "$version_file" == "engine-versions/.." ]] && continue
        [[ ! -f "$version_file" ]] && continue
        engine_name=$(basename "$version_file" | sed 's/^\.//')
        version=$(cat "$version_file")
        printf "   %-15s %s\n" "${engine_name}:" "$version"
    done
else
    echo ""
    echo "   Update pom.xml to match engine-versions/ files"
    exit 1
fi
