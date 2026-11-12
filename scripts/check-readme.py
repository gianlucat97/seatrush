#!/usr/bin/env python
"""The README against the repository it describes.

This file is the specification of the system and it is two thousand lines long, which is exactly
the condition under which a document stops being checked by anyone reading it. Every feature adds a
section and a dozen cross references to the sections around it, and none of that breaks a build:
a link to a heading that was renamed renders as a link, a path in a code span that moved renders as
a path, and a `make` target that was never added reads like a command somebody can run. The failure
is silent and it is cumulative.

So the five things a reader actually follows are checked here, and nothing else:

  anchors   every #link points at a heading, and at the section number the link says it does
  paths     every relative link and every repository path in a code span exists
  targets   every `make x` named in the prose is a target of the Makefile
  contents  the table of contents and the top level sections are the same list
  tree      every path drawn in the repository structure of section 20 exists

What it is not is a style checker. Prose is for people to argue about; these five are facts about
the tree, and a fact in a specification is either true or it is a bug in the specification.

    python scripts/check-readme.py

Exit codes are the ones the other validators use: 0 when everything resolves, 1 when something does
not. Run by CI next to the ADR, dashboard and chart checks.
"""

from __future__ import annotations

import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
README = ROOT / "README.md"
MAKEFILE = ROOT / "Makefile"

# Paths are only checked when they are certainly paths. A code span is also how this document
# writes shell, SQL, metric names and Java packages, so the test is a first segment that names a
# real top level directory of the repository plus a separator.
ROOTS = (
    "libs/",
    "services/",
    "frontend/",
    "load-tests/",
    "deploy/",
    "docs/",
    "scripts/",
    ".github/",
    ".mvn/",
)

# A path with one of these in it is a shape rather than a file: <service>, *.md, {id}. Skipped
# everywhere, because the thing being described is the pattern and not any one member of it.
PLACEHOLDERS = ("<", ">", "*", "{", "}", "?", "$")

# Files named in the tree of section 20 that the repository deliberately does not carry: .env is
# git ignored and the wrapper jar is the one binary .gitignore makes an exception for.
TREE_EXCEPTIONS = (".env",)

LINK = re.compile(r"\[(?P<text>[^\]]*)\]\((?P<href>[^)\s]+)\)")
CODE_SPAN = re.compile(r"`(?P<code>[^`\n]+)`")
HEADING = re.compile(r"^(?P<hashes>#{1,6})\s+(?P<title>.+?)\s*$", re.M)
MAKE_TARGET = re.compile(r"^(?P<name>[a-z][a-z0-9-]*):", re.M)
MAKE_CALL = re.compile(r"\bmake\s+(?P<target>[a-z][a-z0-9-]*)\b")
TOC_ENTRY = re.compile(r"^\d+\.\s+\[(?P<title>[^\]]+)\]\(#(?P<anchor>[^)]+)\)\s*$", re.M)
FENCE = re.compile(r"^```.*?^```", re.M | re.S)
# The number a link says it goes to: "section 12.2" or the section sign this document uses.
SECTION_NUMBER = re.compile(r"(?:§|[Ss]ection\s+)(?P<number>\d+(?:\.\d+)*)")


def slug(title: str) -> str:
    """The anchor GitHub gives a heading.

    Lowercased, everything that is not a letter, a digit, a space, a hyphen or an underscore
    dropped, and the spaces turned into hyphens. That is why `§12.6 Error codes` is reached as
    #126-error-codes and why a heading that names a path loses its slashes.
    """
    text = title.strip().lower()
    text = re.sub(r"[^\w\s-]", "", text, flags=re.UNICODE)
    return re.sub(r"\s+", "-", text).strip("-")


def headings_by_anchor(readme: str) -> dict[str, str]:
    """Every anchor this document offers and the heading behind it.

    The -1, -2 suffixes are the ones GitHub appends when two headings slug the same, which is why
    this counts rather than collecting into a set.
    """
    seen: dict[str, int] = {}
    offered: dict[str, str] = {}
    for heading in HEADING.finditer(strip_code(readme)):
        title = heading.group("title")
        base = slug(title)
        count = seen.get(base, 0)
        seen[base] = count + 1
        offered[base if count == 0 else f"{base}-{count}"] = title
    return offered


