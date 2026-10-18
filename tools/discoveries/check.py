"""Checks that tr.json is in step with en.json (the build runs the same check as checkDiscoveries).

    python -I tools/discoveries/check.py

Both files must list the same ids in the same order, and each entry the same number of steps
with the same places ("at") and the same number of requirements; only the texts differ. Every
entry needs a location, a wiki link and at least one step. After a re-fetch, the entries named
here are the ones whose Turkish needs updating.
"""
import json
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
DIR = os.path.join(ROOT, "common", "src", "main", "resources", "assets", "wynnlabs", "discoveries")


def load(name):
    with open(os.path.join(DIR, name), encoding="utf-8") as f:
        return json.load(f)


def problems(en, tr):
    out = []
    by_id = {e["id"]: e for e in tr}
    if [e["id"] for e in en] != [e["id"] for e in tr]:
        out += [f"{i}: missing in tr.json" for i in [e["id"] for e in en] if i not in by_id]
        out += [f"{e['id']}: not in en.json" for e in tr if e["id"] not in {x["id"] for x in en}]
        if not out:
            out.append("the entries are in a different order")
    for e in en:
        i = e["id"]
        if e.get("x") is None or e.get("z") is None or e.get("y") is None:
            out.append(f"{i}: no location")
        if not str(e.get("wiki", "")).startswith("https://"):
            out.append(f"{i}: no wiki link")
        if not e.get("steps"):
            out.append(f"{i}: no step")
        t = by_id.get(i)
        if t is None:
            continue
        if len(t["steps"]) != len(e["steps"]):
            out.append(f"{i}: {len(e['steps'])} steps in en.json, {len(t['steps'])} in tr.json")
        else:
            for n, (a, b) in enumerate(zip(e["steps"], t["steps"]), 1):
                if a.get("at") != b.get("at"):
                    out.append(f"{i}: step {n} has another place in tr.json")
                if not str(b.get("text", "")).strip():
                    out.append(f"{i}: step {n} has no Turkish text")
        if len(t.get("requires", [])) != len(e.get("requires", [])):
            out.append(f"{i}: requirement count differs")
        if not str(t.get("lore", "")).strip():
            out.append(f"{i}: no Turkish lore")
    return out


def main():
    en = load("en.json")
    tr = load("tr.json")
    found = problems(en, tr)
    if found:
        print("Discovery data:\n  " + "\n  ".join(found))
        sys.exit(1)
    print(f"check: {len(en)} entries, {sum(len(e['steps']) for e in en)} steps, en.json and tr.json agree")


if __name__ == "__main__":
    main()
