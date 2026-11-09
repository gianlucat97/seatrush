#!/usr/bin/env sh
# One published benchmark run of README sections 18.2 and 18.3.
#
# Section 18.1 ends with a rule: the run is published only if the invariant checker exits zero. This
# is that rule as a command. It records the environment before the run so the figures can be read
# back against the machine that produced them, runs the two halves of scripts/load.sh, takes the
# readings of section 18.3 out of Prometheus, and moves the lot into docs/benchmarks/<date>/ only
# when both verdicts are green. A run that failed either one stays in target/benchmark-run, where it
# can be read and argued with, and this script exits with the code that failed.
#
#   ./scripts/publish-run.sh                     the small profile, against make up
#   ./scripts/publish-run.sh full                the benchmark of section 18.1, against a cluster
#   ./scripts/publish-run.sh small -Dseatrush.load.spectators=0
#
# Anything after the profile is handed to scripts/load.sh, which hands it to Maven, so one knob of
# section 18.1 is changed without a third preset.
#
# SEATRUSH_API_URL moves the target, PROMETHEUS_URL moves where the readings come from, and
# SEATRUSH_RUN_DIR names the directory to publish into instead of today's date. The last one is for
# a second run on one day and for a run being redone: without it, a directory that already exists is
# refused rather than overwritten, because a published run is evidence and not a build output.
#
# What it does not do is write the results table. The table of section 18.3 and the environment of
# section 18.2 are read out of these artifacts by whoever publishes the run: a figure in a README is
# a claim somebody is making, and the point of this directory is that the claim can be checked.
set -eu

cd "$(dirname "$0")/.."

PROFILE="${1:-small}"
if [ $# -gt 0 ]; then
  shift
fi

# python3 where the interpreter answers to that name, python where it does not, which is what
# .github/workflows/ci.yml calls the three validators by.
#
# Each candidate is run rather than looked up, because on Windows there is something on the PATH
# called python3 that is not an interpreter: the Microsoft Store app alias, which answers to the
# name, exits non zero and prints an invitation to install Python. `command -v` finds it and is
# satisfied, and the first run of this script got as far as the readings before saying so.
PYTHON="${PYTHON:-}"
if [ -z "$PYTHON" ]; then
  for candidate in python3 python; do
    if "$candidate" -c "" >/dev/null 2>&1; then
      PYTHON="$candidate"
      break
    fi
  done
fi
if [ -z "$PYTHON" ]; then
  echo "no working python3 on the PATH, and three rows of section 18.3 are read with it." >&2
  echo "Name one with PYTHON=/path/to/python3." >&2
  exit 2
fi

API="${SEATRUSH_API_URL:-http://localhost:8080}"
PROMETHEUS="${PROMETHEUS_URL:-http://localhost:9090}"
GRAFANA="${GRAFANA_URL:-http://localhost:3000}"

# Staged under target/ and not under docs/, so a failed run cannot leave half a publication in the
# tree. Nothing here is committed until the last step moves it.
STAGE=target/benchmark-run
GATLING=load-tests/gatling/target/gatling
EVENT_ID_FILE="$GATLING/event-id.txt"

DESTINATION="${SEATRUSH_RUN_DIR:-docs/benchmarks/$(date -u +%Y-%m-%d)}"
if [ -e "$DESTINATION" ]; then
  echo "$DESTINATION already holds a published run." >&2
  echo "Name another with SEATRUSH_RUN_DIR, or remove that one on purpose." >&2
  exit 2
fi

if ! curl --silent --max-time 5 --output /dev/null "$API/api/events"; then
  echo "the gateway does not answer at $API. Start the stack with make up." >&2
  exit 2
fi
if ! curl --silent --max-time 5 --output /dev/null "$PROMETHEUS/-/healthy"; then
  echo "Prometheus does not answer at $PROMETHEUS, so three rows of section 18.3 would be" >&2
  echo "unmeasurable. Point PROMETHEUS_URL at it, or port forward it out of a cluster." >&2
  exit 2
fi

rm -rf "$STAGE"
mkdir -p "$STAGE"

# ---------------------------------------------------------------------------------------
# The environment of README section 18.2, written before the run rather than after it.
#
# Afterwards is too late for the one fact that matters most: a tree with uncommitted changes in it
# means the commit hash below does not describe what ran, and that is worth saying on the artifact
# instead of leaving the reader to assume it does.
environment() {
  echo "the environment of README section 18.2"
  echo
  echo "the commit"
  echo "  revision                          $(git rev-parse HEAD)"
  echo "  described                         $(git describe --always --dirty --tags 2>/dev/null || echo 'no tag')"
  if [ -n "$(git status --porcelain)" ]; then
    echo "  tree                              DIRTY: the revision above is not what ran"
  else
    echo "  tree                              clean"
  fi
  echo
  echo "the injector"
  echo "  host                              $(uname -s -r -m)"
  echo "  cores visible to it               $(getconf _NPROCESSORS_ONLN 2>/dev/null || echo unknown)"
  echo "  jvm                               $(java -version 2>&1 | head -n 1)"
  echo "  maven                             $(./mvnw --version 2>/dev/null | head -n 1 || echo unknown)"
  echo
  echo "the stack"
  echo "  target                            $API"
  echo "  container engine                  $(docker version --format '{{.Server.Version}}' 2>/dev/null || echo unknown)"
  echo "  cores it was given                $(docker info --format '{{.NCPU}}' 2>/dev/null || echo unknown)"
  echo "  memory it was given               $(docker info --format '{{.MemTotal}}' 2>/dev/null || echo unknown) bytes"
  echo
  # The one part of the environment that is not in the repository. .env is git ignored and it holds
  # the door of the sale: a run made with a purchase area of 5,000 and one made with 40 are two
  # different scenarios, and without these lines nobody could tell which one produced the figures.
  # The secrets are named and not printed, because what matters is that they were set.
  echo "the knobs of section 22 this stack was started with"
  if [ -f .env ]; then
    awk -F= '/^SEATRUSH_[A-Z0-9_]+=/ {
      value = substr($0, index($0, "=") + 1)
      if ($1 ~ /SECRET|KEY/) { value = "<set, not published>" }
      printf "  %-45s %s\n", $1, value
    }' .env
    echo "  everything else is the application default of section 22"
  else
    echo "  no .env here, so this stack was configured elsewhere: record it by hand"
  fi
  echo
  echo "one replica of each unless this table says otherwise"
  docker compose --file deploy/docker-compose/docker-compose.yml --env-file .env images 2>/dev/null \
    || echo "  not a Compose stack: record the replicas and the node sizes by hand"
}
environment > "$STAGE/environment.txt"