def strip_code(text: str) -> str:
    """The document with its fenced blocks blanked out, line count preserved.

    A fenced block holds shell, YAML and SQL, and every one of those has lines that look like a
    heading or a link to a regular expression. Blanking rather than deleting keeps the line numbers
    of everything after it honest.
    """
    return FENCE.sub(lambda m: "\n" * m.group(0).count("\n"), text)


def line_of(text: str, index: int) -> int:
    """The line an offset falls on.

    Always called with the same string the offset came from. The stripped document has the line
    count of the original but not its length, so an offset taken from one and counted in the other
    names a line a long way from the problem, which is how the first run of this script reported
    four broken links against four innocent paragraphs.
    """
    return text.count("\n", 0, index) + 1


def check_anchors(readme: str) -> list[str]:
    """Every #link against the headings, and every section number against the heading it reaches.

    The second half is the one that earns its keep in a document written in cross references. A
    link reads [section 12.2] and points at an anchor, and those are two separate things to get
    right: renaming a heading breaks the anchor, which is loud, and renumbering a section leaves
    every link to it pointing somewhere real and wrong, which is silent.
    """
    offered = headings_by_anchor(readme)
    body = strip_code(readme)
    problems = []
    for link in LINK.finditer(body):
        href = link.group("href")
        if not href.startswith("#"):
            continue
        anchor = href[1:]
        if anchor not in offered:
            problems.append(
                f"line {line_of(body, link.start())}: [{link.group('text')}]({href}) "
                f"points at no heading of this document"
            )
            continue
        said = SECTION_NUMBER.search(link.group("text"))
        if said is None:
            continue
        reached = offered[anchor].split(" ", 1)[0].rstrip(".")
        if said.group("number") != reached:
            problems.append(
                f"line {line_of(body, link.start())}: [{link.group('text')}]({href}) reaches "
                f"section {reached}, '{offered[anchor]}'"
            )
    return problems


def check_paths(readme: str) -> list[str]:
    """Relative links and repository paths in code spans, against the tree."""
    problems = []
    body = strip_code(readme)

    for link in LINK.finditer(body):
        href = link.group("href")
        if href.startswith(("#", "http://", "https://", "mailto:")):
            continue
        target = href.split("#", 1)[0]
        if not target or any(ch in target for ch in PLACEHOLDERS):
            continue
        if not (ROOT / target).exists():
            problems.append(
                f"line {line_of(body, link.start())}: [{link.group('text')}]({href}) "
                f"links to a file that is not there"
            )

    for span in CODE_SPAN.finditer(body):
        code = span.group("code").strip()
        if not code.startswith(ROOTS) or any(ch in code for ch in PLACEHOLDERS):
            continue
        # A code span can hold a path and then say something about it: `deploy/grafana/dashboards`
        # is a path and `scripts/load.sh full` is a command. The path is the first word either way.
        path = code.split()[0].rstrip(",.;:")
        if not (ROOT / path).exists():
            problems.append(
                f"line {line_of(body, span.start())}: `{code}` names a path that is not there"
            )
    return problems


def check_make_targets(readme: str) -> list[str]:
    """Every `make x` in the prose against the Makefile.

    Only inside code spans and fenced blocks, because that is how this document writes a command.
    A sentence about making something is prose and has no target behind it.
    """
    targets = set(MAKE_TARGET.findall(MAKEFILE.read_text(encoding="utf-8")))
    problems = []
    spans = [(m.group("code"), m.start()) for m in CODE_SPAN.finditer(readme)]
    spans += [(m.group(0), m.start()) for m in FENCE.finditer(readme)]
    for code, start in spans:
        for call in MAKE_CALL.finditer(code):
            name = call.group("target")
            if name not in targets:
                problems.append(
                    f"line {line_of(readme, start)}: `make {name}` is not a target of the Makefile"
                )
    return problems


