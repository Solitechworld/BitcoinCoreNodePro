#!/usr/bin/env python3
"""
Diff the app's Kotlin RPC models against Bitcoin Core's own RPCResult
declarations.

Why this exists
---------------
Every field name in core/model/*.kt is a promise about what a Bitcoin Core node
will send back. Promises rot. Core adds fields, renames them, and occasionally
removes them outright -- 30.x dropped `balance`, `unconfirmed_balance` and
`immature_balance` from getwalletinfo, and any app still expecting them silently
reads zero and shows the user an empty wallet.

A silently-wrong field is worse than a crash: nothing throws, a data class
default fills in, and a screen displays a confidently incorrect number.

So instead of trusting a transcription, check it. This parses the RPCResult
declarations straight out of the Core source tree and reports:

  MISSING IN CORE  -- we read a field the node no longer sends. Broken now.
  NOT MODELLED     -- the node sends something we ignore. Usually fine, but
                      worth a look after an upgrade; it is how new features
                      announce themselves.

Usage
-----
    ./verify-rpc-models.py --core /path/to/bitcoin-30.3
    ./verify-rpc-models.py --core ~/bitcoin-30.3 --strict   # exit 1 on drift

Run it after every Core upgrade, and in CI if you have one.
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

# kotlin data class -> (core source file, RPCHelpMan function, how far to read)
#
# `span` is how many characters after the function name to scan. RPCResult
# blocks are declarative and self-contained; the spans below cover each one
# without bleeding into the next function.
MAPPING = {
    "BlockchainInfo": ("src/rpc/blockchain.cpp", "RPCHelpMan getblockchaininfo()", 4200),
    "ChainStates":    ("src/rpc/blockchain.cpp", "RPCHelpMan getchainstates()", 2500),
    "MempoolInfo":    ("src/rpc/mempool.cpp",    "RPCHelpMan getmempoolinfo()", 3000),
    "NetworkInfo":    ("src/rpc/net.cpp",        "RPCHelpMan getnetworkinfo()", 4000),
    "PeerInfo":       ("src/rpc/net.cpp",        "RPCHelpMan getpeerinfo()", 9000),
    "WalletInfo":     ("src/wallet/rpc/wallet.cpp", "RPCHelpMan getwalletinfo()", 5000),
    "Utxo":           ("src/wallet/rpc/coins.cpp",  "RPCHelpMan listunspent()", 5000),
    "AddressInfo":    ("src/wallet/rpc/addresses.cpp", "RPCHelpMan getaddressinfo()", 6000),
    "DescriptorEntry":("src/wallet/rpc/backup.cpp",   "RPCHelpMan listdescriptors()", 3000),
    "PsbtAnalysis":   ("src/rpc/rawtransaction.cpp",  "RPCHelpMan analyzepsbt()", 4000),
    "WalletTx":       ("src/wallet/rpc/transactions.cpp", "TransactionDescriptionString", 6000),
}

# Fields we model but Core nests one level deeper, or that come from a
# different RPC than the one mapped above. Listing them here is a deliberate,
# reviewed exemption -- not a way to silence a real mismatch.
KNOWN_EXEMPT = {
    "WalletTx": {"address", "category", "amount", "label", "vout", "fee", "abandoned", "hex"},
    "PsbtAnalysis": {"inputs"},
    "ChainStates": {"chainstates"},
    "NetworkInfo": {"networks", "localaddresses"},
    "WalletInfo": {"scanning", "flags"},
    "Balances": {"mine", "watchonly"},
}

# Fields that exist in some Core versions and not others, with the first
# version that sends them. A model may legitimately declare these while
# targeting an older Core -- they are nullable and simply never arrive. Without
# this table the checker reports them as MISSING, its most serious category,
# and a tool that cries wolf on a benign case is a tool people stop reading.
#
# Only add an entry here when the Kotlin field is nullable AND nothing depends
# on it being present. Anything else is a real defect and should stay loud.
VERSION_CONDITIONAL = {
    # class      field      since Core
    ("BlockchainInfo", "bits"): 29,
    ("BlockchainInfo", "target"): 29,
}

RESULT_FIELD = re.compile(r'\{RPCResult::Type::(\w+),\s*"([^"]*)"')
SERIAL_NAME = re.compile(r'@SerialName\("([^"]+)"\)')


def core_fields(core_root: Path, rel_path: str, fn: str, span: int):
    path = core_root / rel_path
    if not path.exists():
        return None
    text = path.read_text(encoding="utf-8", errors="replace")
    idx = text.find(fn)
    if idx < 0:
        return None
    segment = text[idx:idx + span]
    return {name for _t, name in RESULT_FIELD.findall(segment) if name and " " not in name}


def kotlin_fields(model_dir: Path, class_name: str):
    """Wire-level names for one data class: @SerialName if present, else the property name."""
    for kt in sorted(model_dir.glob("*.kt")):
        text = kt.read_text(encoding="utf-8")
        m = re.search(r'^data class ' + re.escape(class_name) + r'\(', text, re.M)
        if not m:
            continue
        # Walk from the opening paren to its match, so nested generics and
        # default values containing parentheses don't end the block early.
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
            pm = re.search(r'\bva[lr]\s+(\w+)\s*:', line)
            if pm:
                fields.add(pm.group(1))
        return fields
    return None


def detect_core_version(core_root: Path):
    """Major version of the Core tree, from either build system."""
    cm = core_root / "CMakeLists.txt"
    if cm.exists():
        text = cm.read_text(encoding="utf-8", errors="replace")
        # Anchor on the project() call. A bare /VERSION\s+(\d+)\./ matches
        # `cmake_minimum_required(VERSION 3.22)` first and reports Core 30 as
        # "major 3" -- which would silently mark 29+ fields as expected-absent
        # on a modern tree and suppress genuine findings.
        # Core 29+ writes the project version as CMake variables:
        #     project(BitcoinCore VERSION ${CLIENT_VERSION_MAJOR}. ...)
        # so read the variable, not the project() line.
        m = re.search(r"set\s*\(\s*CLIENT_VERSION_MAJOR\s+(\d+)", text)
        if m:
            return int(m.group(1))
        # Literal form, anchored on project() so we do not pick up
        # cmake_minimum_required(VERSION 3.22) and report Core 30 as "major 3".
        m = re.search(r"project\s*\(\s*\w+\s+VERSION\s+(\d+)\.", text)
        if m:
            return int(m.group(1))
    ca = core_root / "configure.ac"
    if ca.exists():
        m = re.search(r"define\(_CLIENT_VERSION_MAJOR,\s*(\d+)\)",
                      ca.read_text(encoding="utf-8", errors="replace"))
        if m:
            return int(m.group(1))
    return None


def main() -> int:
    ap = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--core", required=True, type=Path, help="Bitcoin Core source root")
    ap.add_argument("--models", type=Path, default=None, help="core/model directory")
    ap.add_argument("--strict", action="store_true",
                    help="exit non-zero if any field we read is missing from Core")
    args = ap.parse_args()

    model_dir = args.models or (
        Path(__file__).resolve().parents[2]
        / "app/src/main/java/com/solitech/bitcoincorenode/core/model"
    )
    if not model_dir.is_dir():
        print("error: model directory not found: %s" % model_dir, file=sys.stderr)
        return 2
    if not (args.core / "src/rpc/blockchain.cpp").exists():
        print("error: does not look like a Bitcoin Core tree: %s" % args.core, file=sys.stderr)
        return 2

    broken = 0
    core_major = detect_core_version(args.core)
    print("Core tree : %s%s" % (args.core,
          " (major %d)" % core_major if core_major else " (version undetermined)"))
    print("Models    : %s\n" % model_dir)

    for cls, (rel, fn, span) in sorted(MAPPING.items()):
        cf = core_fields(args.core, rel, fn, span)
        kf = kotlin_fields(model_dir, cls)

        if cf is None:
            print("  ??  %-18s could not locate %s in %s" % (cls, fn, rel))
            broken += 1
            continue
        if kf is None:
            print("  ??  %-18s no such data class in the model directory" % cls)
            broken += 1
            continue

        exempt = KNOWN_EXEMPT.get(cls, set())
        missing = sorted(kf - cf - exempt)
        extra = sorted(cf - kf)

        # Split genuine defects from fields this Core version simply predates.
        expected_absent = []
        if core_major is not None:
            still_missing = []
            for f in missing:
                since = VERSION_CONDITIONAL.get((cls, f))
                if since is not None and core_major < since:
                    expected_absent.append((f, since))
                else:
                    still_missing.append(f)
            missing = still_missing

        if not missing:
            note = ""
            if expected_absent:
                note = "  (+%d not in this version: %s)" % (
                    len(expected_absent),
                    ", ".join("%s needs %d+" % (f, v) for f, v in expected_absent))
            print("  OK  %-18s %d fields modelled%s" % (cls, len(kf), note))
        else:
            print("  !!  %-18s MISSING IN CORE: %s" % (cls, ", ".join(missing)))
            broken += 1
        if extra:
            shown = ", ".join(extra[:12]) + (" ..." if len(extra) > 12 else "")
            print("      %-18s not modelled (%d): %s" % ("", len(extra), shown))

    print()
    if broken:
        print("%d model(s) read fields this Core version does not send." % broken)
        print("Each one is a screen showing a default value instead of real data.")
        return 1 if args.strict else 0
    print("All modelled fields exist in this Core version.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
