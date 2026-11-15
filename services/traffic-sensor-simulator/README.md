# Traffic sensor simulator

The simulator generates `TrafficReading` contract records and publishes them
to Kafka as JSON. Each record uses its zone ID as the Kafka key and its
`occurredAt` value as the Kafka record timestamp. The producer uses the shared
Kafka OAuth client configuration and waits for broker acknowledgement before
generating the next reading.

## Required configuration

All settings are supplied through the process environment. The Kafka OAuth
client also requires `KAFKA_BOOTSTRAP_SERVERS`,
`AUTH_TOKEN_ENDPOINT_URI`, `KAFKA_OAUTH_CLIENT_ID`, and
`KAFKA_OAUTH_CLIENT_SECRET`; TLS truststore settings are required when the
configured endpoints require them. Do not put credentials in this document or
in Git.

| Variable | Purpose |
|---|---|
| `KAFKA_TRAFFIC_READINGS_TOPIC` | Input topic; the selected name is `traffic_readings`. |
| `TRAFFIC_SENSOR_IDS` | Comma-separated, non-empty sensor IDs. |
| `TRAFFIC_ZONE_IDS` | Comma-separated, non-empty zone IDs. |
| `TRAFFIC_MIN_VEHICLES` | Inclusive minimum generated vehicle count. |
| `TRAFFIC_MAX_VEHICLES` | Inclusive maximum generated vehicle count. |
| `TRAFFIC_INTERVAL_MS` | Positive delay between acknowledged readings. |

## Run

Build and install the reactor modules from the repository root, then start the
simulator module with the configured environment:

```powershell
mvn install
mvn -pl services/traffic-sensor-simulator exec:java
```

The process reports each acknowledged event's ID, topic, partition, and
offset. Configuration errors, serialization failures, and Kafka send failures
are surfaced rather than treated as successful publications.
