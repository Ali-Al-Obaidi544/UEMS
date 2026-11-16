#!/usr/bin/env python3
"""Generates src/main/resources/i18n/messages*.properties from tools/messages.src (key ## English ## Arabic)."""
import os
root = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..")
rows = [l.rstrip("\n").split(" ## ") for l in open(os.path.join(root, "tools/messages.src"), encoding="utf-8") if l.strip()]
for name, idx in (("messages.properties", 1), ("messages_ar.properties", 2)):
    with open(os.path.join(root, "src/main/resources/i18n", name), "w", encoding="utf-8") as f:
        f.write("# UEMS message bundle. Generated from tools/messages.src by tools/gen_bundles.py (UTF-8).\n")
        for r in rows:
            assert "'" not in r[idx], "apostrophe in " + r[0]
            f.write(f"{r[0]}={r[idx]}\n")
print(len(rows), "keys")
