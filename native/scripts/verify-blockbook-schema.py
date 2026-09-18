#!/usr/bin/env python3
"""
Check the app's Blockbook models against a live Blockbook server.

Why this exists
---------------
The Bitcoin Core models in this app were transcribed from Core's own source and
are checked by `verify-rpc-models.py`. The Blockbook models could not be: the
session that wrote them had no network route to any Blockbook host, so they were
written from the documented v2 schema.

Documented and actual are not the same thing. Blockbook deployments differ by
version, by coin, and by whatever the operator has patched. This script closes
that gap by asking the real server and diffing what comes back.

It reports three things:

  NOT MODELLED   the server sends a field the app ignores. Usually harmless,
                 sometimes a feature you'd want.
  MISSING        the app expects a field the server does not send. The Kotlin
                 default silently fills in — a zero balance, an empty list —
                 and no error is raised anywhere. This is the dangerous one.
  TYPE MISMATCH  the field exists but is the wrong shape. In particular this
                 checks that amounts really are decimal *strings* of satoshis,
                 which the whole exact-arithmetic design depends on.

Usage
-----
    ./verify-blockbook-schema.py \\
        --url https://bitcoin.atomicwallet.io/api/v2 \\
        --address 1Be6LLAEndprdWKiH6YM62setFQRXJzfha

Add --json to dump the raw responses for inspection.

Uses only the Python standard library, so it runs anywhere without a pip
install — including on a machine you would rather not install packages on.
"""

from __future__ import annotations

import argparse
import json
import re
import sys
import urllib.error
import urllib.request
from pathlib import Path

# kotlin data class -> (endpoint template, where the object sits in the response)
#
# `path` is a list of keys to walk into the response before comparing, or []
# for the top-level object. `list_of` means the response is an array and we
# compare against its first element.
ENDPOINTS = {
    "BlockbookStatus":      dict(path="", keys=[], list_of=False),
    "BlockbookServerInfo":  dict(path="", keys=["blockbook"], list_of=False),
    "BlockbookBackendInfo": dict(path="", keys=["backend"], list_of=False),
    "BlockbookAddress":     dict(path="/address/{address}?details=txs&pageSize=5", keys=[], list_of=False),
    "BlockbookTx":          dict(path="/address/{address}?details=txs&pageSize=5", keys=["transactions"], list_of=True),
    "BlockbookVin":         dict(path="/address/{address}?details=txs&pageSize=5", keys=["transactions", 0, "vin"], list_of=True),
    "BlockbookVout":        dict(path="/address/{address}?details=txs&pageSize=5", keys=["transactions", 0, "vout"], list_of=True),
    "BlockbookUtxo":        dict(path="/utxo/{address}", keys=[], list_of=True),
    "BlockbookFeeResult":   dict(path="/estimatefee/3", keys=[], list_of=False),
}

# Fields that must arrive as decimal strings of satoshis. If any of these comes
# back as a JSON number, `SatsStringSerializer` still copes, but the assumption
# in the model comments is wrong and the docs should be corrected.
SATOSHI_STRING_FIELDS = {
    "balance", "totalReceived", "totalSent", "unconfirmedBalance",
    "value", "valueIn", "fees",
}

SERIAL_NAME = re.compile(r'@SerialName\("([^"]+)"\)')


def kotlin_fields(model_file: Path, class_name: str):
    """Wire-level field names for one data class."""
    text = model_file.read_text(encoding="utf-8")
    m = re.search(r"^data class " + re.escape(class_name) + r"\(", text, re.M)
    if not m:
        return None
    start = text.index("(", m.start())
    depth, i = 0, start
    while i < len(text):
        if text[i] == "(":
            depth += 1
        elif text[i] == ")":
            depth -= 1
            if depth == 0:
                break
        i += 1
    body = text[start:i]

    fields = set()
    for line in body.split("\n"):
        sn = SERIAL_NAME.search(line)
        if sn:
            fields.add(sn.group(1))
            continue
        pm = re.search(r"\bva[lr]\s+(\w+)\s*:", line)
        if pm:
            fields.add(pm.group(1))
    return fields


def fetch(url: str, timeout: int):
    req = urllib.request.Request(url, headers={"User-Agent": "bitcoin-core-node-schema-check/1"})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return json.loads(r.read().decode("utf-8"))


