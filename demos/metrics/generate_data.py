#!/usr/bin/env python3
"""
Generate host metrics data using the metricsgenreceiver binary.

Runs metricsgenreceiver pointing at a local OTLP HTTP server (started here),
then converts each received data point into a flat NDJSON document — the same
shape the Elasticsearch OTel exporter (mapping.mode: otel) would index in
production.

The file exporter is deliberately avoided: metricsgenreceiver exits before it
flushes. The OTLP HTTP path is synchronous — the binary waits for our 200 OK
before considering each batch sent, so no data is lost on exit.

For more on metricsgenreceiver and how to generate your own datasets, see:
  https://github.com/elastic/metricsgenreceiver

The binary is downloaded automatically from GitHub Releases if it is not
already on your PATH.

Output:
  data/docs.ndjson    — one document per OTLP metric data point
  data/queries.ndjson — expected ESQL results for each param variant

Usage:
  python3 generate_data.py
"""

import gzip
import json
import os
import platform
import shutil
import statistics
import subprocess
import sys
import tarfile
import tempfile
import threading
import urllib.request
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, HTTPServer

# ── Configuration ────────────────────────────────────────────────────────────

VERSION         = "1.0.10"
SEED            = 42
SCALE           = 1           # number of simulated hosts
INTERVAL        = "10s"       # metric collection interval
START_NOW_MINUS = "2m"        # how far back to start (controls total doc count)
OTLP_PORT       = 4318        # local OTLP HTTP receiver port

# LIMIT values used in the ESQL benchmark query
PARAM_SIZES = [10]

# OTel SDK resource attribute prefixes that have no meaning in the metrics schema
_STRIP_PREFIXES = ("service.", "telemetry.", "process.")

# ── Paths ────────────────────────────────────────────────────────────────────

_HERE        = os.path.dirname(os.path.abspath(__file__))
DATA_DIR     = os.path.join(_HERE, "data")
DOCS_PATH    = os.path.join(DATA_DIR, "docs.ndjson")
QUERIES_PATH = os.path.join(DATA_DIR, "queries.ndjson")
BIN_DIR      = os.path.join(_HERE, ".bin")
BINARY_PATH  = os.path.join(BIN_DIR, "metricsgenreceiver")


# ── Binary management ─────────────────────────────────────────────────────────

def _platform():
    system  = platform.system().lower()
    machine = platform.machine().lower()
    os_name = {"darwin": "darwin", "linux": "linux"}.get(system)
    arch    = {"x86_64": "amd64", "amd64": "amd64", "arm64": "arm64", "aarch64": "arm64"}.get(machine)
    if not os_name or not arch:
        sys.exit(f"Unsupported platform: {system}/{machine}")
    return os_name, arch


def ensure_binary() -> str:
    found = shutil.which("metricsgenreceiver")
    if found:
        print(f"Using metricsgenreceiver from PATH: {found}")
        return found
    if os.path.exists(BINARY_PATH):
        print(f"Using cached binary: {BINARY_PATH}")
        return BINARY_PATH
    os_name, arch = _platform()
    filename = f"metricsgenreceiver_{os_name}_{arch}.tar.gz"
    url = f"https://github.com/elastic/metricsgenreceiver/releases/download/v{VERSION}/{filename}"
    print(f"Downloading metricsgenreceiver v{VERSION} ({os_name}/{arch})...")
    os.makedirs(BIN_DIR, exist_ok=True)
    with tempfile.NamedTemporaryFile(suffix=".tar.gz", delete=False) as tmp:
        urllib.request.urlretrieve(url, tmp.name)
        with tarfile.open(tmp.name, "r:gz") as tar:
            for member in tar.getmembers():
                if "metricsgenreceiver" in member.name and not member.isdir():
                    member.name = "metricsgenreceiver"
                    tar.extract(member, BIN_DIR, filter="data")
                    break
    os.chmod(BINARY_PATH, 0o755)
    print(f"Saved to {BINARY_PATH}")
    return BINARY_PATH


# ── Local OTLP HTTP receiver ──────────────────────────────────────────────────

