#!/usr/bin/env python
"""The functional specification against itself and against the repository.

`docs/frd/` is the functional specification: forty-seven numbered requirements, a traceability
matrix that points every one of them at a README section, an ADR and a test, and three chapters of
prose that cite requirement ids in passing. That is a document whose every cross reference is a
fact about something else, and none of those facts breaks a build. A requirement renamed in the
catalogue and not in the matrix still renders. A test method renamed in Java leaves a row here
naming a method nobody can find. A requirement cited in a journey and never defined reads exactly
like one that was.

The same argument as `scripts/check-readme.py`, and the same five-things-only discipline:

  requirements  every FR id cited anywhere is defined exactly once in the catalogue
  traceability  the catalogue and the matrix name the same set of requirements, both ways
  structure     every requirement carries the five parts the index promises a reader
  references    every link resolves: a file, a heading of this document, or a README section
  repository    every path in a code span exists, every `make x` is a target, every test exists

What it is not is a style checker, and it deliberately does not read the prose. These five are
facts about the tree and about this document own numbering, and a fact in a specification is either
true or it is a bug in the specification.

    python scripts/check-frd.py

Exit codes are the ones the other validators use: 0 when everything resolves, 1 when something does
not. Run by CI next to the README, ADR, dashboard and chart checks.
"""

from __future__ import annotations

import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
FRD = ROOT / "docs" / "frd"
README = ROOT / "README.md"
MAKEFILE = ROOT / "Makefile"

CATALOGUE = "03-fr-catalog.md"
MATRIX = "06-traceability.md"
INDEX = "README.md"

# The five parts the index of the document promises a reader finds in every requirement. A
# requirement missing one is not a formatting slip: it is a requirement that cannot be tested,
# because one of these is the part that says what "satisfied" means.
PARTS = ("**Actor**", "**Precondition**", "**Main flow**", "**Alternative flows**",
         "**Acceptance criteria**")

# Same test as check-readme.py: a code span is also how this document writes shell, properties and
# Java identifiers, so a path is only checked when its first segment names a real top level
# directory of the repository.
ROOTS = ("libs/", "services/", "frontend/", "load-tests/", "deploy/", "docs/", "scripts/",
         ".github/", ".mvn/")

PLACEHOLDERS = ("<", ">", "*", "{", "}", "?", "$")

LINK = re.compile(r"\[(?P<text>[^\]]*)\]\((?P<href>[^)\s]+)\)")
CODE_SPAN = re.compile(r"`(?P<code>[^`\n]+)`")
HEADING = re.compile(r"^(?P<hashes>#{1,6})\s+(?P<title>.+?)\s*$", re.M)
FENCE = re.compile(r"^```.*?^```", re.M | re.S)
MAKE_TARGET = re.compile(r"^(?P<name>[a-z][a-z0-9-]*):", re.M)
MAKE_CALL = re.compile(r"\bmake\s+(?P<target>[a-z][a-z0-9-]*)\b")

# FR-1.1, and the heading that defines one. A citation is any other appearance of the same shape.
FR_ID = re.compile(r"\bFR-(?P<group>\d+)\.(?P<number>\d+)\b")
FR_HEADING = re.compile(r"^###\s+FR-(?P<group>\d+)\.(?P<number>\d+)\s+(?P<title>.+?)\s*$", re.M)
# A row of the matrix: the requirement is the first cell, in a code span.
FR_ROW = re.compile(r"^\|\s*`FR-(?P<group>\d+)\.(?P<number>\d+)`\s*\|", re.M)
# A Java test, named either as a class or as one of its methods.
TEST_NAME = re.compile(r"^(?P<cls>[A-Z][A-Za-z0-9]*(?:Test|Tests|IT))(?:\.(?P<method>[a-z][A-Za-z0-9]*))?$")
# The number a link says it goes to, the same two spellings README.md uses.
SECTION_NUMBER = re.compile(r"(?:§|[Ss]ection\s+)(?P<number>\d+(?:\.\d+)*)")


def slug(title: str) -> str:
    """The anchor GitHub gives a heading. Same rule as check-readme.py."""
    text = title.strip().lower()
    text = re.sub(r"[^\w\s-]", "", text, flags=re.UNICODE)
    return re.sub(r"\s+", "-", text).strip("-")


def strip_code(text: str) -> str:
    """The document with its fenced blocks blanked out, line count preserved."""
    return FENCE.sub(lambda m: "\n" * m.group(0).count("\n"), text)


def line_of(text: str, index: int) -> int:
    """The line an offset falls on. Always called with the string the offset came from."""
    return text.count("\n", 0, index) + 1


