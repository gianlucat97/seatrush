#!/usr/bin/env python3
"""The readings behind the results table of README section 18.3.

One query per figure that table asks for, asked of Prometheus over the window of one run, printed
as a text file that is published next to the Gatling report. It exists because three of those rows
cannot be answered by the load test at all: seat map propagation is the time between a seat moving
and a watcher being told, which only the realtime service sees both ends of; the seats sold and the
compensations are counters of the services; and the moment the venue sold out is a reading of the
Redis counter the sale is bounded by, taken on a schedule and not on a request.

It is deliberately not a verdict. The gate on a run is the invariant checker of section 17.1 and
the assertions of the simulation, both of which exit non zero on their own; what this produces is
the evidence somebody fills the table in from, so the published figures can be traced to a query
rather than to a screenshot.

    scripts/benchmark-metrics.py --event <uuid> --from <epoch> --to <epoch>
    scripts/benchmark-metrics.py --event <uuid> --from <epoch> --to <epoch> --url http://host:9090

Exit codes follow scripts/check.sh, so a publish script can read them: 0 when every query was
answered, 2 when Prometheus could not be reached or answered an error. There is no 1: this tool
reports and never judges.
"""

from __future__ import annotations

import argparse
import json
import sys
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime, timezone

# Where the local stack publishes Prometheus (README section 23). A cluster has no published port,
# so there it is a port forward and --url.
DEFAULT_URL = "http://localhost:9090"

# How fine a reading of a gauge can be. The gauges of section 14.1 are sampled by a scheduled job
# and then scraped every ten seconds, so a question about when a value changed cannot be answered
# more precisely than that however small a step is asked for. Printed next to every answer that
# depends on it, because a figure quoted to the second out of a ten second series is a lie about
# its own precision.
GAUGE_STEP_SECONDS = 5
SCRAPE_INTERVAL_SECONDS = 10


class PrometheusError(RuntimeError):
    """A query Prometheus refused, or a Prometheus that is not there."""


class Prometheus:
    def __init__(self, url: str, timeout: float = 30.0) -> None:
        self.url = url.rstrip("/")
        self.timeout = timeout

    def instant(self, query: str, at: int) -> list[dict]:
        return self._get("/api/v1/query", {"query": query, "time": str(at)})["result"]

    def series(self, query: str, start: int, end: int, step: int) -> list[dict]:
        return self._get(
            "/api/v1/query_range",
            {"query": query, "start": str(start), "end": str(end), "step": f"{step}s"},
        )["result"]

    def _get(self, path: str, params: dict[str, str]) -> dict:
        request = urllib.request.Request(
            self.url + path + "?" + urllib.parse.urlencode(params),
            headers={"Accept": "application/json"},
        )
        try:
            with urllib.request.urlopen(request, timeout=self.timeout) as response:
                body = json.load(response)
        except urllib.error.HTTPError as error:
            # Prometheus puts the reason a query was refused in the body of a 400, and that reason
            # is the whole message: "parse error at char 23" names the typo, where the status code
            # alone sends the reader to the wrong place.
            detail = error.read().decode("utf-8", "replace")[:500]
            raise PrometheusError(f"{path} answered {error.code}: {detail}") from error
        except OSError as error:
            raise PrometheusError(f"{self.url} does not answer: {error}") from error
        if body.get("status") != "success":
            raise PrometheusError(f"{path} answered {body.get('error', body.get('status'))}")
        return body["data"]


def one(result: list[dict]) -> float | None:
    """The single number an aggregate query returns, or None when nothing matched.

    Nothing matched is a real answer and not an error: a counter that never fired publishes no
    series at all, which is what every clean run looks like for the oversell and the duplicate
    counters. Printing it as 0 would claim a measurement that was never taken, so it stays None
    and prints as "no series".
    """
    if not result:
        return None
    return float(result[0]["value"][1])


def labelled(result: list[dict], label: str) -> dict[str, float]:
    """A query grouped by one label, as a mapping, so the caller prints it in its own order."""
    return {
        sample["metric"].get(label, "(none)"): float(sample["value"][1]) for sample in result
    }


def first_at(result: list[dict], predicate) -> tuple[int, float] | None:
    """The first sample of a range query that satisfies predicate, with its timestamp."""
    for sample in result:
        for timestamp, raw in sample["values"]:
            value = float(raw)
            if predicate(value):
                return int(float(timestamp)), value
    return None


