#!/usr/bin/env python3
"""
A cheap static check for unresolved identifiers in the Kotlin sources.

This is NOT a compiler and does not pretend to be one. It is a heuristic that
catches the single most common error when writing Compose by hand: using a
symbol (a composable, a Modifier extension, a type) without importing it.
`kotlinc` catches these too -- but only once you have an Android SDK, a Gradle
daemon and two minutes, and this runs in under a second.

How it works: collect every capitalised identifier the file references, then
subtract everything the file imports, declares itself, gets from its own
package, or gets from kotlin's default imports. Whatever is left is suspicious.

False positives are expected (nested types, generics, enum entries used
unqualified inside a `when`). Treat the output as a list to skim, not a build
failure -- which is why it exits 0 unless --strict is passed.
"""
from __future__ import annotations
import argparse
import re
import sys
from pathlib import Path

# Implicitly available: kotlin stdlib default imports plus language keywords
# and the primitive-ish types that appear capitalised.
BUILTINS = {
    "String", "Int", "Long", "Double", "Float", "Boolean", "Byte", "Char", "Unit",
    "Any", "Nothing", "List", "Map", "Set", "MutableList", "MutableMap", "MutableSet",
    "Array", "ByteArray", "IntArray", "CharArray", "Pair", "Triple", "Result",
    "Exception", "RuntimeException", "IllegalStateException", "IllegalArgumentException",
    "NumberFormatException", "Throwable", "Comparable", "Iterable", "Sequence",
    "Regex", "Volatile", "JvmInline", "JvmStatic", "JvmField", "Suppress", "OptIn",
    "Deprecated", "Override", "Synchronized", "Serializable", "SerialName",
    "StringBuilder", "Math", "System", "Thread", "Runnable", "AutoCloseable",
    "Comparator", "Number", "CharSequence", "Enum", "Class", "Object",
    "IndexOutOfBoundsException", "UnsupportedOperationException", "Error",
    "Companion", "Builder", "Default", "Type", "Entry", "Key", "Value",
    # java.lang is implicitly imported on the JVM.
    "Process", "ProcessBuilder", "ThreadLocal", "Runtime", "Character", "Integer",
    "Void", "Iterator", "Cloneable", "Package", "Record", "Boolean",
    # kotlin internal annotations and common enum-entry shapes.
    "PublishedApi", "ExperimentalStdlibApi", "DslMarker",
}

DECL = re.compile(
    r'^\s*(?:@\w+\s+)*(?:public |private |internal |protected |abstract |sealed |open |data |value |enum |annotation |inner |companion )*'
    r'(?:class|interface|object|enum class|annotation class|typealias)\s+(\w+)', re.M)
# Top-level vals/funs are package-visible too, and Compose codebases are full
# of them (design tokens, Modifier extensions). Without these the checker
# reports a wall of false positives for anything split across files in one
# package.
TOP_LEVEL = re.compile(r'^(?:@\w+(?:\([^)]*\))?\s*\n)*(?:public |internal )?(?:val|var|fun)\s+(?:<[^>]+>\s+)?(\w+)', re.M)
IMPORT = re.compile(r'^import\s+([\w.]+)(?:\s+as\s+(\w+))?', re.M)
PACKAGE = re.compile(r'^package\s+([\w.]+)', re.M)
# A capitalised identifier not immediately preceded by a dot (so we skip
# member access) and not part of a fully-qualified name.
IDENT = re.compile(r'(?<![\w.])([A-Z][A-Za-z0-9_]*)')

def strip_noise(src: str) -> str:
    src = re.sub(r'/\*.*?\*/', '', src, flags=re.S)
    src = re.sub(r'//[^\n]*', '', src)
    # Triple-quoted then normal strings.
    src = re.sub(r'"""(?:.|\n)*?"""', '""', src)
    src = re.sub(r'"(?:\\.|[^"\\\n])*"', '""', src)
    return src

def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("root", type=Path)
    ap.add_argument("--strict", action="store_true")
    args = ap.parse_args()

    files = sorted(args.root.rglob("*.kt"))
    if not files:
        print("no .kt files under", args.root); return 2

    # Everything declared anywhere in the project, keyed by package.
    pkg_decls: dict[str, set[str]] = {}
    parsed = []
    for f in files:
        raw = f.read_text(encoding="utf-8")
        clean = strip_noise(raw)
        pm = PACKAGE.search(raw)
        pkg = pm.group(1) if pm else ""
        decls = set(DECL.findall(clean)) | set(TOP_LEVEL.findall(clean))
        pkg_decls.setdefault(pkg, set()).update(decls)
        parsed.append((f, raw, clean, pkg, decls))

    total_flagged = 0
    for f, raw, clean, pkg, decls in parsed:
        imported = set()
        for full, alias in IMPORT.findall(raw):
            imported.add(alias or full.rsplit(".", 1)[-1])
            if full.endswith(".*"):
                imported.add("*")

        available = set(BUILTINS) | imported | decls | pkg_decls.get(pkg, set())
        # Enum entries and nested members declared inside this file.
        available |= set(re.findall(r'^\s*(\w+)\s*\(', clean, re.M))
        available |= set(re.findall(r'\b(?:val|var|fun|object|enum class|data class)\s+(\w+)', clean))

        body = clean[clean.find("\n", clean.find("package")) :] if "package" in clean else clean
        body = re.sub(r'^import[^\n]*\n', '', body, flags=re.M)

        used = set(IDENT.findall(body))
        missing = sorted(u for u in used - available if len(u) > 2)

        if missing:
            total_flagged += len(missing)
            rel = f.relative_to(args.root)
            print(f"{rel}")
            for m in missing:
                print(f"    ? {m}")

    print()
    if total_flagged:
        print(f"{total_flagged} identifier(s) with no visible import or declaration.")
        print("Some will be false positives (nested types, enum entries). Skim, don't obey.")
        return 1 if args.strict else 0
    print("No unresolved identifiers found.")
    return 0

if __name__ == "__main__":
    sys.exit(main())
