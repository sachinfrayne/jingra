#!/usr/bin/env bash
set -euo pipefail

ERRORS=0

for version_file in engine-versions/.*; do
    [[ "$version_file" == "engine-versions/." || "$version_file" == "engine-versions/.." ]] && continue
    [[ ! -f "$version_file" ]] && continue

    engine_name=$(basename "$version_file" | sed 's/^\.//')
    expected_version=$(cat "$version_file")
    pom_version=$(grep "<${engine_name}.version>" pom.xml | grep -oE '[0-9]+\.[0-9]+\.[0-9]+' | head -1)

    if [ -z "$pom_version" ]; then
        echo "❌ ${engine_name} version not found in pom.xml"
        ERRORS=1
        continue
    fi

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