def _make_handler(batches: list):
    class OTLPHandler(BaseHTTPRequestHandler):
        def do_POST(self):
            length = int(self.headers.get("Content-Length", 0))
            body   = self.rfile.read(length)

            if self.headers.get("Content-Encoding") == "gzip":
                body = gzip.decompress(body)

            content_type = self.headers.get("Content-Type", "")
            if "json" in content_type:
                try:
                    batches.append(json.loads(body))
                except json.JSONDecodeError as e:
                    print(f"JSON parse error: {e}", file=sys.stderr)
            else:
                print(f"Unexpected content-type: {content_type}", file=sys.stderr)
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.end_headers()
            self.wfile.write(b"{}")

        def log_message(self, *args):
            pass  # suppress per-request logs

    return OTLPHandler


def start_otlp_server(port: int) -> tuple[HTTPServer, list]:
    batches = []
    server  = HTTPServer(("0.0.0.0", port), _make_handler(batches))
    thread  = threading.Thread(target=server.serve_forever)
    thread.daemon = True
    thread.start()
    return server, batches


# ── OTel Collector config ─────────────────────────────────────────────────────

def otel_config(port: int) -> str:
    return f"""\
receivers:
  metricsgen:
    seed: {SEED}
    start_now_minus: {START_NOW_MINUS}
    interval: {INTERVAL}
    exit_after_end: true
    scenarios:
      - path: builtin/hostmetrics
        scale: {SCALE}

exporters:
  otlphttp:
    endpoint: http://localhost:{port}
    encoding: json

service:
  pipelines:
    metrics:
      receivers: [metricsgen]
      exporters: [otlphttp]
"""


# ── OTLP JSON → flat NDJSON ───────────────────────────────────────────────────

def _attr_value(v: dict):
    for kind in ("stringValue", "intValue", "doubleValue", "boolValue"):
        if kind in v:
            return v[kind]
    if "arrayValue" in v:
        return [_attr_value(i) for i in v["arrayValue"].get("values", [])]
    return None


def _attrs(attr_list: list) -> dict:
    return {a["key"]: _attr_value(a["value"]) for a in attr_list}


def _ns_to_ts(ns: str) -> str:
    dt = datetime.fromtimestamp(int(ns) / 1e9, tz=timezone.utc)
    return dt.strftime("%Y-%m-%dT%H:%M:%SZ")


def _data_points(metric_name: str, metric: dict, resource: dict) -> list[dict]:
    docs = []
    for kind in ("gauge", "sum"):
        if kind not in metric:
            continue
        for dp in metric[kind].get("dataPoints", []):
            doc = dict(resource)
            doc["@timestamp"] = _ns_to_ts(dp["timeUnixNano"])
            # asDouble is already a float; asInt comes as a string in OTLP JSON
            # (to preserve 64-bit precision) — convert to int so ES maps it as long
            if "asDouble" in dp:
                doc[metric_name] = dp["asDouble"]
            elif "asInt" in dp:
                doc[metric_name] = int(dp["asInt"])
            doc.update(_attrs(dp.get("attributes", [])))
            docs.append(doc)
    return docs


def parse_batches(batches: list) -> list[dict]:
    docs = []
    for batch in batches:
        for rm in batch.get("resourceMetrics", []):
            resource = _attrs(rm.get("resource", {}).get("attributes", []))
            # Strip OTel SDK bookkeeping fields (service.*, telemetry.*, process.*)
            resource = {k: v for k, v in resource.items()
                        if not any(k.startswith(p) for p in _STRIP_PREFIXES)}
            for sm in rm.get("scopeMetrics", []):
                for metric in sm.get("metrics", []):
                    docs.extend(_data_points(metric["name"], metric, resource))
    return _merge_tsds_docs(docs)


