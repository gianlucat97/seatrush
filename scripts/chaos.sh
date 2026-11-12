#!/usr/bin/env sh
# The chaos experiments of README section 16, run against whatever stack is up.
#
# Each experiment breaks one thing the failure modes table makes a promise about, waits for the
# system to do what that row says it will do, and reports whether it did. They are worth most while
# a sale is running, which is the point of section 17 saying "during load": a pod killed on an idle
# stack proves it restarts, and a pod killed mid-checkout proves the SAGA survived it. So the
# recipe is two terminals, one running `make load-small` and one running this.
#
#   ./scripts/chaos.sh                       every experiment, then the invariants
#   ./scripts/chaos.sh list                  what there is
#   ./scripts/chaos.sh kill-order stop-kafka  two of them, in that order
#
# Four outcomes, and three exit codes borrowed from scripts/check.sh so the two read alike. PASS and
# 0 is the system doing what section 16 says. FAIL and 1 is the system not doing it. GAP and 2 is a
# promise of section 16 whose recovery step is not in the code yet, which is a finding rather than a
# regression and worth telling apart in a gate. SKIP is the fourth and changes no exit code: the
# experiment ran and had nothing to measure, because nothing was in flight to lose. It is not a pass
# on purpose, since an experiment that quietly reports success over an idle stack is how a chaos
# suite becomes decoration.
set -eu

cd "$(dirname "$0")/.."

# Where a crashed service is given to come back, and how long a drain may take. Generous, because
# a JVM starting under load on a laptop is not the thing being measured.
RECOVERY_TIMEOUT="${CHAOS_RECOVERY_TIMEOUT:-120}"
DRAIN_TIMEOUT="${CHAOS_DRAIN_TIMEOUT:-120}"
# How long the broker stays away. Long enough that the relays notice, short enough that nobody
# watching the terminal wonders whether it hung.
KAFKA_OUTAGE="${CHAOS_KAFKA_OUTAGE:-20}"

COMPOSE_FILE=deploy/docker-compose/docker-compose.yml
COMPOSE="docker compose --file $COMPOSE_FILE --env-file .env"
K8S_NAMESPACE="${K8S_NAMESPACE:-seatrush}"
KUBECTL="kubectl --namespace $K8S_NAMESPACE"

PASSED=0
FAILED=0
GAPS=0
SKIPPED=0
FAILED_NAMES=""
GAP_NAMES=""
SKIPPED_NAMES=""

# ---------------------------------------------------------------------------------------
# Which stack to break.
#
# Detected rather than configured, because the answer is observable and getting it wrong is the one
# mistake that would make every experiment report a failure of something that is not running.
# CHAOS_BACKEND overrides it for a host where both are up.
detect_backend() {
  if [ -n "${CHAOS_BACKEND:-}" ]; then
    echo "$CHAOS_BACKEND"
    return
  fi
  if docker compose --file "$COMPOSE_FILE" --env-file .env ps --status running --quiet 2>/dev/null \
    | grep -q .; then
    echo compose
    return
  fi
  if kubectl --namespace "$K8S_NAMESPACE" get pods --no-headers 2>/dev/null | grep -q .; then
    echo k8s
    return
  fi
  echo none
}

# ---------------------------------------------------------------------------------------
# The five things an experiment needs, over either stack.

# SIGKILL and never a signal the process can handle. The graceful path of README section 24.6 is a
# different behaviour with its own tests; what this is for is the process that stopped existing
# between two statements.
#
# The two stacks differ here in a way worth being plain about. In the cluster the Deployment is what
# brings the pod back, so deleting one is the whole experiment and the recovery is the system's. The
# Compose stack has no controller: `restart: unless-stopped` does not fire for a container killed
# through the API, which Docker counts as an explicit stop, so the start below is this script
# standing in for the orchestrator that is not there. What is tested either way is what section 16
# actually claims, which is that nothing is lost by the crash.
crash() {
  case "$BACKEND" in
    compose)
      $COMPOSE kill --signal KILL "$1" >/dev/null 2>&1 || true
      $COMPOSE start "$1" >/dev/null 2>&1 || true
      ;;
    k8s)
      $KUBECTL delete pod --selector "app.kubernetes.io/name=$1" \
        --force --grace-period=0 >/dev/null 2>&1
      ;;
  esac
}