# ---------------------------------------------------------------------------------------
# The run.
#
# The window is taken around it and not from the Gatling report, because the readings afterwards are
# of the services and not of the injector: the sweeper that expires a hold and the relay that drains
# the outbox are both still working after the last response arrived.
STARTED=$(date -u +%s)
echo "== the load test and the invariants (README section 18.1), profile $PROFILE"
# Through tee, so a run that takes minutes says what it is doing, and with the status written to a
# file from inside the pipeline: what $? carries after a pipeline is tee's, and the one that decides
# whether this gets published is the script's. PIPESTATUS would be shorter and is not POSIX.
#
# The `|| status=$?` is what keeps `set -e` out of it. A load test that failed is the normal way for
# this to go and the whole reason the checker runs afterwards, so the shell exiting at that line
# would skip both the verdict and the artifacts, which is what it did the first time this ran.
{
  status=0
  ./scripts/load.sh "$PROFILE" "$@" 2>&1 || status=$?
  echo "$status" > "$STAGE/load-status"
} | tee "$STAGE/run.txt"
LOAD_STATUS=$(cat "$STAGE/load-status")
rm -f "$STAGE/load-status"
ENDED=$(date -u +%s)

if [ ! -f "$EVENT_ID_FILE" ]; then
  echo "the run left no event id in $EVENT_ID_FILE, so there is nothing to publish." >&2
  exit 2
fi
EVENT=$(tr -d ' \t\r\n' < "$EVENT_ID_FILE")
echo "$EVENT" > "$STAGE/event-id.txt"

