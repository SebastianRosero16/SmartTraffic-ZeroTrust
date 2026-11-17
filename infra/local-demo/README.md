# SmartTraffic local demo

This is a disposable, single-host demo profile based on the code at PR #26
commit `6e1d3a9`. It reuses the Kafka and RabbitMQ test fixtures without
editing them. The Auth Server, brokers, application services, and PostgreSQL
run locally; no AWS resources or production ports, identities, or ACLs are
used.

The demo-only values are intentionally explicit: Kafka audience
`smarttraffic-kafka`, topic `traffic_readings`, RabbitMQ's `*-it` topology,
`sensor-1`/`zone-1`, a 0–20 vehicle range, a 1-second generation interval, and
10-second density windows with thresholds 5 and 10. Kafka's application ID is
`traffic-density-demo`. These values are for this local run only, not project
defaults or production decisions.
The local broker superuser list includes the OAuth inter-broker principal and
the KRaft controller's test-certificate principal (`User:CN=kafka`); neither
identity is a production authorization policy.

## Prerequisites

- Docker Desktop with Docker Compose v2.
- Git for Windows (for OpenSSL), and a JDK with `keytool`.
- Network access to pull the pinned Kafka image and the Maven/PostgreSQL images.

The asset generator invokes the pinned Kafka base image once to create a
KRaft cluster ID. It delegates the existing RabbitMQ test asset generation to
`infra/vm-data/rabbitmq-auth-test/prepare-test-assets.ps1`, adds separate
throwaway Kafka TLS material and test identities, and writes everything under
`%TEMP%\smarttraffic-local-demo` by default. It refuses to generate assets
inside the repository or overwrite a non-empty output directory. Secrets and
private keys are not written to Git or printed.

From the repository root in PowerShell:

```powershell
.\infra\local-demo\prepare-assets.ps1
$assets = Join-Path $env:TEMP 'smarttraffic-local-demo'
$env:LOCAL_DEMO_ASSETS = $assets.Replace('\', '/')
docker compose --project-name smarttraffic-local-demo `
  --env-file "$assets\demo.env" `
  --file infra\local-demo\compose.yaml config --quiet
docker compose --project-name smarttraffic-local-demo `
  --env-file "$assets\demo.env" `
  --file infra\local-demo\compose.yaml up --build -d
```

The dashboard binds only to `127.0.0.1:18080`; open
`http://localhost:18080/dashboard.html`. Kafka and RabbitMQ listeners are
private to the Compose networks and are not published to the host. The Auth
Server is published only on `127.0.0.1:18443` so the test clients can use the
fixture certificate for `host.docker.internal`.

The simulator publishes random readings. The density processor aggregates
them, publishes updates to RabbitMQ, and the dashboard displays them. The
archiver writes the input readings to PostgreSQL. The alert dispatcher and
single `client-it` query client use only the RabbitMQ queues and exchanges
already defined by the test fixture.

## Validation and cleanup

```powershell
docker compose --project-name smarttraffic-local-demo `
  --env-file "$assets\demo.env" `
  --file infra\local-demo\compose.yaml ps
docker compose --project-name smarttraffic-local-demo `
  --env-file "$assets\demo.env" `
  --file infra\local-demo\compose.yaml logs --tail 100
docker compose --project-name smarttraffic-local-demo `
  --env-file "$assets\demo.env" `
  --file infra\local-demo\compose.yaml down
```

After `down` completes, remove the generated credentials, certificates, and
other temporary test assets. For the default output directory shown above:

```powershell
$expectedAssets = Join-Path $env:TEMP 'smarttraffic-local-demo'
if ([IO.Path]::GetFullPath($assets) -ne [IO.Path]::GetFullPath($expectedAssets)) {
  throw "Refusing to remove unexpected demo assets path: $assets"
}
Remove-Item -LiteralPath $assets -Recurse -Force
```

`down` preserves the named local data volumes. Add `-v` only when intentionally
discarding the demo's Kafka, RabbitMQ, PostgreSQL, and Maven cache data. The
existing `smarttraffic-kafka-it` container is a separate project and must not
be used or modified by this demo.

Application services use the Maven development image with the source tree
mounted into the containers. This avoids depending on the unmerged
application Dockerfile draft; it is a local development/demo setup, not a
production image or multi-VM deployment.