stop_service() {
  case "$BACKEND" in
    compose) $COMPOSE stop --timeout 5 "$1" >/dev/null 2>&1 ;;
    k8s) scale "$1" 0 ;;
  esac
}

start_service() {
  case "$BACKEND" in
    compose) $COMPOSE start "$1" >/dev/null 2>&1 ;;
    k8s) scale "$1" 1 ;;
  esac
}

# The workload of a component without naming its kind: the broker is a StatefulSet and the rest are
# Deployments, and an experiment should not have to know that.
scale() {
  target=$($KUBECTL get deployment,statefulset --selector "app.kubernetes.io/name=$1" \
    --output name 2>/dev/null | head -n 1)
  if [ -n "$target" ]; then
    $KUBECTL scale "$target" --replicas="$2" >/dev/null 2>&1
  fi
}

# Serving again, by the stack's own definition of it rather than by a sleep: the Compose
# healthchecks are what `make up --wait` waits on, and readiness in the cluster is section 24.6's
# three contributors.
wait_until_serving() {
  waited=0
  while [ "$waited" -lt "$RECOVERY_TIMEOUT" ]; do
    case "$BACKEND" in
      compose)
        # docker inspect and not `compose ps --format`, because this asks a precise question and
        # gets a precise answer: the health the stack's own check reports, or the container state
        # for the one component that has no check. The container names are fixed in the Compose
        # file, which is what makes seatrush-<service> safe to assemble here.
        state=$(docker inspect \
          --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' \
          "seatrush-$1" 2>/dev/null || echo gone)
        case "$state" in
          healthy | running) return 0 ;;
        esac
        ;;
      k8s)
        if $KUBECTL get pods --selector "app.kubernetes.io/name=$1" \
          --output 'jsonpath={.items[*].status.conditions[?(@.type=="Ready")].status}' 2>/dev/null \
          | grep -q True; then
          return 0
        fi
        ;;
    esac
    sleep 2
    waited=$((waited + 2))
  done
  return 1
}

# One scalar out of a service's own database, which is how a claim about the outbox is checked
# without an endpoint for it.
psql_scalar() {
  database="$1"
  statement="$2"
  case "$BACKEND" in
    compose)
      $COMPOSE exec --no-TTY postgres \
        psql --username seatrush --dbname "$database" --tuples-only --no-align \
        --command "$statement" 2>/dev/null | tr -d ' \r\n'
      ;;
    k8s)
      pod=$($KUBECTL get pods --selector app.kubernetes.io/name=postgres \
        --output name 2>/dev/null | head -n 1)
      $KUBECTL exec "$pod" -- \
        psql --username seatrush --dbname "$database" --tuples-only --no-align \
        --command "$statement" 2>/dev/null | tr -d ' \r\n'
      ;;
  esac
}

redis_command() {
  case "$BACKEND" in
    compose) $COMPOSE exec --no-TTY redis redis-cli "$@" 2>/dev/null | tr -d ' \r\n' ;;
    k8s)
      pod=$($KUBECTL get pods --selector app.kubernetes.io/name=redis \
        --output name 2>/dev/null | head -n 1)
      $KUBECTL exec "$pod" -- redis-cli "$@" 2>/dev/null | tr -d ' \r\n'
      ;;
  esac
}

# ---------------------------------------------------------------------------------------
# Reporting.

say() { printf '   %s\n' "$1"; }

pass() {
  printf '   PASS  %s\n\n' "$1"
  PASSED=$((PASSED + 1))
}

fail() {
  printf '   FAIL  %s\n\n' "$1"
  FAILED=$((FAILED + 1))
  FAILED_NAMES="$FAILED_NAMES $EXPERIMENT"
}

gap() {
  printf '   GAP   %s\n\n' "$1"
  GAPS=$((GAPS + 1))
  GAP_NAMES="$GAP_NAMES $EXPERIMENT"
}

skip() {
  printf '   SKIP  %s\n\n' "$1"
  SKIPPED=$((SKIPPED + 1))
  SKIPPED_NAMES="$SKIPPED_NAMES $EXPERIMENT"
}

heading() { printf '\n-- %s\n   %s\n' "$1" "$2"; }

# ---------------------------------------------------------------------------------------
# The experiments.

