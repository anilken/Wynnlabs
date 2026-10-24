"""Refreshes the tables that WynnBuilder build links ("wynnbuilder.github.io/builder/#...") are read with.

A WynnBuilder build link stores each item and tome by WynnBuilder's own numeric id, and the bit
widths of those ids change with each game version the site knows. To read the links the mod
ships, taken from the data files the WynnBuilder site loads:

  - the item and tome names by id (the game's display name, plus WynnBuilder's own name where
    it differs) and the ids WynnBuilder moved to another id;
  - per data version, in the site's order, the widths the link's fields were written with.

Only names, ids and widths are kept; nothing else of their data is shipped.

    common/src/main/resources/assets/wynnlabs/build/wynnbuilder-items.json

Run it after WynnBuilder adds a game version, then rebuild:

    python tools/build/wynnbuilder_items.py
"""
import datetime
import json
import pathlib
import re
import urllib.request

SITE = "https://wynnbuilder.github.io/"
OUT = pathlib.Path(__file__).resolve().parents[2] / "common/src/main/resources/assets/wynnlabs/build/wynnbuilder-items.json"
AGENT = "WynnLabs tools (https://github.com/anilken/wynnlabs)"

# the flag values the mod's reader takes as fixed; a version that changes one stops the refresh
FLAGS = {
    "EQUIPMENT_KIND": {"NORMAL": 0, "CRAFTED": 1, "CUSTOM": 2, "BITLEN": 2},
    "EQUIPMENT_POWDERS_FLAG": {"NO_POWDERS": 0, "HAS_POWDERS": 1, "BITLEN": 1},
    "POWDER_REPEAT_OP": {"REPEAT": 0, "NO_REPEAT": 1, "BITLEN": 1},
    "POWDER_REPEAT_TIER_OP": {"REPEAT_TIER": 0, "CHANGE_POWDER": 1, "BITLEN": 1},
    "POWDER_CHANGE_OP": {"NEW_POWDER": 0, "NEW_ITEM": 1, "BITLEN": 1},
    "TOMES_FLAG": {"NO_TOMES": 0, "HAS_TOMES": 1, "BITLEN": 1},
    "TOME_SLOT_FLAG": {"UNUSED": 0, "USED": 1, "BITLEN": 1},
    "SP_FLAG": {"ASSIGNED": 0, "AUTOMATIC": 1, "BITLEN": 1},
    "SP_ELEMENT_FLAG": {"ELEMENT_UNASSIGNED": 0, "ELEMENT_ASSIGNED": 1, "BITLEN": 1},
    "LEVEL_FLAG": {"MAX": 0, "OTHER": 1, "BITLEN": 1},
}
WIDTHS = {
    "items": "ITEM_ID_BITLEN", "tomes": "TOME_ID_BITLEN", "tomeSlots": "TOME_NUM", "slots": "EQUIPMENT_NUM",
    "powderTiers": "POWDER_TIERS", "powderBits": "POWDER_ID_BITLEN", "wrapBits": "POWDER_WRAPPER_BITLEN",
    "maxLevel": "MAX_LEVEL", "levelBits": "LEVEL_BITLEN", "spBits": "MAX_SP_BITLEN", "spTypes": "SP_TYPES",
}


def fetch(path, raw=False):
    req = urllib.request.Request(SITE + path, headers={"User-Agent": AGENT})
    with urllib.request.urlopen(req, timeout=120) as r:
        body = r.read()
    return body.decode("utf-8") if raw else json.loads(body.decode("utf-8"))


def table(entries, baseline):
    """id -> display name (a list, None for unused ids), id -> WynnBuilder's own name, id -> id moved to."""
    names, aliases, moved = {}, {}, {}
    for name, i in baseline.items():
        if not name.startswith("No "):        # the site's empty slots ("No Weapon Tome"): no item
            names[i] = name
    for e in entries:
        if "remapID" in e:
            moved[str(e["id"])] = e["remapID"]
            names.pop(e["id"], None)
            continue
        shown = e.get("displayName") or e["name"]
        names[e["id"]] = shown
        if e["name"] != shown:
            aliases[str(e["id"])] = e["name"]
    top = max(names) if names else -1
    return [names.get(i) for i in range(top + 1)], aliases, moved


def main():
    js = fetch("js/load_item.js", raw=True)
    block = re.search(r"wynn_version_names\s*=\s*\[(.*?)\]", js, re.S).group(1)
    versions = re.findall(r"'([^']+)'", block)
    out_versions = []
    for v in versions:
        c = fetch(f"data/{v}/encoding_consts.json")
        for k, want in FLAGS.items():
            if c[k] != want:
                raise SystemExit(f"{v}: {k} is {c[k]}, the mod reads {want}")
        if len(c["POWDER_ELEMENTS"]) != 5 or c["POWDERABLE_EQUIPMENT_NUM"] != 5:
            raise SystemExit(f"{v}: powder elements or powderable slots changed")
        row = {"name": v}
        row.update({k: c[src] for k, src in WIDTHS.items()})
        out_versions.append(row)
    latest = versions[-1]
    items = fetch(f"data/{latest}/items.json")["items"]
    tomes = fetch(f"data/{latest}/tomes.json")["tomes"]
    item_names, item_alias, item_moved = table(items, fetch("data/baseline/maps/id_map.json"))
    tome_names, tome_alias, tome_moved = table(tomes, fetch("data/baseline/maps/tome_map.json"))
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(json.dumps({
        "format": 1,
        "source": SITE,
        "fetched": datetime.date.today().isoformat(),
        "versions": out_versions,
        "items": item_names,
        "itemAliases": item_alias,
        "itemMoved": item_moved,
        "tomes": tome_names,
        "tomeAliases": tome_alias,
        "tomeMoved": tome_moved,
    }, ensure_ascii=False, separators=(",", ":")) + "\n", encoding="utf-8")
    print(f"{len(versions)} versions, {len(item_names)} item ids, {len(tome_names)} tome ids -> {OUT}")


if __name__ == "__main__":
    main()
