# Kafka OAuth test broker

This Compose project is a local, single-node test environment for Kafka
4.4.0 with the Strimzi Kafka OAuth broker plugin 0.18.1. It is not the final
`vm-data` deployment and deliberately excludes ZooKeeper, RabbitMQ, PostgreSQL,
the Auth Server, and application services.

The broker uses KRaft combined broker/controller mode. The Kafka base image is
pinned to the multi-platform manifest digest
`sha256:833b867a9585a141da455dc2b57431c65cbccb27f11c91f694444cca9f777e0f`.
The Strimzi plugin version is pinned in `kafka-oauth/pom.xml`. The Maven build
uses strict repository checksum validation (`-C`), records the resolved runtime
dependency tree and checksums in the image, and verifies that the OAuth server
client, common JARs, and pinned Nimbus JOSE JWT dependency are present
before assembling the image. Only the artifacts required by broker OAuth are
copied to Kafka's library directory; Kafka's bundled Jackson libraries are
reused, as recommended by Strimzi. Optional JSONPath-based claim features are
not enabled or packaged in this cut.

## Requirements

- Docker Engine/Desktop with the Docker Compose v2 plugin.
- A local `.env` copied from `.env.example` and filled with approved test
  values. Compose intentionally refuses to start while required values are
  blank.
- TLS keystore/truststore files placed outside Git under the directory selected
  by `TLS_SECRETS_DIR`, with their passwords supplied only through the local
  environment. Do not commit credentials or certificates.
- An external `KafkaClient` JAAS file, selected by `KAFKA_CLIENT_JAAS_FILE`,
  containing the inter-broker client's credentials when that file is used by
  the local runtime. It must not be committed.
- An Auth Server reachable at `OAUTH_JWKS_ENDPOINT`, with the configured
  issuer matching `OAUTH_ISSUER_URI`, and an approved Kafka audience in
  `OAUTH_KAFKA_AUDIENCE`. For this single-listener test, the approved
  `smarttraffic-kafka` value is also the listener OAuth client ID,
  inter-broker client ID, and audience; application clients must continue
  using their own IDs.

The Compose file does not publish ports by default. Its client listener uses
`SASL_SSL` with `OAUTHBEARER`; the KRaft controller listener uses `SSL`.
The controller listener binds only to container loopback and requires mutual
TLS. The broker
authorizer denies operations without a matching ACL. Listener and
controller ports, the JWKS endpoint, issuer, broker client ID, Kafka audience,
TLS file names/types, passwords, OAuth client secret/token endpoint, external
JAAS file, and superuser principal must be supplied locally, including the TLS
client-auth mode. No credentials,
certificates, ports, or JWKS host are committed as defaults.

The broker sets `connections.max.reauth.ms` to `240000` (4 minutes). Validate
OAuth reauthentication against the Auth Server's 300-second token lifetime,
including successful and rejected renewals, with separate client and broker
truststores before deployment.

The named `vm-data-private` bridge network is not exposed to the host. It is
not marked `internal: true`, because the broker must be able to initiate an
outbound connection to the separately deployed Auth Server for JWKS retrieval.
In AWS, restrict that route with the VM Security Groups and `nftables`; Compose
does not replace those controls.

## Build and start

From this directory:

```powershell
Copy-Item .env.example .env
docker build --tag smarttraffic/kafka-oauth:4.4.0-oauth-0.18.1 `
  --file Dockerfile .
```

Generate a KRaft cluster ID from the built image and put the output in
`KAFKA_CLUSTER_ID` in `.env`:

```powershell
docker run --rm --entrypoint /opt/kafka/bin/kafka-storage.sh `
  smarttraffic/kafka-oauth:4.4.0-oauth-0.18.1 random-uuid
```

Fill the remaining required values in `.env`, provide the external JAAS and
TLS files, and ensure the Auth Server is reachable. Steven still needs to
provide/approve the OAuth client settings, token claims, ACL principals, and
Auth Server endpoint/certificate details before the full OAuth flow can be
tested. Do not substitute permissive listeners to bypass these requirements.

Start the broker without publishing a host port:

```powershell
docker compose up -d kafka
docker compose ps
docker compose logs -f kafka
```

If local host access is explicitly needed, use the optional override. It binds
only to loopback and advertises that same address to Kafka clients. The TLS
certificate must include the corresponding host name/IP in its SAN:

```powershell
docker compose -f compose.yaml -f compose.host-access.yaml up -d kafka
```

The health check confirms that the configured client TCP listener accepts a
connection; it does not authenticate or prove that JWKS validation or ACLs
work. Those checks require the approved Auth Server and OAuth client
configuration.

Kafka data persists in the `kafka-data` named volume. `docker compose down`
preserves it. Removing it with `docker compose down -v` deletes the local
broker data.

## Dependency inspection

The image includes:

- `/opt/kafka/strimzi-oauth/dependency-tree.txt` — exact Maven runtime
  dependency versions resolved for the pinned plugin.
- `/opt/kafka/strimzi-oauth/SHA256SUMS` — SHA-256 manifest for the copied
  plugin/runtime JARs.

The build fails on repository checksum mismatches or if any pinned OAuth
server/client/common JAR or Nimbus dependency is missing. To inspect the
resolved dependency tree in a built image:

```powershell
docker run --rm --entrypoint cat `
  smarttraffic/kafka-oauth:4.4.0-oauth-0.18.1 `
  /opt/kafka/strimzi-oauth/dependency-tree.txt
```

Verify the copied JARs against the image's manifest:

```powershell
docker run --rm --workdir /opt/kafka/libs `
  --entrypoint sha256sum `
  smarttraffic/kafka-oauth:4.4.0-oauth-0.18.1 `
  --check /opt/kafka/strimzi-oauth/SHA256SUMS
```

## Validation evidence

The local validation completed on 2026-11-12 with Docker Desktop and an
external HTTPS Auth Server/JWKS:

- `docker compose config` completed successfully.
- Kafka reached `healthy` in KRaft mode.
- The broker loaded the JWKS keys successfully.
- The inter-broker OAuth login obtained a token for the configured broker
  client principal.
- The controller listener used TLS with mutual authentication and remained
  private; no host ports were published.

This evidence does not cover client authentication or ACL behavior. The
`kafka-it` profile must still be run with Steven's approved client matrix.
The result is not considered end-to-end OAuth validation until that test
covers the allowed and rejected client cases.
