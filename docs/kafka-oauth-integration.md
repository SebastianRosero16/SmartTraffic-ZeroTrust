# Kafka OAuth integration

This stage adds reusable Java client properties for SASL/OAUTHBEARER over
SASL_SSL. It does not configure the broker, create topics, or start a Kafka
client: the simulator, processor, and archiver do not yet have Kafka runtime
adapters. The end-to-end checks remain blocked on Héctor's broker and an
available Auth Server.

## Client configuration

`shared/kafka-security` builds Kafka producer, consumer, and Streams properties
from external environment variables. Each service must receive its own
OAuth client ID and secret; never share credentials between services or commit
them to Git.

| Environment variable | Required | Purpose |
|---|---:|---|
| `KAFKA_BOOTSTRAP_SERVERS` | Yes | Parametrized Kafka TLS listener, for example `<broker-host>:<tls-port>`. |
| `AUTH_TOKEN_ENDPOINT_URI` | Yes | HTTPS OAuth token endpoint, for example `https://<auth-host>/oauth2/token`. |
| `KAFKA_OAUTH_CLIENT_ID` | Yes | The service-specific OAuth client registered in Auth Server. |
| `KAFKA_OAUTH_CLIENT_SECRET` | Yes | Secret injected at runtime from a secret manager or protected environment. |
| `KAFKA_OAUTH_SCOPE` | No | Optional token scope. Kafka ACLs, not scopes, authorize Kafka operations. |
| `KAFKA_SSL_TRUSTSTORE_LOCATION` | No | External truststore path when the broker certificate is not trusted by the JVM defaults. |
| `KAFKA_SSL_TRUSTSTORE_PASSWORD` | No | External truststore password; only valid when a truststore path is supplied. |
| `KAFKA_SSL_TRUSTSTORE_TYPE` | No | Truststore type, for example `PKCS12`. |

The client sends its credentials to the token endpoint through Strimzi's OAuth
login callback. It does not send an audience request parameter: Auth Server
currently configures audiences per client. For this approved local-cluster
stage, each of the three OAuth clients must be configured by the team to receive
the `smarttraffic-kafka` audience. The broker must validate the configured
issuer and audience as well as the JWT signature using the Auth Server JWKS
endpoint. Token and JWKS URLs must use HTTPS.

Scopes may be added to tokens, but the Kafka broker does not use them as the
resource authorization policy. The effective authorization is the broker's
ACL decision for the authenticated principal. `traffic_readings`, group names,
and the audience are approved for this local-cluster stage only; they are not
deployment approvals.

## ACL proposal for the local cluster

The principal-to-client-ID mapping must be confirmed against Héctor's broker
configuration before ACLs are applied. The following is the minimum proposed
resource access; no service receives topic `CREATE` permission.

| Service identity | Resource | Minimum proposed operations |
|---|---|---|
| `<simulator-client-id>` | Topic `traffic_readings` | `WRITE`, `DESCRIBE` |
| `<simulator-client-id>` | Cluster | `IDEMPOTENT_WRITE` because the producer enables idempotence |
| `<archiver-client-id>` | Topic `traffic_readings` | `READ`, `DESCRIBE` |
| `<archiver-client-id>` | Group `traffic-archiver` | `READ` |
| `<processor-client-id>` | Topic `traffic_readings` | `READ`, `DESCRIBE` |
| `<processor-client-id>` | Group `traffic-density-processor` | `READ` |
| `<processor-client-id>` | Kafka Streams internal topics | Only the required `READ`, `WRITE`, and `DESCRIBE` on the approved application-ID prefix; `IDEMPOTENT_WRITE` on the cluster if its producer uses idempotence |

The topic `traffic_readings` must be provisioned by an administrative identity.
Kafka Streams internal-topic names depend on the topology, which is not yet
implemented. Once that topology is agreed, an administrator must provision its
changelog/repartition topics before startup if service identities are not to
receive `CREATE`. The Streams ACLs and any additional `DESCRIBE` or cluster
permissions must be confirmed by an integration test; they are not claimed as
verified here. Broker-side topic auto-creation should also be disabled.

These ACLs are a request for Héctor to coordinate and apply in the broker PR;
this change does not modify Docker, Compose, listener settings, or ACL state.

## Token expiry and reauthentication

An authenticated Kafka connection can outlive the JWT used at its initial
SASL exchange. Broker configuration must therefore set
`connections.max.reauth.ms` to a value no greater than the Auth Server access
token lifetime, with the final values coordinated by the team. At each forced
reauthentication, the client callback obtains a current token and the broker
revalidates issuer, audience, signature, and expiry. The actual broker/client
reauthentication behavior must be verified end-to-end before deployment; this
Java module cannot enforce broker session expiry.

## Tests and pending integration checks

The unit tests run without Spring Boot, a broker, containers, or sockets:

```powershell
mvn -pl shared/kafka-security test
```

They verify the generated SASL_SSL/OAUTHBEARER producer, consumer, and Streams
properties; required credentials; HTTPS-only token URLs; optional truststore
settings; and rejection of malformed JAAS input.

## Reproducible broker integration profile

