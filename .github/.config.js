"use strict";
// Release rules for Wynn Labs: how a commit header bumps the version and which section of the
// release notes it goes in. Commit headers follow Conventional Commits, "type(scope)!: subject";
// a squash merge uses the PR title as its header. Read by .github/release-version.js (plain Node,
// no packages).
//
// Bump, the highest one among the commits since the last v* tag wins:
//   chore(bump-mc), feat(major), chore!   -> major (a new Minecraft version, a big rework)
//   feat!, fix!                           -> minor
//   anything else, other headers included -> patch

const RANK = { patch: 0, minor: 1, major: 2 };

/** The notes' sections in order; a header whose type is in none of them goes in the last one. */
const SECTIONS = [
    { title: "New Features", types: ["feat", "feature"] },
    { title: "Bug Fixes", types: ["fix"] },
    { title: "Performance", types: ["perf"] },
    { title: "Documentation", types: ["docs"] },
    { title: "Code Refactoring", types: ["refactor"] },
    { title: "Tests", types: ["test", "tests"] },
    { title: "Miscellaneous", types: [] },
];

/** Types left out of the notes (they still count for the bump). */
const HIDDEN = ["ci"];

/** "type(scope)!: subject" as its parts, or null for a header that is not Conventional Commits. */
function parseHeader(header) {
    const m = /^([a-z]+)(?:\(([^)]*)\))?(!)?:\s*(\S.*)$/.exec((header || "").trim());
    if (!m) {
        return null;
    }
    return {
        type: m[1].toLowerCase(),
        scope: m[2] ? m[2].trim().toLowerCase() : null,
        breaking: m[3] === "!",
        subject: m[4].trim(),
    };
}

/** The bump one header asks for, with the reason. */
function bumpOf(header) {
    const c = parseHeader(header);
    if (c) {
        if (c.type === "chore" && c.scope === "bump-mc") {
            return { releaseType: "major", reason: "chore(bump-mc)" };
        }
        if (c.type === "feat" && c.scope === "major") {
            return { releaseType: "major", reason: "feat(major)" };
        }
        if (c.type === "chore" && c.breaking) {
            return { releaseType: "major", reason: "chore!" };
        }
        if ((c.type === "feat" || c.type === "fix") && c.breaking) {
            return { releaseType: "minor", reason: `${c.type}!` };
        }
    }
    return { releaseType: "patch", reason: "no major or minor header" };
}

/** The bump for a list of headers: the highest one, patch when there is nothing special. */
function whatBump(headers) {
    let best = { releaseType: "patch", reason: "no major or minor header, defaulting to a patch" };
    for (const header of headers) {
        const b = bumpOf(header);
        if (RANK[b.releaseType] > RANK[best.releaseType]) {
            best = { releaseType: b.releaseType, reason: `found a ${b.reason} header: ${header}` };
        }
    }
    return best;
}

/** "1.2.3" bumped by "major", "minor" or "patch". */
function nextVersion(version, releaseType) {
    const m = /^(\d+)\.(\d+)\.(\d+)$/.exec(version);
    if (!m) {
        throw new Error(`not a x.y.z version: ${version}`);
    }
    const [major, minor, patch] = m.slice(1).map(Number);
    switch (releaseType) {
        case "major":
            return `${major + 1}.0.0`;
        case "minor":
            return `${major}.${minor + 1}.0`;
        default:
            return `${major}.${minor}.${patch + 1}`;
    }
}

/** The section title a header goes under, or null when its type is hidden. */
function sectionOf(header) {
    const c = parseHeader(header);
    if (c && HIDDEN.includes(c.type)) {
        return null;
    }
    const s = c && SECTIONS.find((x) => x.types.includes(c.type));
    return (s || SECTIONS[SECTIONS.length - 1]).title;
}

module.exports = { SECTIONS, HIDDEN, parseHeader, bumpOf, whatBump, nextVersion, sectionOf };