def _merge_tsds_docs(docs: list[dict]) -> list[dict]:
    """Merge documents that share the same TSDS dimension key.

    TSDS computes _id from all dimension (string) fields + @timestamp.  When
    two metrics share the same set of string fields (e.g. system.cpu.load_average
    and system.cpu.logical.count both have only host.name/arch/os.type), TSDS
    would overwrite the earlier document.  We pre-merge such documents so that
    every unique (string_fields + @timestamp) combination becomes a single doc
    containing all its metric values.
    """
    import collections
    groups: dict = collections.OrderedDict()
    for doc in docs:
        # Split into dimension key (string/array fields + @timestamp) vs metric values
        dim: dict = {}
        metrics: dict = {}
        for k, v in doc.items():
            if isinstance(v, (int, float)) and not isinstance(v, bool):
                metrics[k] = v
            else:
                dim[k] = v
        # Build a stable hashable key from the dimension fields
        key = json.dumps(dim, sort_keys=True, default=str)
        if key not in groups:
            groups[key] = dict(dim)
        groups[key].update(metrics)
    return list(groups.values())


def _compute_expected_aggregates(docs: list[dict]) -> dict:
    """Pre-compute expected aggregate values for the load query.

    Matches the logic in demo-metrics-load.esql:
      WHERE system.cpu.load_average.1m IS NOT NULL
      STATS avg/max of load averages BY host.name
    """
    load_1m  = [d["system.cpu.load_average.1m"]  for d in docs if "system.cpu.load_average.1m"  in d]
    load_5m  = [d["system.cpu.load_average.5m"]  for d in docs if "system.cpu.load_average.5m"  in d]
    load_15m = [d["system.cpu.load_average.15m"] for d in docs if "system.cpu.load_average.15m" in d]

    agg = {}
    if load_1m:
        agg["expected_avg_load_1m"] = statistics.mean(load_1m)
        agg["expected_max_load_1m"] = max(load_1m)
    if load_5m:
        agg["expected_avg_load_5m"] = statistics.mean(load_5m)
    if load_15m:
        agg["expected_avg_load_15m"] = statistics.mean(load_15m)
    return agg


# ── Main ─────────────────────────────────────────────────────────────────────

def main():
    os.makedirs(DATA_DIR, exist_ok=True)
    binary = ensure_binary()

    server, batches = start_otlp_server(OTLP_PORT)
    print(f"OTLP receiver listening on :{OTLP_PORT}")

    with tempfile.TemporaryDirectory() as tmp:
        config_path = os.path.join(tmp, "otelcol.yaml")
        with open(config_path, "w") as f:
            f.write(otel_config(OTLP_PORT))

        print(f"Running metricsgenreceiver (scale={SCALE}, interval={INTERVAL}, window={START_NOW_MINUS})...")
        result = subprocess.run([binary, "--config", config_path], capture_output=True, text=True)

        if result.stderr:
            print("--- metricsgenreceiver stderr ---")
            print(result.stderr)

        if result.returncode != 0:
            server.shutdown()
            sys.exit(f"metricsgenreceiver exited with code {result.returncode}")

    server.shutdown()

    print(f"Received {len(batches)} OTLP batch(es), parsing...")
    docs = parse_batches(batches)

    if not docs:
        sys.exit("No documents parsed — check the metricsgenreceiver output above")

    print(f"Writing {len(docs):,} docs to {DOCS_PATH}...")
    with open(DOCS_PATH, "w") as f:
        for doc in docs:
            f.write(json.dumps(doc) + "\n")

    expected = _compute_expected_aggregates(docs)
    with open(QUERIES_PATH, "w") as f:
        for size in PARAM_SIZES:
            f.write(json.dumps({"size": size, **expected}) + "\n")

    meta_keys    = {"@timestamp", "host.name", "host.ip", "host.mac", "host.arch", "os.type"}
    metric_names = sorted({k for doc in docs for k in doc if k not in meta_keys})

    print(f"\nDone.")
    print(f"  docs:    {len(docs):,}")
    print(f"  metrics: {len(metric_names)}")
    print(f"  hosts:   {len({doc.get('host.name') for doc in docs})}")
    print(f"\nSample doc:")
    print(json.dumps(docs[0], indent=2))
    print(f"\nMetric fields:")
    for m in metric_names:
        print(f"  {m}")


if __name__ == "__main__":
    main()
