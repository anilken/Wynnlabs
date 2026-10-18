"""Fetches every quest and mini-quest page of the Wynncraft wiki for the shipped quest guides.

    python -I tools/quests/fetch.py            # list the categories and fetch every page
    python -I tools/quests/fetch.py --offline  # only report what the last fetch kept

Lists Category:Quests and Category:Mini-Quests, reads every page's wikitext 50 pages a request,
half a second apart, with the mod's User-Agent, and keeps the pages in build/quests-wiki/ with
_titles.json ({"quest": [...], "mini": [...]}). It only fetches: the guides are cut into stages
by the mod's own parser (QuestWiki.parse) in the dumpQuests Gradle task, so the shipped stages
and the ones the mod reads live follow one set of rules. The wiki's text is CC BY-NC-SA 3.0.
"""
import json
import os
import re
import sys
import time
import urllib.parse
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
CACHE = os.path.join(ROOT, "build", "quests-wiki")
API = "https://wynncraft.wiki.gg/api.php"
UA = "WynnLabs (Minecraft client mod; quest guides; https://github.com/anilken/wynnlabs)"
CATEGORIES = {"quest": "Category:Quests", "mini": "Category:Mini-Quests"}
OLD_VERSION = re.compile(r"\(\d+(?:\.\d+)+\)")


def api(params):
    url = API + "?" + urllib.parse.urlencode({**params, "format": "json"})
    req = urllib.request.Request(url, headers={"User-Agent": UA})
    with urllib.request.urlopen(req, timeout=30) as r:
        data = json.load(r)
    time.sleep(0.5)
    return data


def file_name(title):
    return re.sub(r"[^A-Za-z0-9 ._'-]", "_", title) + ".txt"


def members(category):
    titles = []
    cont = {}
    while True:
        d = api({"action": "query", "list": "categorymembers", "cmtitle": category, "cmnamespace": "0",
                 "cmlimit": "500", **cont})
        titles += [m["title"] for m in d["query"]["categorymembers"]]
        if "continue" not in d:
            return titles
        cont = d["continue"]


def fetch():
    os.makedirs(CACHE, exist_ok=True)
    kinds = {k: members(c) for k, c in CATEGORIES.items()}
    # translations ("/de") and old versions ("Lost Tower (1.11.2)") are other pages of the same quest
    for k in kinds:
        kinds[k] = [t for t in kinds[k] if "/" not in t and not OLD_VERSION.search(t)]
    # a page in both categories counts as a quest
    kinds["mini"] = [t for t in kinds["mini"] if t not in set(kinds["quest"])]
    wanted = kinds["quest"] + kinds["mini"]
    got = 0
    for i in range(0, len(wanted), 50):
        d = api({"action": "query", "prop": "revisions", "rvprop": "content", "rvslots": "main",
                 "titles": "|".join(wanted[i:i + 50])})
        for p in d["query"]["pages"].values():
            if "revisions" in p:
                text = p["revisions"][0]["slots"]["main"]["*"]
                with open(os.path.join(CACHE, file_name(p["title"])), "w", encoding="utf-8", newline="\n") as f:
                    f.write(text)
                got += 1
    with open(os.path.join(CACHE, "_titles.json"), "w", encoding="utf-8") as f:
        json.dump(kinds, f, indent=1, ensure_ascii=False)
    print(f"fetched {got} pages: {len(kinds['quest'])} quests, {len(kinds['mini'])} mini-quests")


def report():
    with open(os.path.join(CACHE, "_titles.json"), encoding="utf-8") as f:
        kinds = json.load(f)
    print(f"{len(kinds['quest'])} quests, {len(kinds['mini'])} mini-quests in {CACHE}")


if __name__ == "__main__":
    report() if "--offline" in sys.argv else fetch()
