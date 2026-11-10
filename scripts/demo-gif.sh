#!/usr/bin/env sh
# The demo of the README, recorded rather than described.
#
# One sale, filled by the load test of README section 18.1 and watched from the spectator view of
# section 6.7 while it happens. That pairing is the whole point: the client is not being clicked
# through by a script pretending to be a person, it is doing the job section 6.7 says spectator mode
# exists for, which is to watch a load test fill a venue in real time. What comes out is
# docs/demo.gif.
#
#   ./scripts/demo-gif.sh                      the venue below, into docs/demo.gif
#   ./scripts/demo-gif.sh docs/demo.gif        the same, said out loud
#   DEMO_FLAGS="-Dseatrush.load.rows=8" ./scripts/demo-gif.sh
#
# The venue is small on purpose and it is sized by the slowest thing in the system rather than by
# what looks impressive. A laptop running this stack confirms somewhere around one checkout a
# second (section 18.3), so a venue of a hundred and fifty seats is one that visibly fills inside a
# minute, and a venue of twenty thousand is a recording of almost nothing changing.
set -eu

cd "$(dirname "$0")/.."

GIF="${1:-docs/demo.gif}"
API="${SEATRUSH_API_URL:-http://localhost:8080}"
UI="${SEATRUSH_UI_URL:-http://localhost:5173}"

FRAMES="${DEMO_FRAMES:-30}"
INTERVAL="${DEMO_INTERVAL:-3000}"
WORK=target/demo
EVENT_ID_FILE=load-tests/gatling/target/gatling/event-id.txt

# Five blocks of five rows, which fills a 1280 by 800 window and is most of the way sold by the
# end of the recording. Overridden whole by DEMO_FLAGS.
DEFAULT_FLAGS="-Dseatrush.load.sections=5 -Dseatrush.load.rows=5 -Dseatrush.load.seatsPerRow=6 \
-Dseatrush.load.lobbyUsers=120 -Dseatrush.load.arrivingUsers=280 \
-Dseatrush.load.spectators=4 -Dseatrush.load.watchers=8"
FLAGS="${DEMO_FLAGS:-$DEFAULT_FLAGS}"

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
  echo "no working python3 on the PATH, and the GIF is written with Pillow." >&2
  exit 2
fi

if ! curl --silent --max-time 5 --output /dev/null "$API/api/events"; then
  echo "the gateway does not answer at $API. Start the stack with make up." >&2
  exit 2
fi
if ! curl --silent --max-time 5 --output /dev/null "$UI/"; then
  echo "the client does not answer at $UI. It is the ui container of make up." >&2
  exit 2
fi

rm -rf "$WORK"
mkdir -p "$WORK"
# Stale from a previous run, so the recorder cannot end up watching last week's sale.
rm -f "$EVENT_ID_FILE"

echo "== the sale, filled by the load test of README section 18.1"
# In the background and with its output kept, because the recording has to start while this is
# still going. A failed load test is not fatal here: what is being recorded is the client, and a
# run that was refused somewhere still filled the venue in front of it. The log says what happened.
# shellcheck disable=SC2086
( ./scripts/load.sh small $FLAGS > "$WORK/load.log" 2>&1; echo "$?" > "$WORK/load-status" ) &
LOAD_PID=$!

# The simulation creates its venue before it injects anybody, and writes the id here. That file
# appearing is the signal that there is a seat map to watch.
echo "waiting for the sale to be created"
WAITED=0
while [ ! -s "$EVENT_ID_FILE" ]; do
  if ! kill -0 "$LOAD_PID" 2>/dev/null; then
    echo "the load test ended before it created a sale:" >&2
    tail -n 20 "$WORK/load.log" >&2
    exit 2
  fi
  if [ "$WAITED" -ge 300 ]; then
    echo "no sale after five minutes, which is longer than the build can possibly take." >&2
    exit 2
  fi
  sleep 2
  WAITED=$((WAITED + 2))
done
EVENT=$(tr -d ' \t\r\n' < "$EVENT_ID_FILE")

echo
echo "== the spectator view of README section 6.7, while it fills"
node scripts/record-demo.mjs \
  --url "$UI" --event "$EVENT" --out "$WORK/frames" \
  --frames "$FRAMES" --interval "$INTERVAL"

echo
echo "== the GIF"
$PYTHON scripts/frames-to-gif.py "$WORK/frames" "$GIF"

# The run is left to finish rather than killed: the buyers still in it are the ones whose holds
# would otherwise sit there until they expired, and the checker at the end of scripts/load.sh is
# the only thing that says whether the sale this recorded was a consistent one.
echo
echo "== waiting for the run to finish, because a recording is not a verdict"
wait "$LOAD_PID" || true
STATUS=$(cat "$WORK/load-status" 2>/dev/null || echo "unknown")
echo "the load test and the invariants exited $STATUS. The log is $WORK/load.log"
