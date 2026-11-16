# Query client

`query-client` periodically sends asynchronous queries for one or more zones.
Each request gets a UUID `requestId`, uses it as the AMQP `correlationId`, sets
`replyTo` to its instance-specific response queue, and carries the instance ID
used as the response-exchange routing key. On connection, the client declares
that queue as non-durable, exclusive, and auto-delete, then binds it to the
configured response exchange. Responses that do not match an active request
are acknowledged and discarded; malformed or mismatched responses are rejected
without requeueing.

## Configuration

The OAuth/TLS variables are the same as those documented by the
[dashboard backend](../user-dashboard-backend/README.md). Configure these
additional variables:

| Variable | Purpose |
|---|---|
| `RABBITMQ_QUERY_QUEUE` | Existing work queue for traffic queries. |
| `RABBITMQ_QUERY_ANSWERS_EXCHANGE` | Existing topic exchange for query responses. |
| `RABBITMQ_QUERY_REPLY_QUEUE` | Queue name declared by this client as exclusive and auto-delete; use a unique name per instance. |
| `RABBITMQ_QUERY_CLIENT_ID` | Instance ID and response-exchange routing key. |
| `QUERY_ZONE_IDS` | Comma-separated, non-empty, unique zone IDs. |
| `QUERY_INTERVAL_SECONDS` | Query cadence; defaults to 10 seconds. |
| `QUERY_TIMEOUT_SECONDS` | Maximum wait for each response; defaults to 10 seconds. |

`RABBITMQ_OAUTH_SCOPE` must grant write access to the request queue through
`amq.default`, configure/write/read access to this instance's reply queue, and
read access to the response exchange plus write access to its routing key for
the binding. The client
declares and binds only its reply queue; shared queues and exchanges remain
pre-provisioned. The fixture values `query-requests-it`, `query-replies-it`,
`query-answers-it`, and `client-it` are test-only; production names and scopes
remain pending team approval.

## Run and test

From the repository root:

```powershell
mvn -B -pl services/query-client -am test
mvn -B -pl services/query-client -am install
mvn -pl services/query-client exec:java
```

Running requires RabbitMQ, the OAuth token service, trusted CA certificates,
and provisioned shared queues/exchanges. The broker-backed round-trip test is
gated by `RABBITMQ_IT=true`; its test setup and disposable credentials are
documented in the [RabbitMQ fixture README](../../infra/vm-data/rabbitmq-auth-test/README.md).
