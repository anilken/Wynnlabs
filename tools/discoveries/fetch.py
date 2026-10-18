"""Builds common/src/main/resources/assets/wynnlabs/discoveries/en.json from the Wynncraft wiki.

    python -I tools/discoveries/fetch.py            # fetch the pages, then write en.json
    python -I tools/discoveries/fetch.py --offline  # reuse the pages fetched last time

Lists Category:Secret_Discoveries, reads every page's wikitext (50 pages a request, half a
second apart, with the mod's User-Agent) and keeps the raw pages in build/discoveries-wiki/.
Each page with a secret-discovery infobox gives one entry: the infobox (name, coordinates,
minimum level, requirements, lore), the region from the discoveries navbox, and the Access
section cut into steps. A step is one line (paragraph or bullet) of prose; a dialogue block
adds a "Choose ..." step with its options (purple) unless the step before it names them all.
MapLink and RenderLocation give a step's place. The wiki's text is CC BY-NC-SA 3.0: every
entry keeps its page URL.

Pages whose steps need a hand fix are listed at the end. Fix them in tools/discoveries/fixes.json
(by id: any field; "steps" replaces the whole list). The fixes are applied after extraction, so
a later run keeps them. "hash" is always the SHA-1 of the extracted (unfixed) steps: the mod
re-extracts the live page with the same rules (DiscoveryWiki.java) and compares, so a fixed
page counts as unchanged until the wiki changes. Keep the step rules here and in
DiscoveryWiki.java the same.
"""
import hashlib
import json
import os
import re
import sys
import time
import urllib.parse
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
OUT = os.path.join(ROOT, "common", "src", "main", "resources", "assets", "wynnlabs", "discoveries", "en.json")
CACHE = os.path.join(ROOT, "build", "discoveries-wiki")
FIXES = os.path.join(os.path.dirname(os.path.abspath(__file__)), "fixes.json")
API = "https://wynncraft.wiki.gg/api.php"
PAGE = "https://wynncraft.wiki.gg/wiki/"
UA = "WynnLabs (Minecraft client mod; secret discoveries; https://github.com/anilken/wynnlabs)"
NAVBOX = "Template:Navbox Secret discoveries"


# ---- fetching -------------------------------------------------------------------------------

def api(params):
    url = API + "?" + urllib.parse.urlencode({**params, "format": "json"})
    req = urllib.request.Request(url, headers={"User-Agent": UA})
    with urllib.request.urlopen(req, timeout=30) as r:
        data = json.load(r)
    time.sleep(0.5)
    return data


def file_name(title):
    return re.sub(r"[^A-Za-z0-9 ._'-]", "_", title) + ".txt"


def fetch(cache):
    os.makedirs(cache, exist_ok=True)
    titles = []
    cont = {}
    while True:
        d = api({"action": "query", "list": "categorymembers", "cmtitle": "Category:Secret_Discoveries",
                 "cmnamespace": "0", "cmlimit": "500", **cont})
        titles += [m["title"] for m in d["query"]["categorymembers"]]
        if "continue" not in d:
            break
        cont = d["continue"]
    wanted = titles + [NAVBOX]
    for i in range(0, len(wanted), 50):
        d = api({"action": "query", "prop": "revisions", "rvprop": "content", "rvslots": "main",
                 "titles": "|".join(wanted[i:i + 50])})
        for p in d["query"]["pages"].values():
            if "revisions" in p:
                text = p["revisions"][0]["slots"]["main"]["*"]
                with open(os.path.join(cache, file_name(p["title"])), "w", encoding="utf-8", newline="\n") as f:
                    f.write(text)
    with open(os.path.join(cache, "_titles.json"), "w", encoding="utf-8") as f:
        json.dump(titles, f, indent=1, ensure_ascii=False)
    print(f"fetched {len(titles)} category pages and the navbox")


# ---- step rules (DiscoveryWiki.java mirrors these) -------------------------------------------
# re.ASCII everywhere: \s, \d and \w then match what Java's regexes match.

A = re.ASCII
ENTITIES = [("&nbsp;", " "), ("&quot;", '"'), ("&#39;", "'"), ("&ndash;", "\u2013"), ("&mdash;", "\u2014"),
            ("&lt;", "<"), ("&gt;", ">"), ("&amp;", "&")]
