"""Checks that tr.json is in step with en.json (the build runs the same check as checkQuests).

    python -I tools/quests/check.py            # the whole file
    python -I tools/quests/check.py --part X   # a translation batch file X before it is merged

tr.json lists the same quest ids as en.json in the same order. Each entry has the same number of
stages, and each stage the same number of hints. Every coordinate and every double-quoted string
of the English task and hints is in the Turkish text unchanged. A Turkish text equal to its
English (beyond a few words) is reported as untranslated, except NPC dialogue, which may stay English. With --part, the batch's entries are
checked against the English entries with the same ids.
"""
import json
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
DIR = os.path.join(ROOT, "src", "main", "resources", "assets", "wynnlabs", "quests")
COORD = re.compile(r"-?\d{1,5}\s*,\s*-?\d{1,5}\s*,\s*-?\d{1,5}")
QUOTED = re.compile(r"\"[^\"]{2,}\"")
WORDS = re.compile(r"[A-Za-z]{3,}")
# an NPC's line ("Worid: …"): dialogue takes the game's Turkish where WynnChaYuan has it, else stays English
SPEAKER = re.compile(r"^[A-Z?][^:]{0,40}: ")


def load(path):
    with open(path, encoding="utf-8") as f:
        return json.load(f)


def norm_coord(c):
    return re.sub(r"\s+", "", c)


def text_problems(where, en, tr):
    out = []
    for c in COORD.findall(en):
        if norm_coord(c) not in {norm_coord(x) for x in COORD.findall(tr)}:
            out.append(f"{where}: coordinates {c} missing")
    for q in QUOTED.findall(en):
        if q not in tr:
            out.append(f"{where}: quoted {q} changed or missing")
    # words outside quotes: a line that is all a quoted game string stays as it is
    if tr.strip() == en.strip() and len(WORDS.findall(QUOTED.sub("", en))) > 4 and not SPEAKER.match(en):
        out.append(f"{where}: not translated")
    if not tr.strip() and en.strip():
        out.append(f"{where}: empty")
    return out


def problems(en, tr, partial=False):
    out = []
    en_by = {e["id"]: e for e in en}
    if not partial:
        if [e["id"] for e in en] != [e["id"] for e in tr]:
            missing = [e["id"] for e in en if e["id"] not in {t["id"] for t in tr}]
            out.append(f"ids or order differ; missing in tr.json: {missing[:10]}{'…' if len(missing) > 10 else ''}")
    for t in tr:
        e = en_by.get(t["id"])
        if e is None:
            out.append(f"{t['id']}: not in en.json")
            continue
        if len(t["stages"]) != len(e["stages"]):
            out.append(f"{t['id']}: {len(t['stages'])} stages, English has {len(e['stages'])}")
            continue
        for i, (es, ts) in enumerate(zip(e["stages"], t["stages"])):
            where = f"{t['id']} stage {i + 1}"
            if es["title"].lower().startswith("stage ") and ts["title"] != es["title"]:
                out.append(f"{where}: title must stay {es['title']!r}")
            out += text_problems(where + " task", es["task"], ts["task"])
            if len(ts["hints"]) != len(es["hints"]):
                out.append(f"{where}: {len(ts['hints'])} hints, English has {len(es['hints'])}")
                continue
            for j, (eh, th) in enumerate(zip(es["hints"], ts["hints"])):
                out += text_problems(f"{where} hint {j + 1}", eh, th)
    return out


def main():
    en = load(os.path.join(DIR, "en.json"))
    if "--part" in sys.argv:
        tr = load(sys.argv[sys.argv.index("--part") + 1])
        found = problems(en, tr, partial=True)
    else:
        tr = load(os.path.join(DIR, "tr.json"))
        found = problems(en, tr)
    for p in found:
        print(p)
    print(f"{len(tr)} entries checked, {len(found)} problems")
    sys.exit(1 if found else 0)


if __name__ == "__main__":
    main()
