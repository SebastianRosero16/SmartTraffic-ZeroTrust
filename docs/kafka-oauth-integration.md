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
| `KAFKA_SSL_TRUSTSTORE_LOCATION` | No | External truststore path for Kafka broker TLS when the broker certificate is not trusted by JVM defaults. |
| `KAFKA_SSL_TRUSTSTORE_PASSWORD` | No | Kafka broker truststore password; only valid when its path is supplied. |
| `KAFKA_SSL_TRUSTSTORE_TYPE` | No | Kafka broker truststore type, for example `PKCS12`. |
| `AUTH_SSL_TRUSTSTORE_LOCATION` | No | External truststore path for HTTPS connections from the Strimzi OAuth callback to the Auth Server. |
| `AUTH_SSL_TRUSTSTORE_PASSWORD` | No | Auth Server HTTPS truststore password, when required by its format; only valid when its path is supplied. |
| `AUTH_SSL_TRUSTSTORE_TYPE` | No | Auth Server HTTPS truststore type, for example `PKCS12`, `JKS`, or `PEM`. |

These truststores protect two different TLS connections. `KAFKA_SSL_TRUSTSTORE_*`
is passed to Kafka's top-level `ssl.truststore.*` properties and validates the
Kafka broker certificate. `AUTH_SSL_TRUSTSTORE_*` is passed to Strimzi's OAuth
JAAS options as `oauth.ssl.truststore.*` and validates the HTTPS certificate
presented by the token endpoint. The Auth Server truststore is optional when
the JVM already trusts that certificate. For a private CA, mount its truststore
file into the client container/host and inject its path (and, if needed,
password/type) through the environment; do not commit the file or its password.
No certificate verification or hostname checking is disabled.
Supplying the Auth Server truststore password or type without its location is
rejected.

Each application authenticates to the HTTPS token endpoint using its own
registered client ID and secret; the simulator, processor, and archiver must
not share a client identity. The Auth Server currently assigns audiences per
registered client, so the applications do not send a separate audience request
parameter. Instead, configure each application client to receive tokens whose
`aud` is `smarttraffic-kafka`.

For the approved local-broker test, the inter-broker OAuth identity is exactly
`smarttraffic-kafka`: Héctor's broker configuration uses it as
`OAUTH_KAFKA_CLIENT_ID` for the external `KafkaClient` JAAS credentials. The
shared listener also uses `oauth.client.id=smarttraffic-kafka` as the expected
audience/resource identifier with audience checking enabled. Thus this value
serves both as the inter-broker client ID and the expected JWT `aud` for this
test; it does not replace or get reused as any application service's client
ID. The broker validates issuer, audience, and JWT signature using the Auth
Server JWKS endpoint. Token and JWKS URLs must use HTTPS.

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

The listener/audience arrangement is currently being tested by Héctor. These
ACLs are a request for him to coordinate and apply in the broker PR; this
change does not modify Docker, Compose, listener settings, or ACL state.

## Token expiry and reauthentication

An authenticated Kafka connection can outlive the JWT used at its initial
SASL exchange. The published broker configuration still has
`connections.max.reauth.ms=0`; this PR does not change it. The draft
integration test now has separate positive and fail-closed cases that use one
producer/connection each. They must be run only after Héctor confirms the
effective local listener value and the Auth Server's observed token TTL.
The agreed local trial uses a 300-second token and a candidate
`connections.max.reauth.ms=240000`; these are test-environment values, not a
production or PR #14 configuration change.

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
configuration table above. If the Auth Server uses a private CA, provide
`AUTH_SSL_TRUSTSTORE_LOCATION` (and its password/type where applicable) to
that client. This truststore is distinct from the optional
`KAFKA_SSL_TRUSTSTORE_*` settings used to validate the broker. The integration
profile additionally requires:

| Environment variable | Purpose |
|---|---|
| `KAFKA_IT_TOPIC` | Existing topic where the authorized producer has `WRITE`; for this approved local test, use `traffic_readings`. |
| `KAFKA_IT_ACL_DENIED_CLIENT_ID` | Valid OAuth client with the correct issuer and audience but without `WRITE` on `KAFKA_IT_TOPIC`. |
| `KAFKA_IT_ACL_DENIED_CLIENT_SECRET` | Secret for that ACL-denied test client. |
| `KAFKA_IT_INVALID_JWT_FILE` | Absolute path to a malformed or untrusted JWT file. |
| `KAFKA_IT_WRONG_ISSUER_JWT_FILE` | Absolute path to a validly signed token with only the issuer claim incorrect. |
| `KAFKA_IT_WRONG_AUDIENCE_JWT_FILE` | Absolute path to a validly signed token with only the audience incorrect. |
| `KAFKA_IT_REAUTH_INTERVAL_MS` | Effective positive listener reauthentication interval, confirmed from the running broker; use `240000` for the agreed local trial. |
| `KAFKA_IT_RUN_ID` | Unique path-safe identifier for this one test execution; use a new value for every run. |
| `KAFKA_IT_AUDIT_DIR` | Absolute local audit root outside the Git repository. The test stores evidence under a run-ID subdirectory. |
| `KAFKA_IT_REAUTH_MARKER_FILE` | Unique path under the run-ID directory, shared with the Auth Server; the test creates it only after the control publish. |
| `KAFKA_IT_TOKEN_AUDIT_FILE` | Unique path under the run-ID directory, shared with the Auth Server for sanitized HTTP status and token `iat`/`exp` metadata. |
| `KAFKA_IT_ADMIN_CLIENT_ID` | Administrative OAuth client used only to verify topic contents. |
| `KAFKA_IT_ADMIN_CLIENT_SECRET` | Secret for the administrative verification client, injected locally. |

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
ACLs, and unique audit paths are ready. The Auth Server's test-only audit
properties must point to the same token-audit and marker files supplied to the
test:
`smarttraffic.auth.test.audit-path`,
`smarttraffic.auth.test.rejection-marker-path`, and
`smarttraffic.auth.test.rejected-client-id`. Use these only in an isolated
test Auth Server and configure the rejected client as the simulator identity.
Keep the production token TTL unchanged; provide the agreed 300-second TTL
only through the local Auth Server test configuration.