MAPLINK = re.compile(r"\{\{MapLink(\|[^{}]*)}}", A)
RENDER = re.compile(r"\{\{RenderLocation([^{}]*)}}", A)
COLOUR = re.compile(r"\{\{c\|[^|{}]*\|([^{}]*)}}", A)
TEMPLATE = re.compile(r"\{\{[^{}]*}}", A)
FILE_LINK = re.compile(r"\[\[(?:File|Image|file|image):[^\[\]]*]]", A)
LINK = re.compile(r"\[\[(?:[^|\[\]]*\|)?([^\[\]]*)]]", A)
EXTERNAL = re.compile(r"\[https?://[^ \]]+ ([^\]]*)]", A)
TAG = re.compile(r"<[^<>]*>", A)
BR = re.compile(r"<br\s*/?>", A | re.I)
TRIPLE = re.compile(r"(?<![\w.])(-?\d{1,5})(?:\s*,\s*|\s+)(-?\d{1,5})(?:\s*,\s*|\s+)(-?\d{1,5})(?!\w|\.\d)", A)
XZ = re.compile(r"\bX:\s*(-?\d{1,5}),?\s*Z:\s*(-?\d{1,5})", A)
COMMENT = re.compile(r"<!--.*?-->", A | re.S)
REF = re.compile(r"<ref[^<>]*?/>|<ref[^<>]*>.*?</ref>", A | re.S)
GALLERY = re.compile(r"<gallery.*?</gallery>", A | re.S | re.I)
TABLE = re.compile(r"^\{\|.*?^\|}", A | re.S | re.M)
CONDITIONAL = re.compile(r"appears (?:when|if)|available (?:when|if)|is available", A | re.I)
CHOICE = re.compile(r"^[*:#]+\s*\{\{c\|[^|{}]*purple[^|{}]*\|", A)
SPEAKER = re.compile(r"^[*:#]*\s*(?:'''[^']*?:\s*'''|'''[^']*'''\s*:|\{\{Dialogue\|)", A | re.I)
ITALIC_ONLY = re.compile(r"^[*:#]*\s*''[^'].*[^']''$", A)
BOLD_ONLY = re.compile(r"^[*:#]*\s*'''[^']*'''$", A)
HEADING = re.compile(r"^(=+)\s*(.*?)\s*=+\s*$", A)
SECTIONS = re.compile(r"^(?:access|the discovery|finding the city|unlocking the library|the four towers|stage \d+)$",
                      A | re.I)
QUOTE_INTRO = re.compile(r"(?:dialogue|says?|message|reads?|text|lines?)\b[^.]*:$", A | re.I)
INPUT = re.compile(r"\{\{Input\|([a-z]+)}}", A)
CLICKS = {"lmb": "Left-click", "rmb": "Right-click"}
BULLETS = re.compile(r"^[*#:;]+\s*", A)
SPACES = re.compile(r"\s+", A)
NUMBER = re.compile(r"^(-?\d+)(?:\.\d+)?$", A)
DIGITS = re.compile(r"-?\d+", A)
COND = " {{cond}}"
CONTROL = "".join(chr(i) for i in range(33))


def trim(s):
    """Java's String.trim(): drops every char up to a space from both ends."""
    return s.strip(CONTROL)


def params(body):
    """A template's parameters ("|x=1|y=2" or "|1|2|3") as a dict; positional ones keyed 1, 2, 3."""
    out = {}
    n = 0
    for part in body.split("|")[1:]:
        if "=" in part:
            k, v = part.split("=", 1)
            out[trim(k).lower()] = trim(v)
        else:
            n += 1
            out[str(n)] = trim(part)
    return out


def number(s):
    """A coordinate as written ("-2206", "11.5" → 11); None when there is none."""
    m = NUMBER.match(trim(s)) if s is not None else None
    return int(m.group(1)) if m else None


def point(p):
    """x/y/z (named or positional, or "coordinates=x, y, z") → [x, y, z]; y may be None."""
    if "coordinates" in p:
        c = DIGITS.findall(p["coordinates"])
        return [int(c[0]), int(c[1]), int(c[2])] if len(c) == 3 else None
    x = number(p.get("x", p.get("1")))
    y = number(p.get("y", p.get("2")))
    z = number(p.get("z", p.get("3")))
    return [x, y, z] if x is not None and z is not None else None


