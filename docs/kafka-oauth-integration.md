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

The broker integration suite is still pending. Once the broker, TLS trust
material, Auth Server, test clients, and ACLs are available, it must demonstrate:

- an authorized simulator publish and authorized archiver/Streams consume;
- rejection when SASL credentials/token are absent or invalid;
- rejection of an expired token at forced reauthentication;
- rejection of a token with a wrong issuer or audience;
- rejection of unauthorized topic/group operations and topic creation;
- the exact Kafka Streams ACL set required by the agreed topology.

Do not treat passing unit tests as evidence that broker-side JWT validation,
ACLs, or forced reauthentication work.

## Latest execution status

Checked on 2026-11-12 before attempting the integration run:

- The local Docker CLI is installed, but `docker version` cannot connect to
  the `desktop-linux` engine because the `dockerDesktopLinuxEngine` named pipe
  is unavailable. The broker could not be started from this workstation.
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
The exact service/client identities and which negative-test identities can be
issued also need confirmation before the integration test cases can be fixed.