def check_contents(readme: str) -> list[str]:
    """The table of contents and the `## N.` headings, in both directions and in order."""
    listed = [(entry.group("title"), entry.group("anchor")) for entry in TOC_ENTRY.finditer(readme)]
    sections = [
        heading.group("title")
        for heading in HEADING.finditer(strip_code(readme))
        if heading.group("hashes") == "##" and re.match(r"^\d+\.\s", heading.group("title"))
    ]
    problems = []
    if not listed:
        return ["the table of contents is empty, which cannot be right"]

    numbered = {}
    for title in sections:
        number, _, rest = title.partition(". ")
        numbered[number] = rest

    for position, (title, anchor) in enumerate(listed, start=1):
        number = str(position)
        if number not in numbered:
            problems.append(
                f"the contents list section {number} '{title}' and there is no such section"
            )
            continue
        if numbered[number] != title:
            problems.append(
                f"section {number} is titled\n"
                f"      heading:  {numbered[number]}\n"
                f"      contents: {title}"
            )
        if anchor != slug(f"{number}. {numbered[number]}"):
            problems.append(f"the contents link to section {number} by the wrong anchor: #{anchor}")

    for number in sorted(numbered, key=int):
        if int(number) > len(listed):
            problems.append(
                f"section {number} '{numbered[number]}' is listed in no table of contents entry"
            )
    return problems


def check_tree(readme: str) -> list[str]:
    """Every path drawn in the repository structure of section 20, against the tree.

    The drawing is the first thing a reader looks at and the last thing anybody updates, which is
    how it came to list two directories that had never held a file. Parsed by the indentation of
    the box drawing characters, the same way it is read.
    """
    block = re.search(r"^## 20\..*?^```\n(?P<tree>.*?)^```", readme, re.M | re.S)
    if block is None:
        return ["section 20 has no repository structure block"]

    problems = []
    stack: list[tuple[int, str]] = []
    for line in block.group("tree").splitlines():
        # The drawing without its comment. A line that is nothing but a comment is the second line
        # of one that was too long, and it carries no path however much it looks like prose.
        drawing = line.split("#", 1)[0]
        entry = re.match(r"^(?P<indent>[\s│├└─]*)(?P<names>\S.*?)\s*$", drawing)
        if entry is None or not re.search(r"[A-Za-z0-9]", entry.group("names")):
            continue
        names = entry.group("names")
        depth = len(entry.group("indent"))
        while stack and stack[-1][0] >= depth:
            stack.pop()
        if depth == 0 and not stack and names.endswith("/"):
            # The root of the drawing, which is the repository itself.
            stack.append((depth, ""))
            continue

        parent = stack[-1][1] if stack else ""
        # One cell can list two entries: "mvnw, .mvn/". Neither of those has children drawn under
        # it, so a cell like that opens no directory.
        pieces = [piece.strip() for piece in names.split(",") if piece.strip()]
        if len(pieces) == 1 and names.endswith("/"):
            stack.append((depth, f"{parent}{names.rstrip('/')}/"))
        for piece in pieces:
            path = f"{parent}{piece.rstrip('/')}"
            if any(ch in path for ch in PLACEHOLDERS) or path.endswith(TREE_EXCEPTIONS):
                continue
            if not (ROOT / path).exists():
                problems.append(
                    f"the tree of section 20 draws {path}, which is not in the repository"
                )
    return problems


def main() -> int:
    readme = README.read_text(encoding="utf-8")
    checks = (
        ("anchors", check_anchors(readme)),
        ("paths", check_paths(readme)),
        ("make targets", check_make_targets(readme)),
        ("contents", check_contents(readme)),
        ("the tree of section 20", check_tree(readme)),
    )
    problems = [(name, problem) for name, found in checks for problem in found]
    if problems:
        print(f"{len(problems)} problem(s) in README.md:", file=sys.stderr)
        for name, problem in problems:
            print(f"  [{name}] {problem}", file=sys.stderr)
        return 1

    offered = len(headings_by_anchor(readme))
    print(f"README.md resolves: {offered} headings, and every link, path and target it names")
    return 0


if __name__ == "__main__":
    sys.exit(main())