def maplink_text(m):
    p = point(params(m.group(1)))
    if p is None:
        return ""
    return f"{p[0]}, {p[1]}, {p[2]}" if p[1] is not None else f"{p[0]}, {p[2]}"


def clean(s):
    """Wikitext of one line → plain text: links keep their label, coloured text its text,
    MapLink becomes "x, y, z", every other template and tag goes."""
    for k, v in ENTITIES:
        s = s.replace(k, v)
    s = s.replace("{{--}}", "\u2014")
    s = INPUT.sub(lambda m: CLICKS.get(m.group(1), ""), s)
    s = MAPLINK.sub(maplink_text, s)
    s = BR.sub(" ", s)
    s = FILE_LINK.sub("", s)
    while True:
        before = s
        s = COLOUR.sub(lambda m: m.group(1), s)
        s = TEMPLATE.sub("", s)
        s = LINK.sub(lambda m: m.group(1), s)
        if s == before:
            break
    s = EXTERNAL.sub(lambda m: m.group(1), s)
    s = TAG.sub("", s)
    s = s.replace("'''", "").replace("''", "")
    s = BULLETS.sub("", trim(s), count=1)
    return trim(SPACES.sub(" ", s))


def first_point(raw, text):
    """The first place a line gives: the first MapLink or RenderLocation in its wikitext, else
    "x, y, z" or "X: x, Z: z" in its text."""
    best = None
    for pat in (MAPLINK, RENDER):
        for m in pat.finditer(raw):
            p = point(params(m.group(1)))
            if p is not None:
                if best is None or m.start() < best[0]:
                    best = (m.start(), p)
                break
    if best is not None:
        return best[1]
    for m in TRIPLE.finditer(text):
        x, y, z = int(m.group(1)), int(m.group(2)), int(m.group(3))
        if max(abs(x), abs(z)) >= 50:
            return [x, y, z]
    m = XZ.search(text)
    if m:
        return [int(m.group(1)), None, int(m.group(2))]
    return None


def matching_end(s, start):
    """The index just past the "}}" that closes the "{{" at start, or -1."""
    depth = 0
    j = start
    while j < len(s) - 1:
        if s.startswith("{{", j):
            depth += 1
            j += 2
        elif s.startswith("}}", j):
            depth -= 1
            j += 2
            if depth == 0:
                return j
        else:
            j += 1
    return -1


def drop_block_templates(s):
    """Templates over several lines ({{Infobox/Items …}}) carry no step: drop them."""
    out = []
    i = 0
    while True:
        start = s.find("{{", i)
        if start < 0:
            out.append(s[i:])
            break
        end = matching_end(s, start)
        if end < 0 or "\n" not in s[start:end]:
            out.append(s[i:start + 2])
            i = start + 2
        else:
            out.append(s[i:start])
            i = end
    return "".join(out)


def unspoil(s):
    """{{HideSpoiler|text|label|…}} → text: the first parameter, which may hold templates."""
    while True:
        start = s.find("{{HideSpoiler")
        if start < 0:
            return s
        end = matching_end(s, start)
        if end < 0:
            return s[:start] + s[start + len("{{HideSpoiler"):]
        inner = s[start + 2:end - 2]
        cut = []
        depth = 0
        k = 0
        while k < len(inner):
            two = inner[k:k + 2]
            if two in ("{{", "[["):
                depth += 1
                k += 2
            elif two in ("}}", "]]"):
                depth -= 1
                k += 2
            else:
                if inner[k] == "|" and depth == 0:
                    cut.append(k)
                k += 1
        text = "" if not cut else inner[cut[0] + 1:cut[1] if len(cut) > 1 else len(inner)]
        if text.startswith("1="):
            text = text[2:]
        s = s[:start] + text + s[end:]


def section(text):
    """The guide part of a page: its Access section, or failing that the sections named like
    one ("The Discovery", "Stage 1", …). Sub-headings inside are left out, their text kept."""
    lines = text.split("\n")
    heads = [HEADING.match(l) for l in lines]
    access = any(h and len(h.group(1)) == 2 and h.group(2).lower() == "access" for h in heads)
    picked = []
    taking = False
    for l, h in zip(lines, heads):
        if h and len(h.group(1)) == 2:
            name = h.group(2)
            taking = name.lower() == "access" if access else SECTIONS.match(name) is not None
        elif h is None and taking:
            picked.append(l)
    return "\n".join(picked)


