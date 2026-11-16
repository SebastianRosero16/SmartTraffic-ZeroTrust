# RabbitMQ Auth Server test assets

`prepare-test-assets.ps1` prepares local-only RSA signing keys and TLS
certificates for the existing Auth Server and the isolated RabbitMQ fixture.
It writes them outside the repository, refuses to overwrite a non-empty
directory, uses seven-day test certificates, and deletes the temporary CA
private key after issuing the two server certificates. Never use these assets
for deployment.

Run from the repository root in PowerShell:

```powershell
.\infra\vm-data\rabbitmq-auth-test\prepare-test-assets.ps1
```

By default, assets are written to
`%TEMP%\smarttraffic-rabbitmq-it`. A different empty directory outside the
repository can be supplied with `-OutputDirectory`.

In one PowerShell window, start the Auth Server:

```powershell
$testAssets = Join-Path $env:TEMP 'smarttraffic-rabbitmq-it'
$authConfig = (Join-Path $testAssets 'application-test.yml').Replace('\', '/')
mvn -pl services/auth-server spring-boot:run "-Dspring-boot.run.arguments=--spring.config.additional-location=file:$authConfig"
```

Use the actual output path printed by the script. In a second PowerShell
window, provide the same output directory to the RabbitMQ Compose fixture:

```powershell
$env:RABBITMQ_TEST_CERT_DIR = 'C:\Users\<you>\AppData\Local\Temp\smarttraffic-rabbitmq-it'
$env:RABBITMQ_OAUTH_JWKS_URI = 'https://host.docker.internal:8443/oauth2/jwks'
docker compose --file infra\vm-data\rabbitmq\compose.yaml config
```

After Steven's review and once Docker Engine is running, the fixture can be
started with `docker compose --project-name smarttraffic-pr26-it --file infra\vm-data\rabbitmq\compose.yaml up`.
The isolated AMQP integration suite is in this directory and does not modify
the root Maven reactor. Running `mvn test` here without
`RABBITMQ_IT=true` skips the broker tests.

With the Auth Server and broker running, run the suite in a disposable Maven
container attached to the Compose network. From the repository root:

```powershell
$testAssets = Join-Path $env:TEMP 'smarttraffic-rabbitmq-it'
$repository = (Get-Location).Path
docker run --rm `
  --network smarttraffic-rabbitmq-it_rabbitmq-it `
  --add-host host.docker.internal:host-gateway `
  --env-file (Join-Path $testAssets 'test-clients.env') `
  -e RABBITMQ_IT=true `
  -e RABBITMQ_IT_JWT_PRIVATE_KEY=/test-assets/jwt-private.pem `
  -e RABBITMQ_IT_CA_CERT=/test-assets/oauth-ca.pem `
  -v "${repository}:/workspace" `
  -v "${testAssets}:/test-assets:ro" `
  -w /workspace/infra/vm-data/rabbitmq-auth-test `
  maven:3.9.9-eclipse-temurin-17 mvn test
```

The suite requests tokens directly from the test Auth Server and never prints
or saves them. It checks valid publisher/reader operations, unknown signing
keys, an invalid signature, expiration, audience, insufficient resource
permissions, a missing token, and whether a validly signed token with a
different `iss` can authenticate. The `iss` result records the behavior of the
selected broker and does not claim that RabbitMQ validates issuer equality.

The generated Auth Server configuration has separate test-only publisher,
dashboard reader, alert dispatcher, and query client identities. Their random
credentials are stored only in generated files outside the repository
(`application-test.yml` and `test-clients.env`). Do not copy them into
production or share the generated private keys.

The query-client's broker-backed round-trip test is in the root Maven reactor.
With the Auth Server and fixture running, execute it from a disposable Maven
container on the same Compose network:

```powershell
$testAssets = Join-Path $env:TEMP 'smarttraffic-rabbitmq-it'
$repository = (Get-Location).Path
docker run --rm `
  --network smarttraffic-pr26-it_rabbitmq-it `
  --add-host host.docker.internal:host-gateway `
  --env-file (Join-Path $testAssets 'test-clients.env') `
  -e RABBITMQ_IT=true `
  -e RABBITMQ_IT_JWT_PRIVATE_KEY=/test-assets/jwt-private.pem `
  -e RABBITMQ_IT_CA_CERT=/test-assets/oauth-ca.pem `
  -e RABBITMQ_IT_AUTH_BASE_URL=https://host.docker.internal:8443 `
  -e RABBITMQ_IT_HOST=rabbitmq `
  -e RABBITMQ_IT_PORT=5671 `
  -v "${repository}:/workspace" `
  -v "${testAssets}:/test-assets:ro" `
  -w /workspace `
  maven:3.9.9-eclipse-temurin-17 mvn -B -pl services/query-client -am test
```

The round-trip creates an exclusive response queue, confirms it cannot be
declared by another connection while owned, publishes a random traffic update,
checks the alert observer, and verifies a populated response with matching
correlation. Closing the query connection must auto-delete the queue. It uses
only the generated test identities and strict CA/hostname validation.

The test query-client identity has `write` on the request queue via
`amq.default`, `configure`/`write`/`read` on its response queue, and `write` on
the response exchange/routing key needed for binding plus `read` on that
exchange. These scopes and all
resources are confined to the disposable `smarttraffic-it` vhost and must not
be copied to production identities.