# README section 16, the first three rows: a service that stopped existing mid-request. What the
# table promises is that nothing is lost by it, because the state is in Postgres and Redis and the
# Lua scripts are atomic, and that another process carries on. The observable part from out here is
# that it comes back and serves again; whether anything was lost is the invariant checker's answer
# at the end, and it is worth far more while a sale is running.
crash_experiment() {
  service="$1"
  heading "$EXPERIMENT" "section 16: $2"
  if [ "$BACKEND" = compose ]; then
    say "killing $service with SIGKILL, then starting it, because Compose has no controller"
  else
    say "deleting the $service pod with no grace period"
  fi
  before=$(date +%s)
  crash "$service"
  if wait_until_serving "$service"; then
    pass "$service was serving again after $(($(date +%s) - before))s"
  else
    fail "$service was not serving again within ${RECOVERY_TIMEOUT}s"
  fi
}

# README section 16: "Outbox rows and stream entries accumulate; relays publish them when Kafka is
# back. No data loss."
#
# The only experiment here with a number on both sides of it. Whatever the relays could not publish
# is sitting in the three outbox tables as rows with no published_at, so the claim is checkable: the
# backlog grows while the broker is away and returns to nothing when it is back. On an idle stack
# there is nothing to accumulate, which the experiment says rather than pretending otherwise.
stop_kafka_experiment() {
  heading "$EXPERIMENT" "section 16: Kafka temporarily unavailable"
  say "stopping the broker for ${KAFKA_OUTAGE}s"
  stop_service kafka
  sleep "$KAFKA_OUTAGE"
  backlog=$(outbox_backlog)
  say "unpublished outbox rows while it was away: $backlog"
  start_service kafka

  if ! wait_until_serving kafka; then
    fail "the broker did not come back within ${RECOVERY_TIMEOUT}s"
    return
  fi
  say "the broker is back"

  if [ "$backlog" = "0" ]; then
    skip "nothing was in flight while the broker was away, so there was no backlog to lose. This
         one is only a measurement while a sale is being bought from: start make load-small and run
         it inside the first minute"
    return
  fi

  waited=0
  while [ "$waited" -lt "$DRAIN_TIMEOUT" ]; do
    remaining=$(outbox_backlog)
    if [ "$remaining" = "0" ]; then
      pass "the backlog of $backlog rows drained in ${waited}s with nothing left behind"
      return
    fi
    sleep 2
    waited=$((waited + 2))
  done
  fail "$(outbox_backlog) of $backlog rows were still unpublished after ${DRAIN_TIMEOUT}s"
}

# Everything the three relays have not managed to publish, which is what a broker outage leaves.
outbox_backlog() {
  total=0
  for database in seatrush_inventory seatrush_order seatrush_payment; do
    rows=$(psql_scalar "$database" 'SELECT count(*) FROM outbox WHERE published_at IS NULL')
    total=$((total + ${rows:-0}))
  done
  echo "$total"
}

# README section 16: "Redis restart with data loss. Active holds lost (users re-hold); seat state
# rebuilt from seat_sale ..."
#
# The sharpest experiment of the set, because the hot state of every live sale is in there and none
# of it is persisted: the Compose stack runs Redis with no appendonly and no save by design, so a
# restart is a total loss. Half of what section 16 promises then happens by itself, and the half
# that does not is the rebuild.
restart_redis_experiment() {
  heading "$EXPERIMENT" "section 16: Redis restart with data loss"
  open_sales=$(psql_scalar seatrush_inventory \
    "SELECT count(*) FROM event WHERE status = 'ON_SALE'")
  keys=$(redis_command dbsize)
  say "$keys keys of hot state, $open_sales sale(s) on sale"

  crash redis
  if ! wait_until_serving redis; then
    fail "Redis did not come back within ${RECOVERY_TIMEOUT}s"
    return
  fi
  say "Redis is back with $(redis_command dbsize) keys, which is the data loss this is about"

  if [ "${open_sales:-0}" = "0" ]; then
    skip "no sale was open, so there was no seat state to lose and nothing to rebuild"
    return
  fi

  # The recovery section 16 names, asked for the way section 12.7 publishes it.
  say "asking inventory to rebuild the seat state of every open sale"
  if rebuild_open_sales; then
    pass "the seat state was rebuilt from seat_sale and the sales are buyable again"
  else
    gap "nothing rebuilt the seat state. POST /internal/events/{id}/rebuild of section 12.7 is not
         implemented, so an open sale that loses its hot state stays unbuyable: the invariant
         checker reports it as a sale nobody can buy anything in"
  fi
}

