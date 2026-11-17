# Traffic archiver Kafka adapter

`KafkaTrafficReadingArchiveConsumer` reads `TrafficReading` JSON records from
`KAFKA_TRAFFIC_READINGS_TOPIC` using the OAuth configuration shared with the
other Kafka clients. It uses the independent consumer group
`traffic-archiver`, archives records through `ArchiveTrafficReadingUseCase`,
and commits offsets only after the full polled batch has been archived.

The adapter provides at-least-once delivery. If archiving a record fails, the
poll operation fails without committing the batch; the database adapter's
`insertIfAbsent` keeps replayed event IDs idempotent.

The Spring Boot application wires the use case, persistence adapter, and
consumer. Set the Kafka OAuth variables documented in the
[simulator README](../traffic-sensor-simulator/README.md), plus
`KAFKA_TRAFFIC_READINGS_TOPIC`, `SPRING_DATASOURCE_URL`,
`SPRING_DATASOURCE_USERNAME`, and `SPRING_DATASOURCE_PASSWORD`.

Build the executable jar with `mvn -pl services/traffic-archiver -am package`,
then run it with:

```powershell
java -jar services/traffic-archiver/target/traffic-archiver-0.1.0-SNAPSHOT.jar
```

## PostgreSQL integration test

The PostgreSQL integration test is skipped by default. To run it locally, copy
`infra/vm-data/postgres-it/.env.example` to an ignored `.env` file beside the
Compose file, replace the local-only password, then start PostgreSQL from the
repository root:

```powershell
docker compose --env-file infra/vm-data/postgres-it/.env `
  -f infra/vm-data/postgres-it/compose.yaml up -d --wait

$env:TRAFFIC_ARCHIVER_PG_IT = "true"
$env:SPRING_DATASOURCE_URL = "jdbc:postgresql://localhost:5433/smarttraffic"
$env:SPRING_DATASOURCE_USERNAME = "smarttraffic_it"
$env:SPRING_DATASOURCE_PASSWORD = "<the local password>"
mvn -B -pl services/traffic-archiver -am test
```

The test applies the Flyway migration, checks Hibernate schema validation, and
verifies that replaying an event ID does not overwrite the first archived
reading. These local fixture credentials and the Compose port binding must not
be reused for deployment. Stop the fixture with `docker compose` using the same
arguments and `down`; add `-v` only when intentionally deleting its test data.
