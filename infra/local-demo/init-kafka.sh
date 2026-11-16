#!/usr/bin/env bash
set -euo pipefail

topics=/opt/kafka/bin/kafka-topics.sh
acls=/opt/kafka/bin/kafka-acls.sh

until "$topics" \
  --bootstrap-server "$KAFKA_BOOTSTRAP_SERVERS" \
  --command-config "$KAFKA_ADMIN_PROPERTIES" \
  --list >/dev/null 2>&1; do
  sleep 2
done

"$topics" \
  --bootstrap-server "$KAFKA_BOOTSTRAP_SERVERS" \
  --command-config "$KAFKA_ADMIN_PROPERTIES" \
  --create --if-not-exists \
  --topic traffic_readings \
  --partitions 1 \
  --replication-factor 1

"$acls" --bootstrap-server "$KAFKA_BOOTSTRAP_SERVERS" \
  --command-config "$KAFKA_ADMIN_PROPERTIES" \
  --add --allow-principal User:traffic-simulator-it \
  --operation Write --operation Describe --topic traffic_readings
"$acls" --bootstrap-server "$KAFKA_BOOTSTRAP_SERVERS" \
  --command-config "$KAFKA_ADMIN_PROPERTIES" \
  --add --allow-principal User:traffic-simulator-it \
  --operation IdempotentWrite --cluster

"$acls" --bootstrap-server "$KAFKA_BOOTSTRAP_SERVERS" \
  --command-config "$KAFKA_ADMIN_PROPERTIES" \
  --add --allow-principal User:traffic-archiver-it \
  --operation Read --operation Describe --topic traffic_readings
"$acls" --bootstrap-server "$KAFKA_BOOTSTRAP_SERVERS" \
  --command-config "$KAFKA_ADMIN_PROPERTIES" \
  --add --allow-principal User:traffic-archiver-it \
  --operation Read --group traffic-archiver

"$acls" --bootstrap-server "$KAFKA_BOOTSTRAP_SERVERS" \
  --command-config "$KAFKA_ADMIN_PROPERTIES" \
  --add --allow-principal User:traffic-processor-it \
  --operation Read --operation Describe --topic traffic_readings
"$acls" --bootstrap-server "$KAFKA_BOOTSTRAP_SERVERS" \
  --command-config "$KAFKA_ADMIN_PROPERTIES" \
  --add --allow-principal User:traffic-processor-it \
  --operation Read --group traffic-density-demo
"$acls" --bootstrap-server "$KAFKA_BOOTSTRAP_SERVERS" \
  --command-config "$KAFKA_ADMIN_PROPERTIES" \
  --add --allow-principal User:traffic-processor-it \
  --operation Create --operation Read --operation Write --operation Describe \
  --topic traffic-density-demo- \
  --resource-pattern-type prefixed
"$acls" --bootstrap-server "$KAFKA_BOOTSTRAP_SERVERS" \
  --command-config "$KAFKA_ADMIN_PROPERTIES" \
  --add --allow-principal User:traffic-processor-it \
  --operation IdempotentWrite --cluster