def walk(doc, keys):
    cur = doc
    for k in keys:
        if cur is None:
            return None
        if isinstance(k, int):
            if not isinstance(cur, list) or len(cur) <= k:
                return None
            cur = cur[k]
        else:
            if not isinstance(cur, dict) or k not in cur:
                return None
            cur = cur[k]
    return cur


def main() -> int:
    ap = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--url", required=True, help="Blockbook base URL ending in /api/v2")
    ap.add_argument("--address", required=True, help="a Bitcoin address with some history")
    ap.add_argument("--models", type=Path, default=None)
    ap.add_argument("--timeout", type=int, default=30)
    ap.add_argument("--json", action="store_true", help="dump raw responses")
    ap.add_argument("--strict", action="store_true", help="exit non-zero on any MISSING field")
    args = ap.parse_args()

    base = args.url.rstrip("/")
    if not base.endswith("/api/v2"):
        base = base.rstrip("/") + "/api/v2"

    model_file = args.models or (
        Path(__file__).resolve().parents[2]
        / "app/src/main/java/com/solitech/bitcoincorenode/data/explorer/BlockbookModels.kt"
    )
    if not model_file.exists():
        print(f"error: model file not found: {model_file}", file=sys.stderr)
        return 2

    print(f"Server : {base}")
    print(f"Address: {args.address}")
    print(f"Models : {model_file}\n")

    # Fetch each distinct endpoint once, not once per class.
    cache = {}
    problems = 0

    for cls, spec in ENDPOINTS.items():
        path = spec["path"].replace("{address}", args.address)
        url = base + path

        if url not in cache:
            try:
                cache[url] = fetch(url, args.timeout)
            except urllib.error.HTTPError as e:
                cache[url] = {"__http_error__": e.code}
            except Exception as e:  # noqa: BLE001
                cache[url] = {"__error__": str(e)}

        doc = cache[url]
        if isinstance(doc, dict) and "__http_error__" in doc:
            print(f"  ??  {cls:<22} HTTP {doc['__http_error__']} from {path or '/'}")
            problems += 1
            continue
        if isinstance(doc, dict) and "__error__" in doc:
            print(f"  ??  {cls:<22} {doc['__error__']}")
            problems += 1
            continue

        node = walk(doc, spec["keys"])
        if spec["list_of"]:
            if not isinstance(node, list) or not node:
                print(f"  --  {cls:<22} no sample available "
                      f"(address has no data at {'/'.join(str(k) for k in spec['keys'])})")
                continue
            node = node[0]
        if not isinstance(node, dict):
            print(f"  ??  {cls:<22} expected an object at "
                  f"{'/'.join(str(k) for k in spec['keys']) or 'root'}, got {type(node).__name__}")
            problems += 1
            continue

        expected = kotlin_fields(model_file, cls)
        if expected is None:
            print(f"  ??  {cls:<22} no such data class in {model_file.name}")
            problems += 1
            continue

        actual = set(node.keys())
        missing = sorted(expected - actual)
        extra = sorted(actual - expected)

        # Type check on the amount fields -- the assumption the design rests on.
        type_issues = []
        for f in sorted(SATOSHI_STRING_FIELDS & actual):
            v = node[f]
            if isinstance(v, (int, float)):
                type_issues.append(f"{f} is a JSON number, not a string")
            elif isinstance(v, str) and not re.fullmatch(r"-?\d+", v):
                type_issues.append(f"{f}={v!r} is not an integer string of satoshis")

        if not missing and not type_issues:
            print(f"  OK  {cls:<22} {len(expected & actual)}/{len(expected)} modelled fields present")
        else:
            if missing:
                print(f"  !!  {cls:<22} MISSING from server: {', '.join(missing)}")
                problems += 1
            for t in type_issues:
                print(f"  !!  {cls:<22} TYPE: {t}")
                problems += 1

        if extra:
            shown = ", ".join(extra[:10]) + (" ..." if len(extra) > 10 else "")
            print(f"      {'':<22} not modelled ({len(extra)}): {shown}")

        if args.json:
            print(json.dumps(node, indent=2)[:1500])

    print()
    if problems:
        print(f"{problems} problem(s).")
        print()
        print("A MISSING field is the one to act on: the Kotlin default fills in silently,")
        print("so a balance reads zero or a list comes back empty with no error anywhere.")
        print("Fix BlockbookModels.kt to match what this server actually sends.")
        return 1 if args.strict else 0

    print("Every modelled field is present and every amount is an integer string.")
    print("BlockbookModels.kt matches this server.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
