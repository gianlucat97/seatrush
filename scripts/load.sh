#!/usr/bin/env sh
# The load test of README section 18, followed by the invariant checker of section 17.1.
#
# Two halves, and neither one is a verdict on its own. Gatling says how fast the system answered
# and how often it refused; it cannot see a seat sold twice, a confirmed order with no ticket or a
# hold that outlived its TTL, because none of those is a response. The checker reads the stores
# afterwards and says whether what was sold is consistent. This script is green only if both are,
# which is the whole reason they are one command.
#
#   ./scripts/load.sh                     the small profile, against the local stack
#   ./scripts/load.sh full                the benchmark of section 18.1
#   ./scripts/load.sh small -Dseatrush.load.spectators=0
#
# Anything after the profile is handed to Maven, which is how one knob of section 18.1 is changed
# without a third preset. SEATRUSH_API_URL moves the target, the way it does for scripts/seed.sh.
set -eu

cd "$(dirname "$0")/.."

MVNW="${MVNW:-./mvnw}"
MODULE=load-tests/gatling
PROFILE="${1:-small}"
if [ $# -gt 0 ]; then
  shift
fi

API="${SEATRUSH_API_URL:-http://localhost:8080}"
# Relative to this directory, and handed to the run rather than left to a default. The JVM the
# Gatling plugin forks inherits Maven's working directory, which is this one and not the module's,
# so the two agree only because both paths are written from here. An absolute one would be worse:
# the shell this runs in and the JVM it starts spell a Windows path differently.
EVENT_ID_FILE="$MODULE/target/gatling/event-id.txt"

# The same check scripts/seed.sh makes, for the same reason: a run that cannot create its venue
# fails a minute in with a stack trace, and the cause is almost always a stack that is not up.
if ! curl --silent --max-time 5 --output /dev/null "$API/api/events"; then
  echo "the gateway does not answer at $API. Start the stack with make up." >&2
  exit 1
fi

# Stale from a previous run, so a Gatling failure cannot leave the checker reading the wrong sale.
rm -f "$EVENT_ID_FILE"

echo "== the load test (README section 18.1), profile $PROFILE"
# A failed run is not allowed to end this script, which is the whole point of running both halves:
# a run whose latencies missed their target, or whose requests were refused in a way nobody designed
# for, is exactly the run worth asking whether the state it left behind is consistent. The two
# verdicts are independent and both are reported.
LOAD_STATUS=0
$MVNW -B -ntp --file "$MODULE/pom.xml" gatling:test \
  -Dgatling.simulationClass=com.seatrush.load.OnSaleSimulation \
  -Dseatrush.load.profile="$PROFILE" \
  -Dseatrush.load.baseUrl="$API" \
  -Dseatrush.load.eventIdFile="$EVENT_ID_FILE" \
  "$@" || LOAD_STATUS=$?

if [ ! -f "$EVENT_ID_FILE" ]; then
  echo "the run left no event id in $EVENT_ID_FILE, so there is nothing to check." >&2
  exit 1
fi
EVENT=$(tr -d ' \t\r\n' < "$EVENT_ID_FILE")

echo
echo "== the invariants of the sale that was just run (README section 17.1)"
# The one event the run created, and not the whole catalog: a laptop that has been demoed on for a
# week has older sales in it whose state says nothing about this run.
CHECK_STATUS=0
./scripts/check.sh --event "$EVENT" || CHECK_STATUS=$?

echo
if [ "$LOAD_STATUS" -ne 0 ]; then
  echo "the load test failed (exit $LOAD_STATUS) and the invariants exited $CHECK_STATUS." >&2
  exit "$LOAD_STATUS"
fi
if [ "$CHECK_STATUS" -ne 0 ]; then
  echo "the load test passed and the invariants did not (exit $CHECK_STATUS)." >&2
  exit "$CHECK_STATUS"
fi
echo "the run was clean and the invariants hold."
