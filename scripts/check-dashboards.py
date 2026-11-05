#!/usr/bin/env python3
"""Validates the provisioned Grafana dashboards of README section 14.4.

A dashboard is configuration, so nothing in the Maven build would notice if one stopped
parsing, pointed at a datasource that is not provisioned, or laid two panels on top of each
other. What makes those worth a gate is how they fail: Grafana logs the provisioning error
and serves the folder without the dashboard, so the first person to find out is whoever
opens it during an incident.

It checks four things that can be checked without a Grafana:

  * the JSON parses and carries the fields the provider needs (uid, title, panels);
  * every uid is unique across the directory, since a collision makes one dashboard
    silently replace the other;
  * no two panels overlap on the 24 column grid, which renders as one hiding the other;
  * every query names the datasource uid that deploy/grafana/provisioning actually
    provisions, rather than Grafana's default of the moment.

The fifth check is the PromQL itself, and that one needs promtool. Given an output path this
script writes every expression out as an alerting rule, with the Grafana macros replaced by
fixed durations, so `promtool check rules` parses all of them in one pass. That is what the
CI step does with it.

Usage:
    python scripts/check-dashboards.py [--rules <path>] [<dashboard dir>]
"""

import argparse
import json
import pathlib
import sys

# What deploy/grafana/provisioning/datasources provisions. A panel naming anything else
# would render "datasource not found" in a Grafana nobody has clicked in.
PROMETHEUS_UID = "seatrush-prometheus"

# Grafana computes these from the panel width and the dashboard range. promtool cannot, so
# they are replaced with something representative purely to parse the expression.
MACROS = {"$__rate_interval": "1m", "$__range": "1h", "$__interval": "1m"}

GRID_WIDTH = 24


def panels_of(dashboard):
    """Every panel, including the ones nested inside a collapsed row."""
    for panel in dashboard.get("panels", []):
        yield panel
        for nested in panel.get("panels", []):
            yield nested


def check(path, uids, problems):
    try:
        dashboard = json.loads(path.read_text(encoding="utf-8"))
    except json.JSONDecodeError as broken:
        problems.append(f"{path.name}: is not valid JSON ({broken})")
        return []

    for field in ("uid", "title", "panels"):
        if not dashboard.get(field):
            problems.append(f"{path.name}: has no {field}")
    uid = dashboard.get("uid")
    if uid in uids:
        problems.append(f"{path.name}: uid {uid} is also used by {uids[uid]}")
    elif uid:
        uids[uid] = path.name

    # The grid is a fixed 24 columns and Grafana does not reflow: an overlap is a panel
    # drawn underneath another one.
    occupied = {}
    expressions = []
    for panel in panels_of(dashboard):
        title = panel.get("title") or panel.get("type")
        grid = panel.get("gridPos")
        if not grid:
            problems.append(f"{path.name}: panel {title!r} has no gridPos")
            continue
        if grid["x"] + grid["w"] > GRID_WIDTH:
            problems.append(f"{path.name}: panel {title!r} runs past the {GRID_WIDTH} column grid")
        # One line per overlapping pair and not per cell, since an overlap is tens of cells
        # and the reader only needs to know which two panels to move.
        clashes = set()
        for y in range(grid["y"], grid["y"] + grid["h"]):
            for x in range(grid["x"], grid["x"] + grid["w"]):
                other = occupied.get((x, y))
                if other:
                    clashes.add((other, x, y))
                occupied[(x, y)] = title
        for other in sorted({clash[0] for clash in clashes}):
            first = min(clash[1:] for clash in clashes if clash[0] == other)
            problems.append(
                f"{path.name}: panels {other!r} and {title!r} overlap, first at "
                f"column {first[0]} row {first[1]}"
            )

        for target in panel.get("targets", []):
            datasource = (target.get("datasource") or {}).get("uid")
            if datasource != PROMETHEUS_UID:
                problems.append(
                    f"{path.name}: target {target.get('refId')} of {title!r} names datasource "
                    f"{datasource!r} and not {PROMETHEUS_UID!r}"
                )
            expression = (target.get("expr") or "").strip()
            if not expression:
                problems.append(f"{path.name}: target {target.get('refId')} of {title!r} is empty")
                continue
            for macro, replacement in MACROS.items():
                expression = expression.replace(macro, replacement)
            expressions.append((f"{path.stem}:{target.get('refId')}:{title}", expression))
    return expressions


def write_rules(path, expressions):
    """Every expression as one alerting rule, for promtool to parse.

    The rule names are made from the dashboard and the panel, so a promtool failure points at
    the panel that has the typo rather than at a line number in a generated file.
    """
    lines = ["# Generated by scripts/check-dashboards.py. Not committed, not evaluated.", "groups:"]
    lines.append("  - name: dashboard-expressions")
    lines.append("    rules:")
    for index, (origin, expression) in enumerate(expressions):
        indented = expression.replace("\n", "\n          ")
        lines.append(f"      # {origin}")
        lines.append(f"      - alert: Expression{index}")
        lines.append("        expr: |")
        lines.append(f"          {indented}")
    path.write_text("\n".join(lines) + "\n", encoding="utf-8", newline="\n")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directory", nargs="?", default="deploy/grafana/dashboards")
    parser.add_argument("--rules", help="write the expressions here for promtool to parse")
    args = parser.parse_args()

    directory = pathlib.Path(args.directory)
    files = sorted(directory.glob("*.json"))
    if not files:
        print(f"no dashboards found in {directory}", file=sys.stderr)
        return 1

    problems = []
    uids = {}
    expressions = []
    for path in files:
        expressions.extend(check(path, uids, problems))

    for problem in problems:
        print(problem, file=sys.stderr)
    if problems:
        return 1

    print(f"{len(files)} dashboards, {len(expressions)} queries, {len(uids)} unique uids: ok")
    if args.rules:
        write_rules(pathlib.Path(args.rules), expressions)
        print(f"wrote {args.rules} for promtool")
    return 0


if __name__ == "__main__":
    sys.exit(main())
