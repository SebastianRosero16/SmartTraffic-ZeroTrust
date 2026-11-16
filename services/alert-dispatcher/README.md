# Alert dispatcher

`alert-dispatcher` consumes `TrafficUpdate` messages, keeps the latest
timestamped state per zone in memory, logs an alert for each status transition,
and serves `TrafficQuery` requests from that local snapshot. The initial state
is treated as `DESPEJADA`; a first `MODERADA` or `CONGESTIONADA` update
therefore emits an alert. Recovery transitions are logged as well. Alerts are
currently application logs, not email, SMS, or a separately persisted event.

Query responses preserve the request ID in both the payload and AMQP
`correlationId`. Replies are published to the configured topic exchange using
the request's `instanceId` as routing key. `replyTo` must identify the
requester's provisioned response queue. A response is confirmed before its
request is acknowledged.

## Configuration

The OAuth/TLS variables are the same as those documented by the
[dashboard backend](../user-dashboard-backend/README.md). In addition,
configure:

| Variable | Purpose |
|---|---|
| `RABBITMQ_ALERT_QUEUE` | Existing queue bound to the traffic update fanout exchange. |
| `RABBITMQ_QUERY_QUEUE` | Existing work queue for traffic queries. |
| `RABBITMQ_QUERY_ANSWERS_EXCHANGE` | Existing topic exchange for query responses. |
| `RABBITMQ_QUERY_REPLY_ROUTES` | Comma-separated `instanceId=replyQueue` allowlist; each queue must be bound to the matching instance routing key. |

`RABBITMQ_OAUTH_SCOPE` must grant read access to both queues and write access
to the response exchange and its authorized routing keys. The service checks
that each query's `replyTo` matches the configured queue for its `instanceId`.
It does not declare queues or exchanges.
Use durable queue/exchange definitions and least-privilege scopes. The
`*-it` names in the RabbitMQ fixture are test-only; production names and scopes
remain pending team approval.

## Run and test

From the repository root:

```powershell
mvn -B -pl services/alert-dispatcher -am test
mvn -B -pl services/alert-dispatcher -am install
mvn -pl services/alert-dispatcher exec:java
```

Running requires RabbitMQ, the OAuth token service, trusted CA certificates,
and the configured topology. The unit tests do not establish that broker
integration or production deployment has been validated.
