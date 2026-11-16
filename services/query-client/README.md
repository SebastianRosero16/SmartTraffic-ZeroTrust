# Query client

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
| `RABBITMQ_QUERY_QUEUE` | Existing work queue for traffic queries. |
| `RABBITMQ_QUERY_REPLY_QUEUE` | Existing queue provisioned for this client instance and bound to its routing key. |
| `RABBITMQ_QUERY_CLIENT_ID` | Instance ID and response-exchange routing key. |
| `QUERY_ZONE_IDS` | Comma-separated, non-empty, unique zone IDs. |
| `QUERY_INTERVAL_SECONDS` | Query cadence; defaults to 10 seconds. |
| `QUERY_TIMEOUT_SECONDS` | Maximum wait for each response; defaults to 10 seconds. |

`RABBITMQ_OAUTH_SCOPE` must grant write access to the request queue and read
access to this instance's reply queue. The client does not declare or bind
queues. The fixture values `query-requests-it`, `query-replies-it`, and
`client-it` are test-only; production names and scopes remain pending team
approval.

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
