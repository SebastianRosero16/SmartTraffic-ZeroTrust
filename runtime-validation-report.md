# Runtime Validation Report

**Generated:** 2026-11-15
**Target:** SmartTraffic auth and messaging flows
**Branch/base:** `steven/auth-messaging-validation` from `bdff99f2978f26ce8683c2f069a9a6b047925cd3`

## Summary

| Area | Status | Evidence |
|---|---|---|
| Auth Server token claims | PASS | `mvn -B -pl services/auth-server test`: 12 tests passed. |
| Simulator and processor unit/topology tests | PASS | Module builds passed; Kafka/Rabbit broker tests were skipped or not run. |
| Dashboard consumer unit tests | PASS | Consumer unit tests passed; no live RabbitMQ connection was exercised. |
| RabbitMQ OAuth integration | BLOCKED | The isolated RabbitMQ 4.3.7 container exited before healthy startup with `.erlang.cookie: eacces`; the suite was not run. |
| Kafka broker / complete simulator → Kafka → processor → RabbitMQ flow | BLOCKED | No Kafka broker or test `.env`, TLS assets, JAAS credentials, or ACL-backed test identity configuration was available. No Kafka integration command was run. |
| Auth Server HTTPS certificate | PASS | OpenSSL verified the generated test CA chain and `host.docker.internal` hostname. |

**Overall:** NEEDS_SIGNOFF. Unit and in-process Auth tests pass, but neither a live Kafka integration nor a live RabbitMQ OAuth suite passed in this run.

## Environment and test credentials

- Docker daemon, Maven, Java 21, Node.js, and OpenSSL were available.
- The only running container before setup was an unrelated MongoDB container. No Kafka, Auth Server, or RabbitMQ test service was available.
- Generated disposable RabbitMQ/Auth test keys, certificates, and client credentials under `%TEMP%\smarttraffic-rabbitmq-it`, outside the repository. No token, password, private key, or certificate was added to Git or printed in test output.
- Started the Auth Server with the generated test configuration. Its TLS certificate chain and hostname validated successfully. Stopped the test server and removed the temporary test directory after validation.
- Did not use or restart the previously reported unhealthy Kafka container. Did not create or access AWS resources.

## Commands and results

| Command | Result |
|---|---|
| `mvn -B -pl services/auth-server test` | Exit 0; 12 passed, 0 failed, 0 errors, 0 skipped. The MockMvc integration test verifies an RS256 token for test-only `sensor-client`, including issuer `https://auth.test.smarttraffic`, audience `urn:smarttraffic:kafka`, and scope `traffic.readings.publish`; it also verifies public JWKS content and invalid client/scope rejection. |
| `mvn -B -pl services/traffic-sensor-simulator -am test` | Exit 0; 35 passed, 0 failed, 0 skipped across the reactor. These are unit/client configuration tests, not broker integration. |
| `mvn -B -pl services/traffic-density-processor -am test` | Exit 0; 48 passed, 0 failed, 3 skipped across the reactor. The skipped tests are broker-gated RabbitMQ publisher and end-to-end checks; topology tests use `TopologyTestDriver`, not a live Kafka broker. |
| `mvn -B -pl services/user-dashboard-backend -am test` | Exit 0; 20 passed, 0 failed, 0 skipped across the reactor, including mocked RabbitMQ consumer tests. |
| `.\infra\vm-data\rabbitmq-auth-test\prepare-test-assets.ps1` | Exit 0; generated test-only RSA/TLS assets and random RabbitMQ client credentials outside the repository. |
| `mvn -pl services/auth-server spring-boot:run "-Dspring-boot.run.arguments=--spring.config.additional-location=file:<temp-test-config>"` | Started; Auth Server listened on HTTPS `8443` with the generated test configuration. |
| Set `RABBITMQ_TEST_CERT_DIR` to the generated temp directory and `RABBITMQ_OAUTH_JWKS_URI` to `https://host.docker.internal:8443/oauth2/jwks`, then `docker compose --project-name smarttraffic-rabbitmq-it-steven-20261115 --file infra\\vm-data\\rabbitmq\\compose.yaml config --quiet` | Exit 0; fixture configuration rendered successfully. |
| Same environment, then `docker compose --project-name smarttraffic-rabbitmq-it-steven-20261115 --file infra\\vm-data\\rabbitmq\\compose.yaml up --detach` | Command exit 0, but the container exited with code 1 and was unhealthy. Broker log: `Error when reading /var/lib/rabbitmq/.erlang.cookie: eacces`. This is a startup blocker, not an OAuth test failure. |
| `docker run ... maven:3.9.9-eclipse-temurin-17 mvn test` (RabbitMQ suite) | Not run: RabbitMQ did not reach healthy startup, so the broker-dependent suite was skipped rather than reported as pass or fail. |
| `mvn -pl shared/kafka-security -Pkafka-it verify` | Not run: no healthy isolated Kafka broker, external Auth/JWKS settings, test TLS material, JAAS credentials, topic, or pre-provisioned ACLs were available. |
| `mvn -B -pl services/traffic-density-processor -am test` with `TRAFFIC_FLOW_IT=true` | Not run: the required Kafka and RabbitMQ brokers and test Auth Server/reader setup were not available. |
| `openssl s_client -connect 127.0.0.1:8443 -servername host.docker.internal -CAfile <test-ca> -verify_return_error -verify_hostname host.docker.internal -brief` | Exit 0; `Verification: OK`, verified peer name `host.docker.internal`. |
| `git diff --check` | Pass. |

