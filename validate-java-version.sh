#!/usr/bin/env bash
# TODO: add this to Makefile and delete this script if it will not make the makefile to complex
set -euo pipefail

# Read Java version from .java-version (single source of truth)
EXPECTED_VERSION=$(cat .java-version)

# Extract version from pom.xml
POM_VERSION=$(grep -A1 '<maven.compiler.source>' pom.xml | grep -oE '[0-9]+' | head -1)

if [ "$POM_VERSION" != "$EXPECTED_VERSION" ]; then
    echo "❌ Version mismatch!"
    echo "   .java-version: $EXPECTED_VERSION"
    echo "   pom.xml:       $POM_VERSION"
    echo ""
    echo "   Update pom.xml to match .java-version:"
    echo "   - maven.compiler.source"
    echo "   - maven.compiler.target"
    echo "   - requireJavaVersion range"
    exit 1
fi

echo "✅ Java version consistent: $EXPECTED_VERSION"
