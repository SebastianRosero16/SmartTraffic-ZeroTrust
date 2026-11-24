# Authentication Server

This Spring Authorization Server module exposes the standard OAuth 2.0 token
endpoint at `/oauth2/token` and, when an RSA key pair is configured, the public
JWK Set at `/oauth2/jwks`. Service identities continue to use the
`client_credentials` grant with `client_secret_basic`. A separate configured
dashboard client uses the Authorization Code grant with PKCE and OpenID
Connect. Dashboard users authenticate through the server's form login.
The login page is a responsive SmartTraffic-branded Thymeleaf view, styled to
match the dashboard palette and retaining Spring Security's CSRF protection.

## External configuration

Configure these properties outside the repository:

| Property | Purpose |
|---|---|
| `AUTH_DB_URL` | PostgreSQL JDBC URL for the dashboard authentication schema. |
| `AUTH_DB_USER` / `AUTH_DB_PASSWORD` | Runtime database identity; grant only schema usage plus `SELECT` and `INSERT` on the dashboard user table. |
| `AUTH_DB_MIGRATOR_USER` / `AUTH_DB_MIGRATOR_PASSWORD` | Separate identity used by Flyway to apply schema migrations. |
| `smarttraffic.auth.dashboard-client.client-id` | OAuth client ID for the dashboard BFF. |
| `smarttraffic.auth.dashboard-client.secret` | OAuth client secret for the dashboard BFF; inject externally. |
| `smarttraffic.auth.dashboard-client.redirect-uris` | Exact callback URLs allowed for the dashboard BFF. |
| `smarttraffic.auth.dashboard-client.audiences` | Audience assigned to dashboard access tokens. |
| `smarttraffic.auth.dashboard-user.username` | Username of the single pre-provisioned viewer account; defaults to `dashboard-viewer`. |
| `smarttraffic.auth.dashboard-user.password-file` | Read-only file containing the initial password, used only if the database has no dashboard user. |
| `AUTH_SESSION_COOKIE_SECURE` | Set to `true` for TLS deployments; set to `false` only for local HTTP development. |
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

The dashboard OAuth client must be configured separately from service clients.
For local development its redirect URI is
`http://localhost:8080/login/oauth2/code/dashboard`; production must use the
final HTTPS dashboard URL. The client is required to use PKCE and does not
require a separate consent screen.

Flyway applies `V1__create_dashboard_user.sql` in the dedicated
`dashboard_auth` schema. Provision that schema and the two database roles
before startup: the migrator owns the schema, while the runtime role receives
only `USAGE` on the schema and `SELECT`/`INSERT` on the dashboard-user table.
The production JDBC URL must use `sslmode=verify-full` and a mounted CA file;
PostgreSQL must reject non-TLS network connections. The
`vm-data/postgres-init/20-dashboard-auth-roles.sh` helper provisions these
roles only during initialization of a new PostgreSQL data directory.
The application bootstraps the configured account from the mounted password
file only when the table is empty; it stores a BCrypt hash and never overwrites
an existing account. There is no public registration or password-reset
endpoint. Keep the password file outside the repository and readable only by
the Auth Server process.

The private key must never be committed or copied into a container image. For
production, mount it read-only from a secret store and serve the token and JWKS
endpoints, authorization endpoint, and form login over TLS. The service rejects
RSA keys smaller than 2048 bits and cryptographically verifies the
public/private key pair during startup.

## Isolated Kafka reauthentication test instrumentation

The optional `smarttraffic.auth.test.audit-path`,
`smarttraffic.auth.test.rejection-marker-path`, and
`smarttraffic.auth.test.rejected-client-id` properties are only for an isolated
integration-test Auth Server. When enabled, the token endpoint writes a
sanitized audit record containing a UUID request ID, timestamp, client ID,
HTTP status, whether rejection was armed when the request arrived, whether the
marker was applied to that client, and the issued token's `iat`/`exp` claims.
It never writes the JWT, response body, or client secret. Once the marker file
exists, only the configured test client receives HTTP 401; other clients,
including the interbroker identity, continue through normal authorization.

Use unique marker and audit paths outside Git for every run, shared between
the Auth Server and `kafka-it`, and preserve them for review. These settings
are unset by default and must not be enabled on a production Auth Server.

Spring Authorization Server validates registered clients and requested scopes.
The JWT `aud` claim is configured per client; this module does not yet select
separate Kafka and RabbitMQ audiences per token request. The team must agree
on that policy before broker integration.

## Tests

The integration tests use an in-memory H2 database and generate an ephemeral
RSA key pair. They cover client-credentials token issuance, the dashboard
account bootstrap and password hash, invalid human credentials, authorization
code with PKCE, RS256 claims, and the public JWKS response. No real credentials
or signing keys are used.