## Identity and authorization evidence

The Auth Server requires per-client external configuration for secrets, scopes, and audiences. No production service-client registry or approved production audience/scope mapping is committed. Therefore this run does not certify production identities.

- Auth Server unit/in-process test identity: `sensor-client`; test-only issuer `https://auth.test.smarttraffic`, audience `urn:smarttraffic:kafka`, and scope `traffic.readings.publish`.
- The isolated RabbitMQ test-asset generator defines test-only clients `rabbit-publisher-it` and `rabbit-reader-it`, both with audience `smarttraffic-rabbitmq-it`. The publisher receives write scopes for fixture resources; the reader receives a read scope for `dashboard-updates-it`. Because RabbitMQ did not start, these runtime identities were not verified against the broker in this run.
- Kafka documentation uses `smarttraffic-kafka` as the expected audience for its local broker test, while the Auth Server unit test uses the distinct `urn:smarttraffic:kafka` value. These are separate test configurations; this report does not silently equate them or change either value.

## Resource and protocol inventory

These are source/configuration names and test values, not production approvals:

- **Kafka topic:** `traffic_readings`, supplied to applications as `KAFKA_TRAFFIC_READINGS_TOPIC`. The simulator is the producer; the processor uses Kafka Streams; the archiver has an independent consumer group. Clients use `SASL_SSL` / `OAUTHBEARER`. The fixture parameterizes `CLIENT_PORT` and `CONTROLLER_PORT`; it does not commit production port values. Kafka ACLs, rather than `KAFKA_OAUTH_SCOPE`, authorize topic/group operations.
- **Kafka client/Auth variables:** `KAFKA_BOOTSTRAP_SERVERS`, `AUTH_TOKEN_ENDPOINT_URI`, `KAFKA_OAUTH_CLIENT_ID`, `KAFKA_OAUTH_CLIENT_SECRET`, optional `KAFKA_OAUTH_SCOPE`, `KAFKA_SSL_TRUSTSTORE_*`, and separate `AUTH_SSL_TRUSTSTORE_*`. The fixture additionally requires external `.env`, TLS files, a `KafkaClient` JAAS file, audience/issuer/JWKS configuration, and pre-provisioned ACLs.
- **RabbitMQ test resources:** vhost `smarttraffic-it`; fanout exchange `traffic-updates-it`; queues `dashboard-updates-it`, `alert-updates-it`, `query-requests-it`, and `query-replies-it`; topic exchange `query-answers-it`. The fixture binds `traffic-updates-it` to the dashboard and alert queues, and `query-answers-it` to `query-replies-it` with routing key `client-it`. These names are fixture definitions; alert/query services are not available in the published base.
- **RabbitMQ application config:** processor requires `RABBITMQ_HOST`, `RABBITMQ_PORT`, `RABBITMQ_VHOST`, `RABBITMQ_EXCHANGE`, OAuth client ID/secret/scope, HTTPS token endpoint, and separate Auth/broker CA paths. The dashboard requires corresponding broker and OAuth settings plus `RABBITMQ_DASHBOARD_QUEUE`. Its development default queue `dashboard_updates_queue` is not the fixture queue.
- **RabbitMQ test listener:** the isolated fixture listens on AMQPS `5671` only inside its Docker network and publishes no host port. Its test Auth Server uses HTTPS `8443`; neither value is a production assignment. RabbitMQ validates JWKS TLS separately from AMQPS client-certificate settings.

## Known limitation

The prior documented RabbitMQ fixture run observed acceptance of a token signed by a trusted key with a different `iss`. That observation remains limited to that fixture/configuration/run; the current attempt could not repeat it because the broker failed before startup. Do not claim issuer-equality validation or generalize the prior observation to all RabbitMQ configurations.

## Required follow-up

1. Resolve the isolated RabbitMQ fixture's cookie-permission startup failure without weakening its authentication, TLS, or container security settings; then run the documented Maven-container suite.
2. Coordinate a fresh Kafka broker/Auth/JWKS test environment with valid test TLS material, identities, topic, and least-privilege ACLs. Then run the Kafka OAuth broker profile and the simulator-to-processor flow.
3. Keep production issuer/audience/scope/resource names and listeners pending approval. Do not use the test `urn:smarttraffic:kafka`, `smarttraffic-kafka`, or `*-it` values as production defaults.
