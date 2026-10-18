"""Refreshes the id tables that WynnBuilder's crafter codes ("CR-...") are made of.

A WynnBuilder craft code stores each ingredient and the recipe by WynnBuilder's own numeric id.
To read and write those codes the mod ships the two tables (name -> id), taken from the data
files the WynnBuilder site loads. Only the ids are kept; nothing else of their data is shipped.

    src/main/resources/assets/wynnlabs/crafting/wynnbuilder-ids.json

Run it after a game update adds ingredients or recipes, then rebuild:

    python tools/crafting/wynnbuilder_ids.py
"""
import datetime
import json
import pathlib
import urllib.request

BASE = "https://wynnbuilder.github.io/data/baseline/compressed/"
OUT = pathlib.Path(__file__).resolve().parents[2] / "src/main/resources/assets/wynnlabs/crafting/wynnbuilder-ids.json"
AGENT = "WynnLabs tools (https://github.com/anilken/wynnlabs)"


def fetch(name):
    req = urllib.request.Request(BASE + name + "_compress.json", headers={"User-Agent": AGENT})
    with urllib.request.urlopen(req, timeout=60) as r:
        return json.load(r)


def main():
    ings = fetch("ingreds")
    recipes = fetch("recipes")
    recipes = recipes["recipes"] if isinstance(recipes, dict) else recipes
    ing_ids = {x["name"]: x["id"] for x in ings}
    # WynnBuilder names recipes "Spear-1-3"; the mod's ids (the game's) are "Spear1-3"
    rec_ids = {x["name"].replace("-", "", 1): x["id"] for x in recipes}
    OUT.write_text(json.dumps({
        "format": 1,
        "source": BASE,
        "fetched": datetime.date.today().isoformat(),
        "ingredients": dict(sorted(ing_ids.items())),
        "recipes": dict(sorted(rec_ids.items())),
    }, ensure_ascii=False, separators=(",", ":")) + "\n", encoding="utf-8")
    print(f"{len(ing_ids)} ingredients, {len(rec_ids)} recipes -> {OUT}")


if __name__ == "__main__":
    main()
