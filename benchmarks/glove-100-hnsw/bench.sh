#!/usr/bin/env bash
# Runs inside the jingra container (or natively, see README): load + bench every build, then
# report. Expects configs already generated into $WORK by prepare.sh.
set -euo pipefail

JINGRA="${JINGRA:-/app/jingra}"
WORK="${WORK:-/work}"
BUILDS="${BUILDS:-8:64,16:100,32:200,48:400}"
export QDRANT_URL="${QDRANT_URL:-http://127.0.0.1:6334}"

host_port="${QDRANT_URL#*://}"
qdrant_host="${host_port%%:*}"

# Plain bash TCP + HTTP so no curl is needed in this image.
qdrant_ready() (
  exec 3<>"/dev/tcp/$qdrant_host/6333" || exit 1
  printf 'GET /healthz HTTP/1.0\r\n\r\n' >&3
  grep -q " 200 " <&3
)

echo "waiting for Qdrant at $qdrant_host ..."
deadline=$((SECONDS + 120))
until qdrant_ready 2>/dev/null; do
  [ "$SECONDS" -lt "$deadline" ] || { echo "Qdrant did not become ready within 120s" >&2; exit 1; }
  sleep 2
done

cd "$WORK"
rm -rf results report   # a stale run under the same run_id would otherwise mix into the report
for build in ${BUILDS//,/ }; do
  m="${build%%:*}"
  efc="${build##*:}"
  cfg="config-m${m}_efc${efc}.yaml"
  echo "=== build m=$m ef_construct=$efc: load ==="
  "$JINGRA" load "$cfg"
  echo "=== build m=$m ef_construct=$efc: bench ==="
  "$JINGRA" bench "$cfg"
done

echo "=== report ==="
"$JINGRA" report config-report.yaml
echo "Done. Plots and CSV: $WORK/report"
