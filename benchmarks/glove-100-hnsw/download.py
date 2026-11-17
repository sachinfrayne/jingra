#!/usr/bin/env python3
"""Downloads a file atomically and verifies its size against the server's Content-Length, so a
dropped connection can never leave a truncated dataset behind.

usage: download.py <url> <destination>
"""
import os
import sys
import urllib.request


def main() -> None:
    url, dest = sys.argv[1], sys.argv[2]
    tmp = dest + ".part"
    os.makedirs(os.path.dirname(dest) or ".", exist_ok=True)
    print(f"downloading {url}", flush=True)
    # ann-benchmarks.com answers 403 to urllib's default "Python-urllib" User-Agent.
    request = urllib.request.Request(url, headers={"User-Agent": "jingra-benchmark"})
    with urllib.request.urlopen(request) as response, open(tmp, "wb") as out:
        expected = int(response.headers.get("Content-Length", "-1"))
        written = 0
        while chunk := response.read(1 << 20):
            out.write(chunk)
            written += len(chunk)
    if expected >= 0 and written != expected:
        os.remove(tmp)
        sys.exit(f"download truncated: got {written} bytes, expected {expected}")
    os.replace(tmp, dest)
    print(f"saved {dest} ({written} bytes)", flush=True)


if __name__ == "__main__":
    main()