Héctor reported a successful run of the existing OAuth/ACL integration at
SHA `3c13bc330d0125fe67702c9f1fcb1d89467dc657`, using separate broker and Auth
Server HTTPS truststores. That run covered the allowed publish, authentication
rejections, and ACL denial, but did not exercise token-expiry
reauthentication. The newly added reauthentication cases still require one
separate run against the exact PR head; unit tests do not prove their broker
behavior.

The reauthentication cases use the real Strimzi OAuth callback and keep the
same producer open. The positive case waits for both HTTP 200 on renewal and an
increase in the producer's `successful-reauthentication-total`, then confirms
a later send and reads its unique value with the administrative consumer. The
negative case creates the rejection marker only after the initial 200 control
token and publish. It waits for HTTP 401 for renewal and an increase in
`failed-reauthentication-total`, then requires the later send to fail and the
unique value to be absent from the topic. Kafka reconnection attempts remain
enabled; all observed simulator-client token requests after the marker must
return 401, while interbroker and administrative identities are not rejected.

The test records token `iat` and `exp` from the locally issued JWT without
writing the JWT or response body. It derives the signal deadline from the
observed expiry and configured effective reauthentication interval, caps each
case at six minutes, and allows observation through `exp` plus 60 seconds.
Polling uses `System.nanoTime()` and bounded intervals, not a fixed sleep. It
logs the scheduled reauthentication time, effective deadline, and monotonic
elapsed time. Failsafe kills a stuck test process after 900 seconds.

Each token audit entry has a UUID request ID and records `rejectionArmed` and
`rejectionApplied` as observed when that request enters the test filter. The
test snapshots request IDs before each control publish or marker activation
and correlates only previously unseen IDs; it does not depend on a process-local
counter, so an Auth Server restart cannot make a new event look old. A request
that began before the marker but completed afterward remains distinguishable
from one processed with rejection armed.

Set `KAFKA_IT_RUN_ID` to a new identifier and retain the complete run
directory, including the Auth Server token audit, rejection marker, and
case-specific TSV audits, as well as the unique Failsafe XML and redirected
test log under the operating-system temporary directory, until Héctor reviews
them. The Auth Server audit includes only request ID, timestamp, client ID,
status, rejection-marker state, and token `iat`/`exp`; it never includes a JWT,
response body, or secret. The test refuses to delete or overwrite prior audit
files or markers. No reauthentication result is claimed until Héctor runs this
exact PR head once and reviews the preserved evidence.

Local validation of this test update:

- `mvn -pl services/auth-server clean test`: 12 tests passed, including
  focused checks for unique request IDs and marker state in sanitized audits.
- `mvn -pl shared/kafka-security clean test`: 11 unit tests passed, including
  audit-ID correlation and expiry/deadline bounds.
- `mvn -pl shared/kafka-security -Pkafka-it -DskipTests test`: `BUILD
  SUCCESS`; tests were skipped, so no broker integration ran. The preceding
  unit-test build compiled the integration test sources.
- Héctor's next agreed broker run remains pending. No success or rejection
  result is claimed for token-expiry reauthentication.

## JWT access-token type investigation

The reported sanitized error was `Token validation failed: Token type not
set`. Strimzi Kafka OAuth 0.18.1's JWT validator requires the exact value
`Bearer` in a JWT payload claim named `typ` or `token_type`; it rejects the
token if both claims are absent. It does not interpret the OAuth token
response's separate top-level `token_type` field as a JWT claim.

The Auth Server currently returns `token_type: Bearer` in the OAuth response,
but its access-token customizer previously added only audience and `jti`
(besides standard claims). The existing test asserted the response metadata
and did not assert a token-type claim. The error indicates that Strimzi found
neither payload claim in the presented JWT. No JWT was shared or decoded in
this investigation.

Correction in this branch: the Auth Server adds the payload claim
`token_type: Bearer` to access tokens while preserving signature, issuer,
audience, and Strimzi's type validation. Its integration test now asserts that
claim inside the signed JWT separately from the OAuth response's
`token_type`. No broker-side validation is disabled or relaxed. The correction
still requires Héctor to rebuild/restart the Auth Server and rerun `kafka-it`;
it is not yet demonstrated against the broker.