def anchors_of(text: str) -> set[str]:
    """Every anchor a document offers, with the -1, -2 suffixes GitHub appends to collisions."""
    seen: dict[str, int] = {}
    offered: set[str] = set()
    for heading in HEADING.finditer(strip_code(text)):
        base = slug(heading.group("title"))
        count = seen.get(base, 0)
        seen[base] = count + 1
        offered.add(base if count == 0 else f"{base}-{count}")
    return offered


def readme_sections() -> dict[str, str]:
    """Every anchor of the root README and the section number behind it."""
    text = strip_code(README.read_text(encoding="utf-8"))
    seen: dict[str, int] = {}
    offered: dict[str, str] = {}
    for heading in HEADING.finditer(text):
        title = heading.group("title")
        base = slug(title)
        count = seen.get(base, 0)
        seen[base] = count + 1
        offered[base if count == 0 else f"{base}-{count}"] = title
    return offered


def chapters() -> list[pathlib.Path]:
    """The documents of the specification, in the order their names sort."""
    return sorted(FRD.glob("*.md"), key=lambda p: p.name)


def defined() -> dict[str, int]:
    """Every requirement the catalogue defines, and the line it is defined on."""
    text = (FRD / CATALOGUE).read_text(encoding="utf-8")
    found: dict[str, int] = {}
    for heading in FR_HEADING.finditer(text):
        found[f"FR-{heading.group('group')}.{heading.group('number')}"] = line_of(
            text, heading.start())
    return found


def check_requirements() -> list[str]:
    """Every FR id cited anywhere against the catalogue, and every definition against duplicates."""
    text = (FRD / CATALOGUE).read_text(encoding="utf-8")
    problems = []

    counted: dict[str, int] = {}
    for heading in FR_HEADING.finditer(text):
        key = f"FR-{heading.group('group')}.{heading.group('number')}"
        counted[key] = counted.get(key, 0) + 1
    for key, count in sorted(counted.items()):
        if count > 1:
            problems.append(f"{CATALOGUE}: {key} is defined {count} times")

    known = set(counted)
    for chapter in chapters():
        body = strip_code(chapter.read_text(encoding="utf-8"))
        for cited in FR_ID.finditer(body):
            key = f"FR-{cited.group('group')}.{cited.group('number')}"
            if key not in known:
                problems.append(
                    f"{chapter.name} line {line_of(body, cited.start())}: {key} is cited and "
                    f"{CATALOGUE} defines no such requirement")
    return problems


def check_traceability() -> list[str]:
    """The catalogue and the matrix against each other, in both directions."""
    text = (FRD / MATRIX).read_text(encoding="utf-8")
    traced = {f"FR-{row.group('group')}.{row.group('number')}" for row in FR_ROW.finditer(text)}
    catalogued = set(defined())

    problems = []
    for key in sorted(catalogued - traced):
        problems.append(f"{key} is defined in {CATALOGUE} and has no row in {MATRIX}")
    for key in sorted(traced - catalogued):
        problems.append(f"{key} has a row in {MATRIX} and is defined in no chapter")
    return problems


def check_structure() -> list[str]:
    """Every requirement against the five parts the index promises a reader."""
    text = (FRD / CATALOGUE).read_text(encoding="utf-8")
    starts = [(f"FR-{m.group('group')}.{m.group('number')}", m.start())
              for m in FR_HEADING.finditer(text)]
    problems = []
    for position, (key, start) in enumerate(starts):
        end = starts[position + 1][1] if position + 1 < len(starts) else len(text)
        body = text[start:end]
        missing = [part for part in PARTS if part not in body]
        if missing:
            problems.append(
                f"{key} (line {line_of(text, start)}) is missing "
                f"{', '.join(part.strip('*') for part in missing)}")
    return problems


