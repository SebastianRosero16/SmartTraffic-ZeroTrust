# Traffic density processor

`TrafficDensityTopology` consumes JSON `TrafficReading` records from Kafka,
re-keys them by zone, aggregates them in event-time tumbling windows, and
publishes each updated `TrafficUpdate` through the
`TrafficUpdatePublisher` output port. A constructor-injected density strategy
calculates the window value: the default is the simple average, and
`time-weighted` gives more weight to more recent readings. The Kafka window
state stores sufficient statistics rather than retaining every reading.
Late results for an older window do not replace a zone status already advanced
by a newer window; corrections to the latest window remain eligible.

The Kafka Streams topology uses the shared OAuth client configuration. The
window size, allowed lateness, and status thresholds are mandatory runtime
settings; no project-wide defaults have been approved. A zero grace period
means records arriving after Kafka Streams closes their event-time window are
late and are not included.

`RabbitMqOAuthTrafficUpdatePublisher` implements the output port using OAuth
2.0 `client_credentials` and AMQPS. It publishes persistent JSON messages and
waits for publisher confirms. Unroutable messages and token/publish failures
are surfaced to the caller. `TrafficDensityProcessorApplication` starts as a
Spring Boot application, wires the publisher to Kafka Streams, and closes both
services during JVM shutdown. Start the processor with
`edu.smarttraffic.processor.TrafficDensityProcessorApplication` after
configuring both the Kafka and RabbitMQ variables below.

The runtime wires a `TrafficEventLoggingObserver` into the domain aggregate.
It logs status transitions and critical-density events; traffic updates are
still published through the existing RabbitMQ output port.

## Required configuration

In addition to the Kafka OAuth client environment listed in the
[simulator README](../traffic-sensor-simulator/README.md), configure:

| Variable | Purpose |
|---|---|
| `KAFKA_STREAMS_APPLICATION_ID` | Unique Kafka Streams application ID. |
| `KAFKA_TRAFFIC_READINGS_TOPIC` | Source topic; selected name is `traffic_readings`. |
| `TRAFFIC_WINDOW_SECONDS` | Positive event-time tumbling window size. |
| `TRAFFIC_WINDOW_GRACE_SECONDS` | Non-negative allowed lateness for the window. |
| `TRAFFIC_DENSITY_STRATEGY` | `average` (default) or `time-weighted`. |
| `TRAFFIC_MODERATE_THRESHOLD` | Density at which the state becomes `MODERADA`. |
| `TRAFFIC_CONGESTED_THRESHOLD` | Density at which the state becomes `CONGESTIONADA`; must exceed the moderate threshold. |

## RabbitMQ publisher configuration

The adapter requires the following environment variables when it is
constructed:

| Variable | Purpose |
|---|---|
| `RABBITMQ_HOST` | RabbitMQ hostname; its certificate hostname is verified. |
| `RABBITMQ_PORT` | AMQPS port. |
| `RABBITMQ_VHOST` | Target virtual host. |
| `RABBITMQ_EXCHANGE` | Exchange to publish to. |
| `RABBITMQ_ROUTING_KEY` | Routing key; defaults to the empty string if omitted. |
| `AUTH_TOKEN_ENDPOINT_URI` | HTTPS OAuth token endpoint. |
| `RABBITMQ_OAUTH_CLIENT_ID` | OAuth client ID and RabbitMQ SASL username. |
| `RABBITMQ_OAUTH_CLIENT_SECRET` | OAuth client secret; provide through a secret manager or protected environment. |
| `RABBITMQ_OAUTH_SCOPE` | OAuth scope requested for publishing. |
| `AUTH_TLS_CA_CERT_PATH` | Readable PEM CA certificate used to verify the token endpoint. |
| `RABBITMQ_TLS_CA_CERT_PATH` | Readable PEM CA certificate used to verify the RabbitMQ server. |

Use only authorized client credentials and CA certificates. The adapter
verifies both TLS peers and does not enable automatic RabbitMQ connection
recovery, so a reconnect obtains a fresh token when necessary. Publishing is
not end-to-end exactly-once: a Kafka replay after an uncertain publish result
can produce duplicate updates.

The topology behavior is tested with Kafka's `TopologyTestDriver`; those tests
do not require a live broker.

The gated `TrafficUpdateEndToEndIntegrationTest` verifies a reading sent
through Kafka Streams is received as a `TrafficUpdate` from RabbitMQ. It runs
only with `TRAFFIC_FLOW_IT=true` and requires isolated test brokers, the test
Auth Server, and local test-only reader credentials and CA material. Start the
simulator and processor with their test identities before running this test.

On 2026-11-14, the test passed against an isolated Kafka 4.4.0 broker with the
Strimzi OAuth plugin 0.18.1 and RabbitMQ 4.3.7. The simulator published using
its test OAuth identity, and the test reader received an update for the
expected test zone. The generated credentials and certificates were kept
outside the repository and removed after the run. This result applies only to
that disposable local setup, not to production.