## Latest execution status

Checked on 2026-11-12 at 19:50 local time:

- Docker is now available (`docker` 28.5.1, Compose 2.40.3). The earlier check
  at 17:26 failed because the `dockerDesktopLinuxEngine` named pipe was
  unavailable; the daemon was activated afterward.
- `docker ps` on Steven's machine shows no Kafka container (only an unrelated
  MongoDB container). Docker is available here, but the broker and Auth
  Server/JWKS runtime configuration are on Héctor's environment.
- `mvn test`: `BUILD SUCCESS`, 46 tests, 0 failures, 0 errors, 0 skipped.
- `mvn package`: `BUILD SUCCESS`, 46 tests, 0 failures, 0 errors, 0 skipped.
- `mvn -pl services/auth-server test`: `BUILD SUCCESS`, 8 tests, 0 failures,
  0 errors, 0 skipped, including the JWT `token_type` claim assertion.
- `mvn -pl shared/kafka-security -Pkafka-it -DskipTests test`: `BUILD SUCCESS`;
  this compiled the profile configuration but intentionally skipped tests.
- `mvn -pl shared/kafka-security -Pkafka-it test`: `BUILD SUCCESS`, 5 unit
  tests passed. This does not run the Failsafe integration test.
- After adding the separate Auth Server HTTPS truststore settings,
  `mvn -pl shared/kafka-security test`: `BUILD SUCCESS`, 6 tests passed,
  including the truststore-separation and incomplete-configuration checks.
- After that change, `mvn -pl shared/kafka-security -Pkafka-it -DskipTests
  test`: `BUILD SUCCESS`; the profile configuration and integration test
  sources compiled, but tests were skipped.
- Héctor's initial broker attempt exited because its test JWKS endpoint was
  deliberately set to `https://auth.example.invalid/oauth2/jwks`; the
  temporary Compose resources were then removed. That endpoint was not usable.
- Héctor later reported a temporary run where the Auth Server/JWKS responded
  successfully and issued a token reported to contain `iss`, `aud`, and `sub`.
  The broker reached KRaft startup but failed during inter-broker/listener
  authentication with `No principal name in JWT claim: sub`. Temporary
  resources and secrets were cleaned up. This is a reproduced failure, not a
  successful authentication test.
- After commit `ea55248` in PR #14, Héctor reported a successful local broker
  startup: Compose valid, KRaft healthy, JWKS loaded, inter-broker OAuth login
  using the broker client principal, and private controller TLS/mTLS. This
  report does not include execution of this module's `kafka-it` profile.
- Héctor reported that `kafka-it` then failed on the first publish with
  `Token validation failed: Token type not set`. Strimzi requires JWT payload
  `typ` or `token_type` equal to `Bearer`; the Auth Server correction and
  claim assertion described above have been added but still need testing.
- Héctor is testing a single listener with
  `smarttraffic-kafka` as both the inter-broker OAuth client ID and the
  listener's expected audience/resource ID. Application clients retain their
  individual OAuth client IDs and must be configured in Auth Server to receive
  `aud=smarttraffic-kafka`. The result of Héctor's test is pending; this is
  not a validated integration.
- Héctor reported `connections.max.reauth.ms=0`. Forced reauthentication on
  token expiry is disabled and remains explicitly pending; no expiry
  reauthentication test has been run.
- This branch now accepts `AUTH_SSL_TRUSTSTORE_LOCATION`,
  `AUTH_SSL_TRUSTSTORE_PASSWORD`, and `AUTH_SSL_TRUSTSTORE_TYPE`, mapping them
  to Strimzi's `oauth.ssl.truststore.*` JAAS options. A focused unit test
  verifies that these remain separate from Kafka's `ssl.truststore.*`
  properties and that Auth Server truststore credentials are not placed in
  the broker truststore settings.
- Héctor's reported 5-unit/1-integration-test pass used an external OAuth
  HTTPS truststore on his local setup. It has not been rerun against this
  branch's new configuration from Steven's workspace.
- No reachable broker address, Auth Server token/JWKS URLs, TLS trust material,
  test client credentials, or provisioned test ACLs are available in this
  workspace. No credentials, tokens, or certificates were requested or added.
- Consequently, Steven cannot independently rerun the profile from this
  machine. The current commit's authorized-client and rejection results still
  require a run in Héctor's broker/Auth Server environment.

To unblock a reproducible run, Héctor needs to provide the non-secret runtime
parameters: broker host and TLS client port, certificate host/SAN and the
local truststore arrangement, Auth Server token and JWKS endpoints plus issuer,
approved audience, ACL principal mapping/status, token lifetime, and
`connections.max.reauth.ms`. Test credentials and certificate files must be
injected locally from a protected source, never posted in the PR or committed.
The host-side run must use the local-access Compose override, the approved
`traffic_readings` topic and audience, and the authorized/ACL-denied test
clients. The validly signed wrong-issuer/wrong-audience test tokens must be
prepared for that test environment before the profile can run.
