#!/bin/sh
# Creates the topics of README section 10.1 on the local broker.
#
# Topics are never auto-created: the broker has auto-creation off, so a producer that
# mistypes a topic name fails loudly instead of getting a fresh single-partition topic
# that silently breaks the ordering the partition count is there to provide.
#
# Every business topic gets 12 partitions and is keyed by orderId (all messages of one
# SAGA in order) or by eventId (ordered seat-map updates per event). Each one gets a
# companion .dlq with 3 partitions, where a consumer parks a poison message once its
# retries are exhausted.
set -eu

bootstrap="${BOOTSTRAP_SERVER:-kafka:9092}"
kafka_topics=/opt/kafka/bin/kafka-topics.sh

business_topics="
inventory.seat-events
inventory.commands
inventory.replies
payment.commands
payment.replies
order.events
"

create_topic() {
  # One broker locally, so a single replica is the only possible factor. --if-not-exists
  # makes this script safe to run on every make up.
  "$kafka_topics" --bootstrap-server "$bootstrap" \
    --create --if-not-exists \
    --topic "$1" --partitions "$2" --replication-factor 1
  echo "topic $1 ready with $2 partitions"
}

for topic in $business_topics; do
  create_topic "$topic" 12
  create_topic "$topic.dlq" 3
done

echo "topics on $bootstrap:"
"$kafka_topics" --bootstrap-server "$bootstrap" --list
