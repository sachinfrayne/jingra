#!/usr/bin/env bash
set -euo pipefail

ERRORS=0
UPDATED=0

# Cross-platform in-place sed: macOS needs sed -i '', Linux needs sed -i
if [[ "$(uname)" == "Darwin" ]]; then
    sedi() { sed -i '' "$@"; }
else
    sedi() { sed -i "$@"; }
fi

# Use GITHUB_TOKEN if set to avoid rate limiting
CURL_ARGS=(-sf)
if [[ -n "${GITHUB_TOKEN:-}" ]]; then
    CURL_ARGS+=(-H "Authorization: Bearer ${GITHUB_TOKEN}")
fi

fetch_maven_latest() {
    local group="$1" artifact="$2"
    local group_path="${group//.//}"
    local url="https://repo.maven.apache.org/maven2/${group_path}/${artifact}/maven-metadata.xml"
    curl -sf "$url" | python3 -c "
import sys, xml.etree.ElementTree as ET
root = ET.parse(sys.stdin).getroot()
release = root.findtext('versioning/release') or ''
print(release)
" 2>/dev/null || true
}

fetch_github_latest() {
    local github_url="$1"
    local owner_repo
    owner_repo=$(echo "$github_url" | sed 's|https://github.com/||')
    local api_url="https://api.github.com/repos/${owner_repo}/releases/latest"
    local tag
    tag=$(curl "${CURL_ARGS[@]}" "$api_url" \
        | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('tag_name',''))" \
        2>/dev/null || true)
    # Strip leading non-numeric prefix: v1.2.3 → 1.2.3, mimir-3.1.0 → 3.1.0
    echo "$tag" | sed 's/^[^0-9]*//'
}

for version_file in engine-versions/.*; do
    [[ "$version_file" == "engine-versions/." || "$version_file" == "engine-versions/.." ]] && continue
    [[ ! -f "$version_file" ]] && continue

    engine_name=$(basename "$version_file" | sed 's/^\.//')
    current_version=$(cat "$version_file" | tr -d '[:space:]')

    details_file="engine-details/.${engine_name}"
    if [[ ! -f "$details_file" ]]; then
        echo "⚠️  ${engine_name}: no engine-details file at ${details_file}, skipping"
        continue
    fi

    maven_group=$(grep "^maven_group=" "$details_file" | cut -d= -f2- | tr -d '[:space:]' || true)
    maven_artifact=$(grep "^maven_artifact=" "$details_file" | cut -d= -f2- | tr -d '[:space:]' || true)

    if [[ -n "$maven_group" && -n "$maven_artifact" ]]; then
        latest_version=$(fetch_maven_latest "$maven_group" "$maven_artifact")
        source_label="Maven Central"
    else
        github_url=$(grep "^github=" "$details_file" | cut -d= -f2- | tr -d '[:space:]')
        if [[ -z "$github_url" ]]; then
            echo "⚠️  ${engine_name}: no github= or maven_group= in ${details_file}, skipping"
            continue
        fi
        latest_version=$(fetch_github_latest "$github_url")
        source_label="GitHub"
    fi

    if [[ -z "$latest_version" ]]; then
        echo "❌  ${engine_name}: failed to fetch latest version from ${source_label}"
        ERRORS=$((ERRORS + 1))
        continue
    fi

    if [[ "$current_version" == "$latest_version" ]]; then
        printf "  %-15s %s  (already latest, via %s)\n" "${engine_name}:" "$current_version" "$source_label"
    else
        printf "  %-15s %s  →  %s  (via %s)\n" "${engine_name}:" "$current_version" "$latest_version" "$source_label"
        printf '%s' "$latest_version" > "$version_file"
        sedi "s|<${engine_name}\.version>${current_version}</${engine_name}\.version>|<${engine_name}.version>${latest_version}</${engine_name}.version>|g" pom.xml
        UPDATED=$((UPDATED + 1))
    fi
done

echo ""
if [[ $ERRORS -ne 0 ]]; then
    echo "❌  ${ERRORS} engine(s) could not be checked — see errors above"
    exit 1
fi

if [[ $UPDATED -eq 0 ]]; then
    echo "✅  All engines already at latest versions"
else
    echo "✅  Updated ${UPDATED} engine(s)"
fi