def check_references() -> list[str]:
    """Every link of every chapter: a file that exists, an anchor that is offered, a number that
    matches the heading it reaches.

    The last of those is the one that earns its keep. A link reads "section 12.2" and points at an
    anchor, and those are two separate things to get right: renaming a README heading breaks the
    anchor, which is loud, and renumbering a section leaves every link pointing somewhere real and
    wrong, which is silent.
    """
    sections = readme_sections()
    own: dict[str, set[str]] = {}
    for chapter in chapters():
        own[chapter.name] = anchors_of(chapter.read_text(encoding="utf-8"))

    problems = []
    for chapter in chapters():
        body = strip_code(chapter.read_text(encoding="utf-8"))
        for link in LINK.finditer(body):
            href = link.group("href")
            where = f"{chapter.name} line {line_of(body, link.start())}"
            if href.startswith(("http://", "https://", "mailto:")):
                continue

            target, _, anchor = href.partition("#")

            if not target:
                if anchor not in own[chapter.name]:
                    problems.append(f"{where}: #{anchor} is no heading of this chapter")
                continue

            if any(ch in target for ch in PLACEHOLDERS):
                continue

            resolved = (FRD / target).resolve()
            if not resolved.exists():
                problems.append(f"{where}: {target} is not there")
                continue

            if not anchor:
                continue

            if resolved == README.resolve():
                if anchor not in sections:
                    problems.append(f"{where}: #{anchor} is no heading of README.md")
                    continue
                said = SECTION_NUMBER.search(link.group("text"))
                reached = sections[anchor].split(" ", 1)[0].rstrip(".")
                if said is not None and said.group("number") != reached:
                    problems.append(
                        f"{where}: [{link.group('text')}] reaches README section {reached}, "
                        f"'{sections[anchor]}'")
            elif resolved.parent == FRD and resolved.name in own:
                if anchor not in own[resolved.name]:
                    problems.append(f"{where}: #{anchor} is no heading of {resolved.name}")
    return problems


def check_repository() -> list[str]:
    """Paths, make targets and test names, against the tree.

    Test names are checked by searching for the declaration rather than by parsing Java: a class is
    a file of that name, a method is that identifier followed by a parenthesis in it. Enough to
    catch the rename, which is the failure this is for.
    """
    targets = set(MAKE_TARGET.findall(MAKEFILE.read_text(encoding="utf-8")))
    java = {path.stem: path for path in ROOT.glob("**/src/test/java/**/*.java")}

    problems = []
    for chapter in chapters():
        raw = chapter.read_text(encoding="utf-8")
        body = strip_code(raw)

        for span in CODE_SPAN.finditer(body):
            code = span.group("code").strip()
            where = f"{chapter.name} line {line_of(body, span.start())}"

            if code.startswith(ROOTS) and not any(ch in code for ch in PLACEHOLDERS):
                path = code.split()[0].rstrip(",.;:")
                if not (ROOT / path).exists():
                    problems.append(f"{where}: `{code}` names a path that is not there")
                continue

            test = TEST_NAME.match(code)
            if test is None:
                continue
            source = java.get(test.group("cls"))
            if source is None:
                problems.append(f"{where}: `{code}` names no test class in the repository")
                continue
            method = test.group("method")
            if method and f"{method}(" not in source.read_text(encoding="utf-8"):
                problems.append(f"{where}: `{code}` names no method of {source.name}")

        for code, start in ([(m.group("code"), m.start()) for m in CODE_SPAN.finditer(raw)]
                            + [(m.group(0), m.start()) for m in FENCE.finditer(raw)]):
            for call in MAKE_CALL.finditer(code):
                if call.group("target") not in targets:
                    problems.append(
                        f"{chapter.name} line {line_of(raw, start)}: `make {call.group('target')}` "
                        f"is not a target of the Makefile")
    return problems


def check_index() -> list[str]:
    """Every chapter against the index, both ways.

    A chapter nobody links to is a chapter nobody reads, and an index naming a chapter that was
    renamed is a broken link the reference check would catch but would not explain.
    """
    index = (FRD / INDEX).read_text(encoding="utf-8")
    listed = {link.group("href").partition("#")[0]
              for link in LINK.finditer(strip_code(index))}
    problems = []
    for chapter in chapters():
        if chapter.name == INDEX:
            continue
        if chapter.name not in listed:
            problems.append(f"{chapter.name} is listed in no link of {INDEX}")
    return problems


def main() -> int:
    if not FRD.is_dir():
        print(f"{FRD} is not there", file=sys.stderr)
        return 1

    checks = (
        ("requirements", check_requirements()),
        ("traceability", check_traceability()),
        ("structure", check_structure()),
        ("references", check_references()),
        ("the repository", check_repository()),
        ("the index", check_index()),
    )
    problems = [(name, problem) for name, found in checks for problem in found]
    if problems:
        print(f"{len(problems)} problem(s) in docs/frd/:", file=sys.stderr)
        for name, problem in problems:
            print(f"  [{name}] {problem}", file=sys.stderr)
        return 1

    requirements = len(defined())
    print(f"docs/frd/ resolves: {len(chapters())} chapters, {requirements} requirements, "
          f"each traced once and each reference it names")
    return 0


if __name__ == "__main__":
    sys.exit(main())
