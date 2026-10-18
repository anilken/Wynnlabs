"use strict";
// Works out the next release from the commits since the last v* tag, with the rules in
// .config.js, and writes its notes. Plain Node, no packages; it only reads git.
//
//   node .github/release-version.js [--notes <file>] [--head <ref>]
//
// Prints the version and the notes. In GitHub Actions it also sets the step outputs version,
// tag, previous, release_type and skipped (true when there is nothing to release).

const { execFileSync } = require("child_process");
const fs = require("fs");
const path = require("path");
const rules = require("./.config.js");

function arg(name, fallback) {
    const i = process.argv.indexOf(name);
    return i >= 0 && i + 1 < process.argv.length ? process.argv[i + 1] : fallback;
}

function git(...args) {
    return execFileSync("git", args, { encoding: "utf8", stdio: ["ignore", "pipe", "pipe"] }).trim();
}

function output(name, value) {
    if (process.env.GITHUB_OUTPUT) {
        fs.appendFileSync(process.env.GITHUB_OUTPUT, `${name}=${value}\n`);
    }
}

const head = arg("--head", "HEAD");
const notesFile = arg("--notes", null);
const repo = process.env.GITHUB_REPOSITORY || "anilken/wynnlabs";
const server = process.env.GITHUB_SERVER_URL || "https://github.com";
const repoUrl = `${server}/${repo}`;

// the last release: the newest vX.Y.Z tag reachable from head
let previous = null;
try {
    previous = git("describe", "--tags", "--abbrev=0", "--match", "v[0-9]*.[0-9]*.[0-9]*", head);
} catch (e) {
    previous = null;
}

// commit headers since then (merge commits left out: squash merges are the commits that count)
const range = previous ? `${previous}..${head}` : head;
const log = git("log", "--no-merges", "--format=%H%x1f%s%x1e", range);
const commits = log
    .split("\x1e")
    .map((r) => r.trim())
    .filter(Boolean)
    .map((r) => {
        const [sha, header] = r.split("\x1f");
        return { sha, header: header || "" };
    });

if (commits.length === 0) {
    console.log(`Nothing to release: no commits since ${previous || "the start"}.`);
    output("skipped", "true");
    process.exit(0);
}

const bump = rules.whatBump(commits.map((c) => c.header));
let version;
if (previous) {
    version = rules.nextVersion(previous.replace(/^v/, ""), bump.releaseType);
} else {
    // no tag yet: the first release is mod_version from gradle.properties
    const props = fs.readFileSync(path.join(git("rev-parse", "--show-toplevel"), "gradle.properties"), "utf8");
    const m = /^mod_version\s*=\s*(\S+)\s*$/m.exec(props);
    if (!m) {
        throw new Error("no v* tag and no mod_version in gradle.properties");
    }
    version = m[1];
}
const tag = `v${version}`;

let tagExists = false;
try {
    git("rev-parse", "--verify", "--quiet", `refs/tags/${tag}`);
    tagExists = true;
} catch (e) {
    tagExists = false;
}
if (tagExists) {
    console.error(`The tag ${tag} already exists: tag the last release properly or check the history.`);
    process.exit(1);
}

// the notes: one section per type, newest commit first, PR numbers and commits linked
function line(c) {
    const p = rules.parseHeader(c.header);
    let text = p ? p.subject : c.header;
    if (p && p.scope) {
        text = `**${p.scope}:** ${text}`;
    }
    text = text.replace(/\(#(\d+)\)/g, (_, n) => `([#${n}](${repoUrl}/pull/${n}))`);
    return `* ${text} ([${c.sha.slice(0, 7)}](${repoUrl}/commit/${c.sha}))`;
}

const today = new Date().toISOString().slice(0, 10);
const title = previous ? `[${tag}](${repoUrl}/compare/${previous}...${tag})` : tag;
const parts = [`## ${title} (${today})`, ""];
let shown = 0;
for (const s of rules.SECTIONS) {
    const inSection = commits.filter((c) => rules.sectionOf(c.header) === s.title);
    if (inSection.length === 0) {
        continue;
    }
    parts.push(`### ${s.title}`, "", ...inSection.map(line), "");
    shown += inSection.length;
}
if (shown === 0) {
    parts.push("Build and CI changes only.", "");
}
const notes = parts.join("\n");

if (notesFile) {
    fs.writeFileSync(notesFile, notes);
}
console.log(`Previous release: ${previous || "none"}`);
console.log(`Commits: ${commits.length}, bump: ${bump.releaseType} (${bump.reason})`);
console.log(`Next version: ${version}`);
console.log("");
console.log(notes);

output("version", version);
output("tag", tag);
output("previous", previous || "");
output("release_type", bump.releaseType);
output("skipped", "false");
