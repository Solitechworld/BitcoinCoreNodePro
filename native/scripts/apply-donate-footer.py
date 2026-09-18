#!/usr/bin/env python3
"""
Adds the donation footer to every screen, in one deterministic pass.

Why a script rather than ten hand edits: ten hand edits is ten chances to miss
a screen or paste it in the wrong scope, and no record of what changed. This
matches on exact anchors, reports every file it touched, and is idempotent --
run it twice and the second run reports "already present" for everything.

Placement rules, by screen shape:

  scrolling Column   inserted immediately before the trailing bottom spacer,
                     so it is the last thing in the scroll content
  LazyColumn         appended as a trailing `item { }`
  Send screen        gets the full DonatePanel rather than the collapsed
                     footer, on the compose step
  Console            skipped -- it is a full-height terminal with a docked
                     input bar and no scroll content to sit under

Usage:
    ./apply-donate-footer.py [--check]

--check reports what it would do without writing anything.
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

SCREENS = Path("app/src/main/java/com/solitech/bitcoincorenode/ui/screens")

FOOTER_IMPORT = "import com.solitech.bitcoincorenode.ui.components.DonateFooter"
PANEL_IMPORT = "import com.solitech.bitcoincorenode.ui.components.DonatePanel"

# Scrolling-Column screens: anchor on the trailing bottom spacer.
SPACER_ANCHOR = "Spacer(Modifier.height(24.dp))"

COLUMN_SCREENS = [
    "dashboard/DashboardScreen.kt",
    "node/NodeControlScreen.kt",
    "mempool/MempoolScreen.kt",
    "settings/SettingsScreen.kt",
    "receive/ReceiveScreen.kt",
    "wallet/WalletScreens.kt",
]

# LazyColumn screens: append a trailing item.
LAZY_SCREENS = {
    "peers/PeersScreen.kt": (
        "            items(peers, key = { it.id }) { peer -> PeerCard(peer, now) }\n",
        "            items(peers, key = { it.id }) { peer -> PeerCard(peer, now) }\n"
        "            item { DonateFooter() }\n",
    ),
    "wallet/WalletScreens.kt::list": (
        "            items(wallets) { name ->",
        None,  # handled specially below
    ),
}


def add_import(text: str, import_line: str) -> tuple[str, bool]:
    if import_line in text:
        return text, False
    lines = text.split("\n")
    # Insert alphabetically among the existing com.solitech imports so the
    # import block stays sorted and future diffs stay small.
    idx = None
    for i, line in enumerate(lines):
        if line.startswith("import com.solitech.bitcoincorenode.ui.components."):
            if line > import_line:
                idx = i
                break
            idx = i + 1
    if idx is None:
        for i, line in enumerate(lines):
            if line.startswith("import "):
                idx = i
                break
    if idx is None:
        return text, False
    lines.insert(idx, import_line)
    return "\n".join(lines), True


def process(path: Path, check: bool) -> list[str]:
    notes: list[str] = []
    original = path.read_text(encoding="utf-8")
    text = original

    rel = path.as_posix()

    # --- scrolling Column screens ---
    if any(rel.endswith(s) for s in COLUMN_SCREENS):
        if "DonateFooter()" in text:
            notes.append("footer already present")
        elif SPACER_ANCHOR not in text:
            notes.append(f"ANCHOR NOT FOUND ({SPACER_ANCHOR!r}) -- add the footer by hand")
        else:
            # Only the LAST occurrence: SendScreen and WalletScreens have more
            # than one composable ending in a bottom spacer, and every one of
            # them is a page bottom, so replace them all.
            count = text.count(SPACER_ANCHOR)
            text = text.replace(
                SPACER_ANCHOR,
                "DonateFooter()\n            " + SPACER_ANCHOR,
            )
            notes.append(f"footer inserted at {count} page bottom(s)")
            text, added = add_import(text, FOOTER_IMPORT)
            if added:
                notes.append("import added")

    # --- LazyColumn: peers ---
    if rel.endswith("peers/PeersScreen.kt"):
        anchor, replacement = LAZY_SCREENS["peers/PeersScreen.kt"]
        if "item { DonateFooter() }" in text:
            notes.append("footer already present")
        elif anchor not in text:
            notes.append("ANCHOR NOT FOUND for peers list -- add the footer by hand")
        else:
            text = text.replace(anchor, replacement)
            notes.append("footer appended to peer list")
            text, added = add_import(text, FOOTER_IMPORT)
            if added:
                notes.append("import added")

    # --- LazyColumn: wallet list (inside a file that also has a Column screen) ---
    if rel.endswith("wallet/WalletScreens.kt"):
        anchor = """                    Text("descriptor wallet", style = CyberType.Terminal, color = CyberColors.TextTertiary)
                }
            }
        }"""
        replacement = """                    Text("descriptor wallet", style = CyberType.Terminal, color = CyberColors.TextTertiary)
                }
            }
            item { DonateFooter() }
        }"""
        if "item { DonateFooter() }" in text:
            notes.append("wallet-list footer already present")
        elif anchor not in text:
            notes.append("ANCHOR NOT FOUND for wallet list -- add the footer by hand")
        else:
            text = text.replace(anchor, replacement)
            notes.append("footer appended to wallet list")

    # --- Send screen: full panel, not the collapsed footer ---
    if rel.endswith("send/SendScreen.kt"):
        if "DonatePanel()" in text:
            notes.append("donate panel already present")
        else:
            anchor = """        CyberButton(
            text = "Review transaction",
            onClick = vm::build,
            enabled = state.canBuild,
            fillWidth = true,
        )
        Spacer(Modifier.height(24.dp))"""
            replacement = """        CyberButton(
            text = "Review transaction",
            onClick = vm::build,
            enabled = state.canBuild,
            fillWidth = true,
        )

        Spacer(Modifier.height(8.dp))
        DonatePanel()
        Spacer(Modifier.height(24.dp))"""
            if anchor not in text:
                notes.append("ANCHOR NOT FOUND on send screen -- add DonatePanel() by hand")
            else:
                text = text.replace(anchor, replacement)
                notes.append("donate panel added below Review")
                text, a1 = add_import(text, PANEL_IMPORT)
                if a1:
                    notes.append("import added")

    if text != original and not check:
        path.write_text(text, encoding="utf-8")

    return notes


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--check", action="store_true", help="report without writing")
    args = ap.parse_args()

    if not SCREENS.is_dir():
        print(f"error: run this from the BitcoinCoreNode project root "
              f"(expected {SCREENS})", file=sys.stderr)
        return 2

    targets = sorted(set(
        [SCREENS / s for s in COLUMN_SCREENS] +
        [SCREENS / "peers/PeersScreen.kt", SCREENS / "send/SendScreen.kt"]
    ))

    changed = 0
    for path in targets:
        if not path.exists():
            print(f"  ??  {path.relative_to(SCREENS)} not found")
            continue
        notes = process(path, args.check)
        if notes:
            marker = "  ..  " if args.check else "  OK  "
            print(f"{marker}{path.relative_to(SCREENS)}")
            for n in notes:
                flag = "!!" if "NOT FOUND" in n else "  "
                print(f"        {flag} {n}")
            changed += 1

    print()
    print(f"{'Would change' if args.check else 'Processed'} {changed} file(s).")
    print("Console screen skipped on purpose: full-height terminal, no scroll content.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