def quantile(metric: str, selector: str, window: int, q: float) -> str:
    """A percentile over the buckets of one histogram, in seconds.

    Summed by `le` across services and replicas rather than read per series, because a bucket
    counter is additive and the question is about the operation and not about which pod served it.
    `increase` over the whole window rather than a rate, so a run is measured by what happened in
    it and the figure does not depend on when inside the window the load was.
    """
    inner = f"sum by (le) (increase({metric}_bucket{selector}[{window}s]))"
    return f"histogram_quantile({q}, {inner})"


def milliseconds(value: float | None) -> str:
    if value is None:
        return "no series"
    if value != value:  # NaN, which is what histogram_quantile answers for an empty histogram
        return "no observations"
    return f"{value * 1000:.0f} ms"


def count(value: float | None) -> str:
    if value is None:
        return "no series"
    return f"{value:.0f}"


def moment(epoch: int | None) -> str:
    if epoch is None:
        return "never"
    return datetime.fromtimestamp(epoch, timezone.utc).isoformat(timespec="seconds")


def report(prometheus: Prometheus, event: str, start: int, end: int, out) -> None:
    window = max(1, end - start)
    # Every selector carries the event where the metric has one, because a laptop that has been
    # demoed on has older sales in it and a published figure has to be about the run that was made.
    event_selector = '{event="%s"}' % event
    say = lambda line="": print(line, file=out)  # noqa: E731
    pad = 34

    def row(name: str, value: str, note: str = "") -> None:
        say(f"  {name:<{pad}}{value}{('   (' + note + ')') if note else ''}")

    say("the readings of README section 18.3, from Prometheus")
    say(f"  event                             {event}")
    say(f"  window                            {moment(start)} to {moment(end)}  ({window}s)")
    say(f"  prometheus                        {prometheus.url}")
    say()

    say("the venue")
    capacity = one(prometheus.instant(f"max(seatrush_seats_capacity{event_selector})", end))
    remaining = one(prometheus.instant(f"min(seatrush_seats_available{event_selector})", end))
    sold = one(prometheus.instant(f"sum(increase(seatrush_seats_sold_total[{window}s]))", end))
    row("capacity", count(capacity))
    row("seats left at the end", count(remaining), f"sampled, +/-{SCRAPE_INTERVAL_SECONDS}s")
    row("seats sold during the window", count(sold))
    row(
        "oversell prevented by the guard",
        count(one(prometheus.instant(f"sum(increase(seatrush_oversell_prevented_total[{window}s]))", end))),
        "must be 0",
    )
    say()

    say("when it sold out")
    # The counter the sale is bounded by, not a count of the seat map: it is the same read the sold
    # out check makes, and the one the two gauges of section 14.4 are drawn from.
    left = prometheus.series(
        f"min(seatrush_seats_available{event_selector})", start, end, GAUGE_STEP_SECONDS
    )
    opened = first_at(left, lambda value: capacity is not None and value < capacity)
    emptied = first_at(left, lambda value: value <= 0)
    row("first reading below capacity", moment(opened[0] if opened else None))
    row("first reading at zero", moment(emptied[0] if emptied else None))
    if opened and emptied:
        row(
            "time to sell out",
            f"{emptied[0] - opened[0]}s",
            f"between two sampled readings, +/-{SCRAPE_INTERVAL_SECONDS}s",
        )
    else:
        row("time to sell out", "the sale did not sell out inside the window")
    say()

    say("the latencies of README section 15")
    # The two per endpoint figures are read on the service that serves the endpoint and never on
    # the gateway, which tags a proxied route uri=UNKNOWN (section 14.1).
    join = '{service="queue-service",uri="/api/events/{eventId}/queue"}'
    for name, query in (
        ("queue join p50", quantile("http_server_requests_seconds", join, window, 0.5)),
        ("queue join p99", quantile("http_server_requests_seconds", join, window, 0.99)),
        ("hold p50", quantile("seatrush_hold_duration_seconds", "", window, 0.5)),
        ("hold p99", quantile("seatrush_hold_duration_seconds", "", window, 0.99)),
        ("checkout p50", quantile("seatrush_saga_duration_seconds", "", window, 0.5)),
        ("checkout p99", quantile("seatrush_saga_duration_seconds", "", window, 0.99)),
        ("seat map propagation p50", quantile("seatrush_realtime_propagation_seconds", "", window, 0.5)),
        ("seat map propagation p99", quantile("seatrush_realtime_propagation_seconds", "", window, 0.99)),
    ):
        row(name, milliseconds(one(prometheus.instant(query, end))), "bucket edge")
    say()

    say("the holds")
    for result, amount in labelled(
        prometheus.instant(f"sum by (result) (increase(seatrush_holds_total[{window}s]))", end),
        "result",
    ).items():
        row(f"holds {result}", count(amount))
    row(
        "holds released by the sweeper",
        count(one(prometheus.instant(f"sum(increase(seatrush_holds_expired_total[{window}s]))", end))),
    )
    row(
        "admissions",
        count(one(prometheus.instant(f"sum(increase(seatrush_admissions_total[{window}s]))", end))),
    )
    say()

    say("the checkouts")
    for result, amount in labelled(
        prometheus.instant(
            f"sum by (result) (increase(seatrush_checkout_started_total[{window}s]))", end
        ),
        "result",
    ).items():
        row(f"started {result}", count(amount))
    compensations = labelled(
        prometheus.instant(
            f"sum by (reason) (increase(seatrush_saga_compensations_total[{window}s]))", end
        ),
        "reason",
    )
    if compensations:
        for reason, amount in compensations.items():
            row(f"compensated {reason}", count(amount))
    else:
        row("compensations", "no series")
    row(
        "stuck checkouts at the end",
        count(one(prometheus.instant("max(seatrush_saga_stuck)", end))),
    )
    say()

    say("the load it was under")
    # Subqueries, because the question is the peak of a gauge over the run and not its last value.
    # seatrush_sse_connections is the one gauge of section 14.1 that is summed rather than maxed:
    # it counts what a single pod holds.
    for name, query in (
        (
            "peak queued",
            f"max_over_time((max(seatrush_queue_length{{event=\"{event}\",state=\"queued\"}}))[{window}s:{GAUGE_STEP_SECONDS}s])",
        ),
        (
            "peak in the purchase area",
            f"max_over_time((max(seatrush_queue_active_users{event_selector}))[{window}s:{GAUGE_STEP_SECONDS}s])",
        ),
        (
            "peak seat map streams",
            f'max_over_time((sum(seatrush_sse_connections{{stream="seatmap"}}))[{window}s:{GAUGE_STEP_SECONDS}s])',
        ),
        (
            "peak waiting room streams",
            f'max_over_time((sum(seatrush_sse_connections{{stream="queue"}}))[{window}s:{GAUGE_STEP_SECONDS}s])',
        ),
    ):
        row(name, count(one(prometheus.instant(query, end))))
    say()

    say("what nothing is allowed to have")
    for name, query in (
        ("5xx answers", f'sum(increase(http_server_requests_seconds_count{{status=~"5.."}}[{window}s]))'),
        ("duplicate messages skipped", f"sum(increase(seatrush_messages_duplicates_total[{window}s]))"),
        ("seats fixed by the reconciler", f"sum(increase(seatrush_redis_reconciliations_total[{window}s]))"),
        ("late replies", f"sum(increase(seatrush_saga_late_replies_total[{window}s]))"),
        ("outbox lag at the end", "max(seatrush_outbox_lag_seconds)"),
    ):
        row(name, count(one(prometheus.instant(query, end))))


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(
        description="the readings behind the results table of README section 18.3",
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    parser.add_argument("--event", required=True, help="the sale the run was made against")
    parser.add_argument("--from", dest="start", required=True, type=int, help="epoch seconds")
    parser.add_argument("--to", dest="end", required=True, type=int, help="epoch seconds")
    parser.add_argument("--url", default=DEFAULT_URL, help=f"default {DEFAULT_URL}")
    arguments = parser.parse_args(argv)
    if arguments.end <= arguments.start:
        parser.error("--to is before --from, so there is no window to measure")
    try:
        report(Prometheus(arguments.url), arguments.event, arguments.start, arguments.end, sys.stdout)
    except PrometheusError as error:
        print(f"the readings could not be taken: {error}", file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
