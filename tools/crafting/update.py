"""Refreshes the crafting recipes the mod ships.

Wynncraft's official API lists every crafting recipe (15 item types x 48 level brackets) at
/v3/item/recipe/database. The mod only needs the numbers that decide a craft, so this keeps
those, fixes the API's quirks, and writes one compact file:

    src/main/resources/assets/wynnlabs/crafting/recipes.json

Quirks fixed here, so the mod reads clean data:
- durability is stored x1000 (571000 means 571): divided once;
- a few ranges have min and max swapped (Spear117-119 damage, Pants10-13 durability): sorted;
- materials use internal names ("Refined Jungle Wood"); the in-game name ("Jungle Planks")
  is added next to it.

Run it after a game update that changes recipes, then rebuild:

    python tools/crafting/update.py
"""
import datetime
import json
import pathlib
import urllib.request

URL = "https://api.wynncraft.com/v3/item/recipe/database?full_result"
OUT = pathlib.Path(__file__).resolve().parents[2] / "src/main/resources/assets/wynnlabs/crafting/recipes.json"
AGENT = "WynnLabs tools (https://github.com/anilken/wynnlabs)"

# The refined material's kind as the game shows it ("Refined Oak Wood" is "Oak Planks").
SHOWN_KIND = {"Wood": "Planks"}


def shown(internal):
    """The in-game name of a refined material from its internal name."""
    name = internal[len("Refined "):] if internal.startswith("Refined ") else internal
    head, _, kind = name.rpartition(" ")
    return f"{head} {SHOWN_KIND.get(kind, kind)}" if head else name


def pair(r, scale=1):
    """A {minimum, maximum} range as [low, high], scaled; None when the recipe has none."""
    if not r or "minimum" not in r or "maximum" not in r:
        return None
    a, b = r["minimum"] / scale, r["maximum"] / scale
    a, b = (int(round(a)), int(round(b)))
    return [min(a, b), max(a, b)]


def main():
    req = urllib.request.Request(URL, headers={"User-Agent": AGENT})
    with urllib.request.urlopen(req, timeout=60) as r:
        data = json.load(r)
    items = data if isinstance(data, list) else list(data.values())
    out = []
    for x in items:
        lv = x["level"]
        out.append({
            "id": x["internalName"],
            "type": x["type"],
            "skill": x["skill"],
            "level": [min(lv["minimum"], lv["maximum"]), max(lv["minimum"], lv["maximum"])],
            "materials": [{"item": m["item"], "shown": shown(m["item"]), "amount": m["amount"]} for m in x["materials"]],
            "healthOrDamage": pair(x.get("healthOrDamage")),
            "durability": pair(x.get("durability"), 1000),
            "duration": pair(x.get("duration")),
            "basicDuration": pair(x.get("basicDuration")),
            "xp": x.get("xp", 0),
        })
    out.sort(key=lambda r: (r["type"], r["level"][0]))
    doc = {"format": 1, "source": URL.split("?")[0], "fetched": datetime.date.today().isoformat(), "recipes": out}
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(json.dumps(doc, ensure_ascii=False, separators=(",", ":")), encoding="utf-8")
    types = sorted({r["type"] for r in out})
    print(f"{len(out)} recipes, {len(types)} types -> {OUT}")


if __name__ == "__main__":
    main()
