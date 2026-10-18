"""Refreshes the ability trees' connector maps the mod ships.

Wynncraft's API gives each class's tree as wynncraft.com draws it: every cell, nodes and
connector pieces, with coordinates (x 1-9 across, y counted down the whole tree from 1). The
mod only needs where the pieces are and which way their arms point, so this keeps just that,
one small file per class, in src/main/resources/assets/wynnlabs/trees/<class>.json:

    {"format": 1, "source": "...", "fetched": "2026-10-16", "cells": {"page:row:col": "connector_up_down", ...}}

Run it when Wynncraft changes a tree (a new update), then rebuild:

    python tools/trees/update.py
"""
import datetime
import json
import pathlib
import urllib.request

CLASSES = ["archer", "assassin", "mage", "shaman", "warrior"]
URL = "https://api.wynncraft.com/v3/ability/map/"
OUT = pathlib.Path(__file__).resolve().parents[2] / "src/main/resources/assets/wynnlabs/trees"
ROWS = 6


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    today = datetime.date.today().isoformat()
    for cls in CLASSES:
        req = urllib.request.Request(URL + cls, headers={"User-Agent": "WynnLabs tools (https://github.com/anilken/wynn-inventory-api)"})
        with urllib.request.urlopen(req, timeout=30) as r:
            data = json.load(r)
        cells = {}
        for page, items in data.items():
            for item in items:
                if item.get("type") != "connector":
                    continue
                x = item["coordinates"]["x"] - 1
                y = item["coordinates"]["y"] - 1
                cells[f"{y // ROWS + 1}:{y % ROWS}:{x}"] = item["meta"]["icon"]
        out = {"format": 1, "source": URL + cls, "fetched": today, "cells": dict(sorted(cells.items(), key=lambda kv: tuple(int(p) for p in kv[0].split(":"))))}
        (OUT / f"{cls}.json").write_text(json.dumps(out, indent=1) + "\n", encoding="utf-8")
        print(f"{cls}: {len(cells)} pieces")


if __name__ == "__main__":
    main()