def prepare(page):
    """Comments, refs (a conditional one leaves a mark), galleries, tables, spoiler wrappers and
    templates spread over lines go before the page is read line by line."""
    text = page.replace("\r\n", "\n")
    text = COMMENT.sub("", text)
    text = REF.sub(lambda m: COND if not m.group(0).endswith("/>") and CONDITIONAL.search(m.group(0)) else "", text)
    text = GALLERY.sub("", text)
    text = TABLE.sub("", text)
    return drop_block_templates(unspoil(text))


def steps(page):
    """A page's access steps: [{"text", "at"}], at = [x, y, z] (y may be None) or None."""
    out = []
    pending = None      # a RenderLocation on a line of its own: the next step's place
    choices = []        # purple dialogue options not yet made a step
    in_collapse = False
    quoting = False     # bullets after "… the following dialogue:" are what the game says

    def flush():
        if choices:
            prose = out[-1]["text"].lower() if out else ""
            named = all(c.lower() in prose for c in choices)
            if not named:
                out.append({"text": "Choose " + ", then ".join('"' + c + '"' for c in choices) + ".", "at": None})
            choices.clear()

    for raw in section(prepare(page)).split("\n"):
        l = trim(raw)
        if l.startswith("{{Collapse top"):
            in_collapse = True
            continue
        if l.startswith("{{Collapse bottom"):
            in_collapse = False
            flush()
            continue
        if CHOICE.match(l):
            c = clean(l)
            if c and not c.startswith("[+") and not c.startswith("[-") and COND not in l and c not in choices:
                choices.append(c)
            continue
        if quoting and l.startswith("*"):
            continue
        quoting = False
        if in_collapse or not l or l[0] in "{|!" and not l.startswith("{{") \
                or SPEAKER.match(l) or ITALIC_ONLY.match(l) or BOLD_ONLY.match(l):
            continue
        c = clean(l)
        if not c:
            for m in RENDER.finditer(l):
                p = point(params(m.group(1)))
                if p is not None:
                    pending = p
                    break
            continue
        if c.endswith(":") and len(c.split(" ")) <= 4 or c.endswith("=") or c.startswith("[+") \
                or c.startswith("[-") or c.startswith("Category:"):
            continue
        flush()
        at = first_point(l, c)
        out.append({"text": c, "at": at if at is not None else pending})
        pending = None
        quoting = QUOTE_INTRO.search(c) is not None
    flush()
    return out


def steps_hash(st):
    """SHA-1 of the steps: "text<TAB>x,y,z" lines (y left empty when unknown, nothing after the
    tab when there is no place), joined by newlines."""
    lines = []
    for s in st:
        at = s["at"]
        where = "" if at is None else f"{at[0]},{'' if at[1] is None else at[1]},{at[2]}"
        lines.append(s["text"] + "\t" + where)
    return hashlib.sha1("\n".join(lines).encode("utf-8")).hexdigest()


# ---- entries --------------------------------------------------------------------------------

def infobox(page):
    """The infobox fields of {{Infobox/Town …}}, each a whole value (it may run over lines)."""
    start = page.find("{{Infobox/Town")
    if start < 0:
        return None
    end = matching_end(page, start)
    body = page[start + 2:end - 2] if end > 0 else page[start + 2:]
    fields = {}
    key = None
    for line in body.split("\n")[1:]:
        m = re.match(r"^\s*\|\s*([A-Za-z_]+)\s*=(.*)$", line, A)
        if m:
            key = m.group(1).lower()
            fields[key] = m.group(2)
        elif key is not None:
            fields[key] += "\n" + line
    return {k: trim(v) for k, v in fields.items()}


def regions(navbox):
    """Discovery name (lower case) → region, from the navbox's groups (Fruma by its parts)."""
    out = {}
    order = []
    groups = {}
    for m in re.finditer(r"\|\s*group(\d+)\s*=\s*(.*)", navbox, A):
        groups.setdefault(m.group(1), []).append(clean(m.group(2)))
    for m in re.finditer(r"\|\s*list(\d+)\s*=\s*(.*)", navbox, A):
        n = m.group(1)
        line = m.group(2)
        if "Navbox|child" in line:
            continue
        name = groups[n].pop(0) if groups.get(n) else "?"
        order.append(name)
        names = []
        for d in re.finditer(r"\{\{Dotlist\|([^{}]*)}}", line, A):
            names += [trim(x) for x in d.group(1).split(",")]
        names += [trim(x) for x in re.findall(r"\[\[([^\[\]|]*)]]", line, A)]
        for x in names:
            out[x.lower()] = name
    return out, order


