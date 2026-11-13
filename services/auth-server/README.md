# Authentication Server

This Spring Authorization Server module exposes the standard OAuth 2.0 token
endpoint at `/oauth2/token` and, when an RSA key pair is configured, the public
JWK Set at `/oauth2/jwks`. It supports the `client_credentials` grant and
`client_secret_basic` authentication only.

## External configuration

Configure these properties outside the repository:

| Property | Purpose |
|---|---|
| `smarttraffic.auth.issuer` | Stable issuer URL advertised in JWTs. Use the final HTTPS URL in a deployed environment. |
| `smarttraffic.auth.access-token-ttl` | Access-token lifetime; defaults to a provisional five minutes. |
| `smarttraffic.auth.key-id` | `kid` value used in signed tokens and JWKS. |
| `smarttraffic.auth.private-key-path` | Mounted PKCS#8 RSA private-key file. |
| `smarttraffic.auth.public-key-path` | Mounted X.509 RSA public-key file. |
| `smarttraffic.auth.clients.<client-id>.secret` | Unique client secret, injected from a secret manager or Docker secret. |
| `smarttraffic.auth.clients.<client-id>.scopes` | Scopes approved for that client. |
| `smarttraffic.auth.clients.<client-id>.audiences` | Audiences approved for tokens issued to that client. |

Clients with no configured scopes or audiences are rejected at startup. There
are no default client identities, secrets, scopes, or audiences in the
repository. Configure clients through an external Spring configuration file
and inject secret values at runtime. The issuer, audiences, scopes, token
lifetime, and key location remain deployment decisions; the defaults are not
team approvals.

The private key must never be committed or copied into a container image. For
production, mount it read-only from a secret store and serve the token and JWKS
endpoints over TLS. The service rejects RSA keys smaller than 2048 bits and
cryptographically verifies the public/private key pair during startup.

## Isolated Kafka reauthentication test instrumentation

The optional `smarttraffic.auth.test.audit-path`,
`smarttraffic.auth.test.rejection-marker-path`, and
`smarttraffic.auth.test.rejected-client-id` properties are only for an isolated
integration-test Auth Server. When enabled, the token endpoint writes a
sanitized audit record containing request sequence, timestamp, client ID,
HTTP status, and the issued token's `iat`/`exp` claims. It never writes the
JWT, response body, or client secret. Once the marker file exists, only the
configured test client receives HTTP 401; other clients, including the
interbroker identity, continue through normal authorization.

Use unique marker and audit paths outside Git for every run, shared between
the Auth Server and `kafka-it`, and preserve them for review. These settings
are unset by default and must not be enabled on a production Auth Server.

Spring Authorization Server validates registered clients and requested scopes.
The JWT `aud` claim is configured per client; this module does not yet select
separate Kafka and RabbitMQ audiences per token request. The team must agree
on that policy before broker integration.

## Tests

The integration tests generate an ephemeral RSA key pair and use test-only
client credentials. They exercise token issuance, client authentication,
scope rejection, RS256 verification, claim contents, and the public JWKS
response. No real credentials or signing keys are used.