The `kafka-it` Maven profile runs `KafkaOAuthBrokerIT` with Failsafe. It is
disabled by default and requires an already-running broker, an accessible Auth
Server/JWKS, externally injected test credentials, and pre-provisioned ACLs.
The test never creates topics.

Run it from the repository root after setting the required environment
variables from a protected local source:

```powershell
mvn -pl shared/kafka-security -Pkafka-it verify
```

The authorized producer uses the standard variables from the client
configuration table above. The integration profile additionally requires:

| Environment variable | Purpose |
|---|---|
| `KAFKA_IT_TOPIC` | Existing topic where the authorized producer has `WRITE`; for this approved local test, use `traffic_readings`. |
| `KAFKA_IT_ACL_DENIED_CLIENT_ID` | Valid OAuth client with the correct issuer and audience but without `WRITE` on `KAFKA_IT_TOPIC`. |
| `KAFKA_IT_ACL_DENIED_CLIENT_SECRET` | Secret for that ACL-denied test client. |
| `KAFKA_IT_INVALID_JWT_FILE` | Absolute path to a malformed or untrusted JWT file. |
| `KAFKA_IT_WRONG_ISSUER_JWT_FILE` | Absolute path to a validly signed token with only the issuer claim incorrect. |
| `KAFKA_IT_WRONG_AUDIENCE_JWT_FILE` | Absolute path to a validly signed token with only the audience incorrect. |

JWT files are bearer credentials and must be generated for the test environment,
stored outside the repository, and supplied by absolute path. Do not put
tokens, passwords, private keys, or certificates in Git, command-line
arguments, or PR comments. The wrong-issuer and wrong-audience tokens must
otherwise be validly signed by a key published by the broker-configured JWKS;
otherwise the test would only prove signature rejection. Use a test-only
signing key held by the test Auth Server, not a copy of the real Auth Server's
private key.

The integration test first requires a successful publish, so an unavailable
broker or incorrect authorized identity fails before the negative cases. It
then checks that a missing token, invalid JWT, wrong issuer, wrong audience,
and valid identity without topic `WRITE` each fail with the expected Kafka
authentication or authorization error. The missing-token case intentionally
fails the client's OAuth callback before a token can be sent; it verifies that
no Kafka operation succeeds without a token, not a particular broker log
message.

The ACL-denied test client must still have any cluster-level permission needed
to initialize the idempotent producer (`IDEMPOTENT_WRITE`), while having no
`WRITE` on the test topic. Otherwise a cluster-level denial could mask whether
the topic ACL is effective.

The profile does not start Docker Compose or Auth Server. It is safe to invoke
only after those external services, TLS trust material, test identities, topic,
and ACLs are ready. The current broker definition does not configure
`connections.max.reauth.ms`; therefore this profile does not claim to verify
forced reauthentication. If Héctor enables that setting, add a separate
expiry/reauthentication case using an Auth Server-issued short-lived token
before treating expiry behavior as verified.

No broker integration result has been reported. Passing unit tests do not prove
broker-side JWT validation, ACLs, or forced reauthentication.

## Latest execution status

Checked on 2026-11-12 at 17:47 local time:

- Docker is now available (`docker` 28.5.1, Compose 2.40.3). The earlier check
  at 17:26 failed because the `dockerDesktopLinuxEngine` named pipe was
  unavailable; the daemon was activated afterward.
- `docker ps -a` showed no Kafka container running or available locally.
- `mvn test`: `BUILD SUCCESS`, 46 tests, 0 failures, 0 errors, 0 skipped.
- `mvn package`: `BUILD SUCCESS`, 46 tests, 0 failures, 0 errors, 0 skipped.
- `mvn -pl shared/kafka-security -Pkafka-it -DskipTests test`: `BUILD SUCCESS`;
  the integration test source compiled, but tests were intentionally skipped.
- `mvn -pl shared/kafka-security -Pkafka-it test`: `BUILD SUCCESS`, 5 unit
  tests passed. This does not run the Failsafe integration test.
- The latest report on PR #14 says the broker exited because its test JWKS
  endpoint was deliberately set to `https://auth.example.invalid/oauth2/jwks`;
  the temporary Compose resources were then removed. That endpoint is not a
  usable Auth Server.
- No reachable broker address, Auth Server token/JWKS URLs, TLS trust material,
  test client credentials, or provisioned test ACLs are available in this
  workspace. No credentials, tokens, or certificates were requested or added.
- Consequently, no broker connection or token/issuer/audience/ACL/expiry
  rejection test was executed. There are no end-to-end pass results to report.

To unblock a reproducible run, Héctor needs to provide the non-secret runtime
parameters: broker host and TLS client port, certificate host/SAN and the
local truststore arrangement, Auth Server token and JWKS endpoints plus issuer,
approved audience, ACL principal mapping/status, token lifetime, and
`connections.max.reauth.ms`. Test credentials and certificate files must be
injected locally from a protected source, never posted in the PR or committed.
The exact service/client identities and issuance of the validly signed
wrong-issuer/wrong-audience test tokens must also be coordinated before the
profile can run.
