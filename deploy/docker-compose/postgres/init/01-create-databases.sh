#!/bin/sh
# Creates one database per owning service (README section 11).
#
# The Postgres entrypoint runs this on an empty data directory only, before the server
# accepts connections from outside, which is the one moment CREATE DATABASE is free of
# concurrent clients. Flyway then owns the schema inside each database; nothing here
# creates a table.
#
# A shell script rather than plain SQL, because the database names come from .env.
set -eu

maintenance_database="${POSTGRES_DB:-seatrush}"

database_exists() {
  psql --username "$POSTGRES_USER" --dbname "$maintenance_database" \
    --tuples-only --no-align --quiet \
    --command "SELECT 1 FROM pg_database WHERE datname = '$1'" |
    grep -q '^1$'
}

create_database() {
  name="$1"
  # CREATE DATABASE has no IF NOT EXISTS, and the entrypoint also replays this file when
  # someone mounts an initialised data directory by hand.
  if database_exists "$name"; then
    echo "database $name is already there"
    return
  fi
  psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$maintenance_database" \
    --command "CREATE DATABASE \"$name\" OWNER \"$POSTGRES_USER\""
  echo "created database $name"
}

create_database "${POSTGRES_INVENTORY_DB:-seatrush_inventory}"
create_database "${POSTGRES_ORDER_DB:-seatrush_order}"
create_database "${POSTGRES_PAYMENT_DB:-seatrush_payment}"
