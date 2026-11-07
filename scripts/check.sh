#!/usr/bin/env sh
# The invariant checker of README section 17, pointed at the local stack.
#
# The tool itself takes flags and reads nothing but its own environment variables (run it with
# --help). This wrapper exists for the two things it deliberately does not do: find its own jar,
# and know where the stack it is checking is listening.
#
# The addresses come from .env, which is the one file that already knows them: make up starts
# Postgres and Redis on the ports written there, so a stack on a moved port is checked on the moved
# port without anybody passing a flag. A variable already set in the shell wins over the file, and a
# flag passed here wins over both, which is the same order the tool documents for itself.
#
# Anything after the script name is handed straight to the checker:
#   ./scripts/check.sh                       the whole catalog, waiting for checkouts to finish
#   ./scripts/check.sh --settle-timeout 0    a look at a sale that is still running
#   ./scripts/check.sh --event <uuid>        one event of a catalog with several
#
# A cluster has no published ports, so there it is two port-forwards and the flags that match
# (README section 23).
set -eu

# Run from the repository root whatever directory this was called from: the jar and .env are both
# found relative to it.
cd "$(dirname "$0")/.."

MVNW="${MVNW:-./mvnw}"
MODULE=load-tests/invariant-checker

# Only the variables the checker reads, and only when the shell has not already set them. Reading
# the whole file with `.` would execute it, and .env is a file people edit.
if [ -f .env ]; then
  for name in POSTGRES_HOST POSTGRES_PORT POSTGRES_USER POSTGRES_PASSWORD \
    POSTGRES_INVENTORY_DB POSTGRES_ORDER_DB POSTGRES_PAYMENT_DB \
    REDIS_HOST REDIS_PORT; do
    eval "already=\${$name:-}"
    [ -n "$already" ] && continue
    value=$(sed -n "s/^${name}=\\(.*\\)\$/\\1/p" .env | tail -n 1)
    [ -n "$value" ] && export "$name=$value"
  done
fi

JAR=$(ls "$MODULE"/target/*-boot.jar 2>/dev/null | head -n 1 || true)
if [ -z "$JAR" ]; then
  echo "building the invariant checker, which is a one-off" >&2
  # -am because the module has the test support of the reactor behind it, and without the tests
  # because this is the tool being built and not the build being run.
  $MVNW -B -ntp -q -pl "$MODULE" -am -DskipTests package
  JAR=$(ls "$MODULE"/target/*-boot.jar | head -n 1)
fi

# exec, so the exit code of the checker is the exit code of this script: 0 for a clean run, 1 for a
# violated invariant and 2 for a check that could not be made. That code is the whole reason this is
# runnable from a benchmark script and from CI.
exec java -jar "$JAR" "$@"