# Every sale that is still on sale, through the endpoint section 12.7 says exists. It returns
# non-zero when the endpoint is not there, which is the finding rather than a failure of this script.
rebuild_open_sales() {
  rebuilt=0
  for event in $(psql_scalar seatrush_inventory \
    "SELECT id FROM event WHERE status = 'ON_SALE'" | tr ',' ' '); do
    if inventory_post "/internal/events/$event/rebuild"; then
      rebuilt=$((rebuilt + 1))
    fi
  done
  [ "$rebuilt" -gt 0 ]
}

# The internal endpoints are deliberately not published by the gateway (README sections 13 and
# 12.7), so this reaches the service from inside the deployment, which is where an operator
# performing a recovery would be standing.
inventory_post() {
  case "$BACKEND" in
    compose)
      $COMPOSE exec --no-TTY inventory-service \
        curl --silent --fail --show-error --max-time 60 \
        --request POST "http://localhost:8081$1" >/dev/null 2>&1
      ;;
    k8s)
      pod=$($KUBECTL get pods --selector app.kubernetes.io/name=inventory-service \
        --output name 2>/dev/null | head -n 1)
      $KUBECTL exec "$pod" -- \
        curl --silent --fail --show-error --max-time 60 \
        --request POST "http://localhost:8081$1" >/dev/null 2>&1
      ;;
  esac
}

# ---------------------------------------------------------------------------------------

EXPERIMENTS="kill-inventory kill-order kill-queue kill-realtime stop-kafka restart-redis"

run_experiment() {
  EXPERIMENT="$1"
  case "$EXPERIMENT" in
    kill-inventory)
      crash_experiment inventory-service "inventory pod dies during a hold" ;;
    kill-order)
      crash_experiment order-service "order pod dies mid-SAGA" ;;
    kill-queue)
      crash_experiment queue-service "queue pod dies" ;;
    kill-realtime)
      crash_experiment realtime-service "realtime pod dies" ;;
    stop-kafka) stop_kafka_experiment ;;
    restart-redis) restart_redis_experiment ;;
    *)
      echo "no such experiment: $EXPERIMENT. ./scripts/chaos.sh list" >&2
      exit 1
      ;;
  esac
}

if [ "${1:-}" = "list" ]; then
  for name in $EXPERIMENTS; do echo "$name"; done
  exit 0
fi

BACKEND=$(detect_backend)
if [ "$BACKEND" = none ]; then
  echo "nothing is running. Start the stack with make up, or a cluster with make k8s-up." >&2
  exit 1
fi

echo "SeatRush chaos (README section 16), against the $BACKEND stack"
echo "Worth far more during a sale: run make load-small in another terminal."

if [ $# -gt 0 ]; then
  CHOSEN="$*"
else
  CHOSEN="$EXPERIMENTS"
fi
for name in $CHOSEN; do
  run_experiment "$name"
done

printf -- '-- the invariants afterwards (README section 17.1)\n'
CHECK_STATUS=0
./scripts/check.sh || CHECK_STATUS=$?

echo
echo "experiments: $PASSED passed, $SKIPPED skipped, $GAPS gap(s), $FAILED failed"
[ -n "$SKIPPED_NAMES" ] && echo "  skipped:$SKIPPED_NAMES"
[ -n "$GAP_NAMES" ] && echo "  gaps:   $GAP_NAMES"
[ -n "$FAILED_NAMES" ] && echo "  failed:$FAILED_NAMES"
echo "invariants: exit $CHECK_STATUS"

# A failure of the system outranks a gap in it, and the invariant checker outranks both: an
# experiment that passed while a seat was sold twice is not a run anybody should read as green.
if [ "$FAILED" -gt 0 ] || [ "$CHECK_STATUS" = "1" ]; then
  exit 1
fi
if [ "$GAPS" -gt 0 ] || [ "$CHECK_STATUS" != "0" ]; then
  exit 2
fi
if [ "$SKIPPED" -gt 0 ]; then
  echo "every failure mode that could be measured behaved the way README section 16 says."
  echo "The skipped ones need a sale in flight: run this alongside make load-small."
else
  echo "every failure mode behaved the way README section 16 says it does."
fi