def overview_heights(page):
    """Discovery name (lower case) → its {{Location}} point in the Secret Discoveries page's table."""
    out = {}
    name = None
    for line in page.split("\n"):
        m = re.search(r"'''\[\[([^\[\]|]*)(?:\|[^\[\]]*)?]]'''", line, A)
        if m:
            name = trim(m.group(1)).lower()
            continue
        m = re.search(r"\{\{Location(\|[^{}]*)}}", line, A)
        if m and name is not None:
            out[name] = point(params(m.group(1)))
            name = None
    return out


def requirements(value):
    """Infobox requirements → a list of texts ("None" gives an empty list)."""
    out = []
    for part in BR.split(value or ""):
        c = clean(part)
        if c and c.lower() not in ("none", "n/a", "-"):
            out.append(c)
    return out


def entry(title, page, where, heights):
    box = infobox(page)
    if box is None or trim(box.get("secret", "")) != "1":
        return None
    st = steps(page)
    x = number(box.get("xcoordinate"))
    z = number(box.get("zcoordinate"))
    y = number(box.get("ycoordinate"))
    table = heights.get(title.lower())
    if table and (x is None or z is None or abs(table[0] - x) + abs(table[2] - z) > 1000):
        x, y, z = table             # an infobox without coordinates, or with a typo
    if y is None and x is not None:
        # the height of a place near the infobox spot: the overview's table, then the page itself
        near = [s["at"] for s in st] + [point(params(m.group(1))) for m in RENDER.finditer(page)]
        for at in near + [table]:
            reach = 200 if at is table else 16
            if at and at[1] is not None and abs(at[0] - x) <= reach and abs(at[2] - z) <= reach:
                y = at[1]
                break
    level = number(re.sub(r"[^0-9]", "", box.get("minlevel", "")) or None)
    region = where.get(title.lower()) or where.get(clean(box.get("name", "")).lower(), "")
    return {
        "id": title,
        "name": clean(box.get("name", title)) or title,
        "wiki": PAGE + urllib.parse.quote(title.replace(" ", "_"), safe="_'(),"),
        "x": x, "y": y, "z": z,
        "minLevel": level or 1,
        "region": region,
        "requires": requirements(box.get("requirements")),
        "lore": clean(box.get("lore", "").replace("\n", " ")),
        "steps": st,
        "hash": steps_hash(st),
    }


def main():
    offline = "--offline" in sys.argv
    if not offline:
        fetch(CACHE)
    with open(os.path.join(CACHE, "_titles.json"), encoding="utf-8") as f:
        titles = json.load(f)
    with open(os.path.join(CACHE, file_name(NAVBOX)), encoding="utf-8") as f:
        where, order = regions(f.read())
    with open(os.path.join(CACHE, file_name("Secret Discoveries")), encoding="utf-8") as f:
        heights = overview_heights(f.read())
    fixes = {}
    if os.path.exists(FIXES):
        with open(FIXES, encoding="utf-8") as f:
            fixes = json.load(f)
    entries = []
    problems = []
    for t in titles:
        with open(os.path.join(CACHE, file_name(t)), encoding="utf-8") as f:
            e = entry(t, f.read(), where, heights)
        if e is None:
            continue
        for k, v in fixes.get(t, {}).items():
            if not k.startswith("_"):
                e[k] = v
        why = []
        if not e["steps"]:
            why.append("no step")
        if not any(s["at"] for s in e["steps"]) and e["x"] is None:
            why.append("no location")
        if e["y"] is None:
            why.append("no height")
        if not e["region"]:
            why.append("no region")
        if why:
            problems.append(f"{t}: {', '.join(why)}")
        entries.append(e)
    unused = sorted(set(fixes) - {e["id"] for e in entries})
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, "w", encoding="utf-8", newline="\n") as f:
        f.write("[\n" + ",\n".join(json.dumps(e, ensure_ascii=False) for e in entries) + "\n]\n")
    print(f"wrote {len(entries)} entries to {os.path.relpath(OUT, ROOT)}")
    for p in problems:
        print("  needs a fix: " + p)
    for u in unused:
        print("  fix for an unknown page: " + u)


if __name__ == "__main__":
    main()
