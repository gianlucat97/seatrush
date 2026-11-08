#!/usr/bin/env python
"""The ADRs of README section 25 against the files in docs/adr/.

An index and the things it indexes drift quietly, which is the one failure worth a script here. A
table row with no file is a decision somebody looked for and did not find; a file with no row is a
decision nobody will ever look for. Neither breaks a build, neither breaks a deployment, and both
make the section a claim rather than a record.

So this checks the correspondence in both directions, that the titles agree exactly, that each link
points at the file it names, and that every ADR carries the four sections section 25 promises plus
the status and the milestone. Run by CI next to the dashboard and chart validators, for the same
reason they are there.

    python scripts/check-adrs.py
"""

from __future__ import annotations

import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
README = ROOT / "README.md"
ADR_DIR = ROOT / "docs" / "adr"

# The four headings README section 25 states the format as, plus the two metadata lines every ADR
# carries. The headings are matched exactly, because a format that is almost followed is a format
# nobody can rely on when reading twenty of them in a row.
REQUIRED_SECTIONS = ("## Context", "## Decision", "## Consequences", "## Alternatives considered")
REQUIRED_FIELDS = ("- **Status**:", "- **Milestone**:")

# | 0001 | [Orchestrated SAGA instead of ...](docs/adr/0001-orchestrated-saga-for-checkout.md) |
TABLE_ROW = re.compile(
    r"^\|\s*(?P<number>\d{4})\s*\|\s*\[(?P<title>[^\]]+)\]\((?P<link>[^)]+)\)\s*\|$", re.M
)
# A row that was never linked, which is the shape the table had before the files existed.
BARE_ROW = re.compile(r"^\|\s*(?P<number>\d{4})\s*\|\s*(?P<title>[^|\[]+?)\s*\|$", re.M)

HEADING = re.compile(r"^#\s*ADR-(?P<number>\d{4}):\s*(?P<title>.+?)\s*$", re.M)


def main() -> int:
    problems: list[str] = []

    readme = README.read_text(encoding="utf-8")
    rows = {int(m.group("number")): m for m in TABLE_ROW.finditer(readme)}
    for bare in BARE_ROW.finditer(readme):
        number = int(bare.group("number"))
        if number not in rows:
            problems.append(
                f"README row {number:04d} does not link to its file: the table of section 25 is "
                f"the index, so every row points at docs/adr/"
            )

    if not rows:
        problems.append("no ADR rows found in README section 25, which cannot be right")

    files = {}
    for path in sorted(ADR_DIR.glob("*.md")):
        if not re.match(r"^\d{4}-[a-z0-9-]+\.md$", path.name):
            problems.append(f"{path.name} is not named NNNN-slug.md")
            continue
        files[int(path.name[:4])] = path

    # Both directions, because the two mistakes are different and only one of them is visible while
    # writing an ADR.
    for number in sorted(set(rows) - set(files)):
        problems.append(f"README lists ADR-{number:04d} and docs/adr/ has no file for it")
    for number in sorted(set(files) - set(rows)):
        problems.append(
            f"docs/adr/{files[number].name} exists and no row of README section 25 names it"
        )

    for number in sorted(set(rows) & set(files)):
        problems.extend(check_one(number, rows[number], files[number]))

    if problems:
        print(f"{len(problems)} problem(s) with the ADRs:", file=sys.stderr)
        for problem in problems:
            print(f"  {problem}", file=sys.stderr)
        return 1

    print(f"{len(files)} ADRs, each with a row of README section 25 and the four sections it asks for")
    return 0


def check_one(number: int, row: re.Match[str], path: pathlib.Path) -> list[str]:
    problems: list[str] = []
    name = path.name
    expected_link = f"docs/adr/{name}"
    if row.group("link") != expected_link:
        problems.append(
            f"README row {number:04d} links to {row.group('link')} and the file is {expected_link}"
        )

    text = path.read_text(encoding="utf-8")
    heading = HEADING.search(text)
    if heading is None:
        problems.append(f"{name} has no '# ADR-{number:04d}: <title>' heading")
    else:
        if int(heading.group("number")) != number:
            problems.append(
                f"{name} is numbered ADR-{heading.group('number')} inside and {number:04d} by its name"
            )
        # Exactly and not loosely: the table is what a reader searches, so a title that has drifted
        # is a decision they will not find by the name they remember.
        if heading.group("title") != row.group("title"):
            problems.append(
                f"ADR-{number:04d} is titled\n"
                f"      file:   {heading.group('title')}\n"
                f"      README: {row.group('title')}"
            )

    for section in REQUIRED_SECTIONS:
        if not re.search(rf"^{re.escape(section)}\s*$", text, re.M):
            problems.append(f"{name} has no '{section}' section")
    for field in REQUIRED_FIELDS:
        if field not in text:
            problems.append(f"{name} has no '{field}' line")

    # A section heading that exists and says nothing is the one way this check can be satisfied
    # without an ADR being written, so the body of each one has to be there.
    for section in REQUIRED_SECTIONS:
        body = section_body(text, section)
        if body is not None and len(body.split()) < 20:
            problems.append(f"{name} has a '{section}' section with almost nothing in it")

    return problems


def section_body(text: str, section: str) -> str | None:
    """What is under one heading, up to the next one of the same level."""
    match = re.search(rf"^{re.escape(section)}\s*$(?P<body>.*?)(?=^## |\Z)", text, re.M | re.S)
    return None if match is None else match.group("body")


if __name__ == "__main__":
    sys.exit(main())
