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
