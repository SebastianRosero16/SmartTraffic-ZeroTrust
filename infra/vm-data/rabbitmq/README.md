# Isolated RabbitMQ OAuth 2 integration fixture

This directory is a test-only RabbitMQ 4.3.7 fixture. It does not modify the
Kafka test Compose project in the parent directory and does not define the
production vhost, exchanges, queues, routing keys, identities, or scopes.

The fixture enables only `rabbitmq_auth_backend_oauth2` for messaging
authentication and restricts SASL to `PLAIN`, the mechanism used to carry the
OAuth token. It requires an HTTPS JWKS URI and mounted test certificates,
allows only RS256, requires `exp`, enforces audience validation, verifies the
JWKS server certificate and hostname, and disables the plaintext AMQP listener.
It does not enable the management plugin or publish broker ports to the host.
Definitions contain test-only queues and exchanges and no password users.
The Compose service drops all Linux capabilities except `CHOWN`, `DAC_OVERRIDE`,
`SETGID`, and `SETUID`, which the image entrypoint and diagnostics need to
switch to the `rabbitmq` user and read the Erlang cookie. `no-new-privileges`
remains enabled.

The AMQPS listener does not require client certificates:
`ssl_options.verify = verify_none` and `fail_if_no_peer_cert = false` apply to
client-certificate authentication on that listener. They do not disable the
separate peer and hostname verification configured for outbound JWKS HTTPS.

## Required local files and settings

Create a local `.env` in this directory (do not commit it) with:

```dotenv
RABBITMQ_OAUTH_JWKS_URI=https://test-auth.example.invalid/oauth2/jwks
RABBITMQ_TEST_CERT_DIR=C:\path\outside\the\repository\to\test-certs
```

The certificate directory must contain:

- `oauth-ca.pem`: CA used to validate the HTTPS JWKS endpoint.
- `server.pem`: test AMQPS server certificate.
- `server-key.pem`: matching test AMQPS private key.

Do not use production private keys or credentials in this fixture. The example
JWKS hostname is intentionally invalid; replace it only with an authorized
isolated test Auth Server whose certificate matches its hostname. The fixture
refuses to start when the required settings or certificate directory are
missing.

## Run

With Docker Engine/Desktop running and the isolated test Auth Server available:

```powershell
docker compose --file compose.yaml config
docker compose --file compose.yaml up --detach
docker compose --file compose.yaml ps
docker compose --file compose.yaml logs rabbitmq
```

The broker has no published host ports. Test clients must join the
`rabbitmq-it` network and connect to `rabbitmq:5671` using AMQPS.

Stop and remove only this fixture's container and test data with:

```powershell
docker compose --file compose.yaml down --volumes
```

## Integration test evidence

The runnable integration suite and its isolated test credentials are documented
in [`rabbitmq-auth-test/README.md`](../rabbitmq-auth-test/README.md).

On 2026-11-14, the suite passed against the `rabbitmq:4.3.7` image with Docker
Engine. All nine cases passed using disposable keys, certificates, and client
credentials; the broker had no host-published ports:

- A valid RS256 publisher token could publish, and a scoped reader token could
  read its queue.
- Unknown signing keys, a signature from a different key, an expired token,
  and a wrong audience were rejected.
- A reader authenticated, but an unauthorized queue declaration was denied
  with AMQP reply code `403 ACCESS_REFUSED`.
- A connection without an OAuth token was rejected.
- On the 2026-11-15 rerun at commit `270efc1ccc27ccb9992ba65539faf70dc3902b24`,
  a token signed by the trusted test key with a different `iss` was accepted.
  The pinned RabbitMQ 4.3.7 OAuth2 backend verifies the JWT signature and
  configured algorithm, then selects the resource server from `aud`; its
  token-validation path does not compare `iss` with an expected issuer. The
  fixture does not configure issuer equality, and adding an issuer/discovery
  setting alone is not evidence that the AMQP backend enforces that claim.
  See the pinned
  [`rabbit_auth_backend_oauth2`](https://github.com/rabbitmq/rabbitmq-server/blob/v4.3.7/deps/rabbitmq_auth_backend_oauth2/src/rabbit_auth_backend_oauth2.erl)
  and
  [`uaa_jwt_jwt`](https://github.com/rabbitmq/rabbitmq-server/blob/v4.3.7/deps/rabbitmq_auth_backend_oauth2/src/uaa_jwt_jwt.erl)
  validation paths.

Issuer equality remains required by the project's intended trust model but is
not enforced by this stock fixture backend; it is pending a separately
implemented and broker-tested backend that rejects a trusted-key token with a
different issuer. The existing case is characterization of the observed
acceptance, not a negative rejection test. This is not full RFC 9068 validation.
Do not log or persist JWTs or secrets. These results validate only the
isolated test fixture, not a production deployment.

## Production decisions still pending

The production audience, vhost, resource names, bindings, service scopes, and
per-instance `query_client` identities remain undecided. The `*-it` values in
this directory are test-only and must not be copied into a deployment without
team approval.

The exact RabbitMQ patch release is pinned as requested. RabbitMQ's 4.3
documentation is no longer actively maintained; verify the selected image
artifact and its digest before any deployment.
