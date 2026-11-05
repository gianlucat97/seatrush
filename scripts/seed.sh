#!/usr/bin/env sh
# The demo event of README section 23, created through the public API and nothing else.
#
# Through the API because that is the only way an event is ever created in this system: there
# is no fixture loader and no SQL to insert one. A seed that wrote rows directly would be a
# second way to make an event, and the first thing it would skip is the Redis warm-up that
# the on-sale depends on.
#
# Two thousand seats and a sale two minutes out, which is what makes a demo of a flash
# on-sale possible: long enough to open three browsers and join the lobby, short enough that
# nobody is waiting. The lobby is drawn in a random order the moment it opens, so arriving
# early in those two minutes buys nothing, which is the thing worth showing.
set -eu

API="${SEATRUSH_API_URL:-http://localhost:8080}"
NAME="${SEATRUSH_SEED_EVENT_NAME:-The Midnight Set}"
MINUTES="${SEATRUSH_SEED_MINUTES:-2}"

# One field out of a known response, with sed rather than a JSON parser: this script has the
# prerequisites of README section 23 and a JSON parser is not among them.
field() {
  sed -n "s/.*\"$1\":\"\([^\"]*\)\".*/\1/p"
}

post() {
  curl --silent --show-error --fail-with-body \
    --header 'Content-Type: application/json' \
    --header "Authorization: Bearer $2" \
    --request POST --data "${3:-}" "$API$1"
}

if ! curl --silent --fail --max-time 3 --output /dev/null "$API/actuator/health"; then
  echo "the gateway does not answer at $API. Start the stack with make up." >&2
  exit 1
fi

# The demo login of README section 12.1, which exists in the local and bench profiles only.
TOKEN=$(curl --silent --show-error --fail-with-body \
  --header 'Content-Type: application/json' \
  --request POST --data '{"username":"operator","admin":true}' \
  "$API/api/auth/demo-login" | field accessToken)

if [ -z "$TOKEN" ]; then
  echo "the demo login did not return a token: is SPRING_PROFILES_ACTIVE local or bench?" >&2
  exit 1
fi

# Date arithmetic the two common date commands disagree about, so both spellings are tried.
ON_SALE=$(date -u -d "+${MINUTES} minutes" '+%Y-%m-%dT%H:%M:%SZ' 2>/dev/null \
  || date -u -v "+${MINUTES}M" '+%Y-%m-%dT%H:%M:%SZ')

# Four sections of 500, which is a venue with a cheap end and an expensive one: a seat map
# worth looking at, and a price a selection can add up (README section 6.3).
EVENT=$(post /api/admin/events "$TOKEN" "{
  \"name\": \"$NAME\",
  \"onSaleAt\": \"$ON_SALE\",
  \"sections\": [
    {\"id\": \"A\", \"name\": \"Front Stalls\", \"priceCents\": 12000, \"currency\": \"EUR\", \"rows\": 20, \"seatsPerRow\": 25},
    {\"id\": \"B\", \"name\": \"Rear Stalls\",  \"priceCents\": 8500,  \"currency\": \"EUR\", \"rows\": 20, \"seatsPerRow\": 25},
    {\"id\": \"C\", \"name\": \"Balcony\",      \"priceCents\": 5500,  \"currency\": \"EUR\", \"rows\": 20, \"seatsPerRow\": 25},
    {\"id\": \"D\", \"name\": \"Gallery\",      \"priceCents\": 3000,  \"currency\": \"EUR\", \"rows\": 20, \"seatsPerRow\": 25}
  ]
}")

EVENT_ID=$(printf '%s' "$EVENT" | field id)
if [ -z "$EVENT_ID" ]; then
  echo "the event was not created: $EVENT" >&2
  exit 1
fi

# DRAFT to SCHEDULED, which is what opens the lobby: from here somebody can join and wait.
post "/api/admin/events/$EVENT_ID/schedule" "$TOKEN" >/dev/null

cat <<MESSAGE
seeded "$NAME"
  event      $EVENT_ID
  seats      2,000 in 4 sections, 30,00 to 120,00 EUR
  on sale    $ON_SALE (in $MINUTES minutes), lobby open now

  buyer      ${SEATRUSH_UI_URL:-http://localhost:5173}/#/events/$EVENT_ID
  spectator  ${SEATRUSH_UI_URL:-http://localhost:5173}/#/events/$EVENT_ID/spectator

Open it as a buyer, join the queue, and open the spectator link in another window to watch
the venue fill. To open the sale now instead of waiting:
  curl -X POST $API/api/admin/events/$EVENT_ID/open -H "Authorization: Bearer <admin token>"
MESSAGE
