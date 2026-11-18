# Query client

The service starts as a Spring Boot application and manages its RabbitMQ
client lifecycle through the application context.

`query-client` periodically sends asynchronous queries for one or more zones.
Each request gets a UUID `requestId`, uses it as the AMQP `correlationId`, sets
`replyTo` to its provisioned response queue, and carries the instance ID used
as the response-exchange routing key. Responses that do not match an active
request are acknowledged and discarded; malformed or mismatched responses are
rejected without requeueing.

## Configuration

The OAuth/TLS variables are the same as those documented by the
[dashboard backend](../user-dashboard-backend/README.md). Configure these
additional variables:

| Variable | Purpose |
|---|---|
| `RABBITMQ_QUERY_EXCHANGE` | Existing direct exchange used to publish requests. |
| `RABBITMQ_QUERY_QUEUE` | Existing work queue for traffic queries. |
| `RABBITMQ_QUERY_REPLY_QUEUE` | Existing queue provisioned for this client instance and bound to its routing key. |
| `RABBITMQ_QUERY_CLIENT_ID` | Instance ID and response-exchange routing key. |
| `QUERY_ZONE_IDS` | Comma-separated, non-empty, unique zone IDs. |
| `QUERY_INTERVAL_SECONDS` | Query cadence; defaults to 10 seconds. |
| `QUERY_TIMEOUT_SECONDS` | Maximum wait for each response; defaults to 10 seconds. |

`RABBITMQ_OAUTH_SCOPE` must grant write access to the request exchange and read
access to this instance's reply queue. The exchange is bound to the request
queue using the queue name as its routing key. A named exchange keeps the
publisher away from RabbitMQ's default exchange, which cannot restrict writes
to one queue. The client does not declare or bind queues or exchanges. The
fixture values `query-requests-it`, `query-replies-it`, and `client-it` are
test-only.

## Run and test

From the repository root:

```powershell
mvn -B -pl services/query-client -am test
mvn -B -pl services/query-client -am install
mvn -pl services/query-client exec:java
```

Running requires RabbitMQ, the OAuth token service, trusted CA certificates,
and provisioned queues. The unit tests do not validate broker connectivity or
production deployment.