# The report as Gatling wrote it, viewer and all, because what is published has to be readable by
# somebody who has neither this repository nor a Gatling of the same version. simulation.log is left
# behind on purpose: it is the binary input the report was generated from, and a published artifact
# nothing in the tree can open is not evidence.
REPORT=$(ls -dt "$GATLING"/onsalesimulation-* 2>/dev/null | head -n 1 || true)
if [ -n "$REPORT" ]; then
  mkdir -p "$STAGE/gatling"
  cp -R "$REPORT"/. "$STAGE/gatling"
  rm -f "$STAGE/gatling/simulation.log"
  # The block the console ends with, which is the table anybody reads first and the only place the
  # assertions of the simulation say which of them held. It stops at the marker scripts/load.sh
  # prints before its second half, so the checker's report does not land in the Gatling summary too.
  awk '/Global Information/ { inside = 1 } /== the invariants/ { inside = 0 } inside' \
    "$STAGE/run.txt" > "$STAGE/gatling/summary.txt"
fi

# ---------------------------------------------------------------------------------------
# The two verdicts, each one in a file of its own.
#
# The checker is run again rather than cut out of the output above, and it is allowed to be: it
# never writes, calls no service and has no state, so a run can be checked twice. What that buys is
# a file that is exactly the checker's own report, with no load test wrapped around it.
CHECK_STATUS=0
./scripts/check.sh --event "$EVENT" > "$STAGE/invariant-checker.txt" 2>&1 || CHECK_STATUS=$?

METRICS_STATUS=0
$PYTHON scripts/benchmark-metrics.py \
  --event "$EVENT" --from "$STARTED" --to "$ENDED" --url "$PROMETHEUS" \
  > "$STAGE/metrics.txt" 2>&1 || METRICS_STATUS=$?

# Where the three pages of section 14.4 are, at the time range of this run and nothing else, so the
# screenshots that go next to these files are of the run and not of whenever somebody opened them.
{
  echo "the three dashboards of README section 14.4, at the window of this run"
  echo
  for uid in on-sale checkout platform; do
    echo "  $GRAFANA/d/seatrush-$uid?from=${STARTED}000&to=${ENDED}000"
  done
  echo
  echo "Saved next to this file as grafana/<page>.png. A screenshot is the only artifact here that"
  echo "nothing in the repository can regenerate, because the retention of the local Prometheus is"
  echo "seven days and the one in a cluster goes with the cluster."
} > "$STAGE/grafana.txt"

# ---------------------------------------------------------------------------------------
# Publish, or do not.
#
# The checker alone decides this, which is the rule section 18.1 states and not a shortcut around
# the load test. The two verdicts are about different things. A red load test is a fact about the
# run and belongs in the publication: a refused request is the ordinary answer of a busy on-sale,
# and an injector that ran out of sockets beside the stack it was measuring has produced a figure
# about the environment of section 18.2, which is exactly what a published run is supposed to say
# out loud. A broken invariant is a fact about the system, and there is nothing to publish about a
# sale whose books do not balance.
echo
if [ "$CHECK_STATUS" -ne 0 ]; then
  echo "not published: the invariants exited $CHECK_STATUS, and section 18.1 publishes a run only" >&2
  echo "when they hold. The artifacts are in $STAGE, which is not a publication." >&2
  exit "$CHECK_STATUS"
fi
if [ "$METRICS_STATUS" -ne 0 ]; then
  echo "the invariants held and the readings of section 18.3 were not taken:" >&2
  sed 's/^/  /' "$STAGE/metrics.txt" >&2
  echo "The artifacts are in $STAGE. Three rows of that table are read nowhere else, so they are" >&2
  echo "worth taking again rather than publishing a run with holes in it." >&2
  exit "$METRICS_STATUS"
fi

mkdir -p "$(dirname "$DESTINATION")"
mv "$STAGE" "$DESTINATION"
mkdir -p "$DESTINATION/grafana"
echo "published to $DESTINATION"
if [ "$LOAD_STATUS" -ne 0 ]; then
  echo
  echo "the load test exited $LOAD_STATUS, so this run is published with a red verdict on it."
  echo "What failed is in $DESTINATION/gatling/summary.txt, and it belongs in the write-up."
fi
echo
echo "what is left to do by hand, because neither of them is a reading:"
echo "  the three screenshots named in $DESTINATION/grafana.txt"
echo "  the tables of README sections 18.2 and 18.3, from environment.txt and metrics.txt"
