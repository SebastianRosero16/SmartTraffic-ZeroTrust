[CmdletBinding()]
param(
    [string]$OutputDirectory = (Join-Path $env:TEMP 'smarttraffic-local-demo')
)

$ErrorActionPreference = 'Stop'

$repositoryRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..')).TrimEnd('\')
$outputPath = [System.IO.Path]::GetFullPath($OutputDirectory).TrimEnd('\')
$repositoryPrefix = $repositoryRoot + '\'
if ($outputPath.Equals($repositoryRoot, [System.StringComparison]::OrdinalIgnoreCase) -or
        $outputPath.StartsWith($repositoryPrefix, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw 'Demo credentials, keys, and certificates must be generated outside the repository.'
}
if (Test-Path -LiteralPath $outputPath) {
    if (-not (Get-Item -LiteralPath $outputPath).PSIsContainer) {
        throw "The output path is not a directory: $outputPath"
    }
    if (Get-ChildItem -LiteralPath $outputPath -Force | Select-Object -First 1) {
        throw "The output directory must be empty: $outputPath"
    }
}

$opensslCommand = Get-Command openssl.exe -ErrorAction SilentlyContinue
$opensslPath = if ($opensslCommand) { $opensslCommand.Source } else { 'C:\Program Files\Git\usr\bin\openssl.exe' }
$keytoolCommand = Get-Command keytool.exe -ErrorAction SilentlyContinue
if (-not (Test-Path -LiteralPath $opensslPath)) {
    throw 'OpenSSL was not found. Install OpenSSL or Git for Windows.'
}
if (-not $keytoolCommand) {
    throw 'keytool was not found. Install a JDK.'
}

$clusterImage = 'apache/kafka:4.4.0@sha256:833b867a9585a141da455dc2b57431c65cbccb27f11c91f694444cca9f777e0f'
$clusterId = (& docker run --rm --entrypoint /opt/kafka/bin/kafka-storage.sh $clusterImage random-uuid)
if ($LASTEXITCODE -ne 0) {
    throw 'Could not generate the local Kafka cluster ID using the pinned Kafka image.'
}
$clusterId = ($clusterId | Select-Object -Last 1).Trim()
if ($clusterId -notmatch '^[A-Za-z0-9_-]{22}$') {
    throw 'The Kafka image returned an unexpected cluster ID.'
}

$rabbitAssetsScript = Join-Path $repositoryRoot 'infra\vm-data\rabbitmq-auth-test\prepare-test-assets.ps1'
& $rabbitAssetsScript -OutputDirectory $outputPath
if ($LASTEXITCODE -ne 0) {
    throw 'The RabbitMQ test asset generator failed.'
}

function Invoke-OpenSsl {
    param([Parameter(Mandatory = $true)][string[]]$Arguments)

    & $script:opensslPath @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "OpenSSL failed: openssl $($Arguments -join ' ')"
    }
}

function Invoke-Keytool {
    param([Parameter(Mandatory = $true)][string[]]$Arguments)

    & $script:keytoolCommand.Source @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw 'keytool failed while preparing local demo truststores.'
    }
}

$kafkaTlsPath = Join-Path $outputPath 'kafka-tls'
New-Item -ItemType Directory -Path $kafkaTlsPath | Out-Null

$kafkaCaKey = Join-Path $kafkaTlsPath 'kafka-ca-key.pem'
$kafkaCaCert = Join-Path $kafkaTlsPath 'kafka-ca.pem'
$brokerKey = Join-Path $kafkaTlsPath 'broker-key.pem'
$brokerCsr = Join-Path $kafkaTlsPath 'broker.csr'
$brokerCert = Join-Path $kafkaTlsPath 'broker.pem'
$brokerExtensions = Join-Path $kafkaTlsPath 'broker-extensions.cnf'
$brokerKeystore = Join-Path $kafkaTlsPath 'broker.keystore.p12'
$brokerTruststore = Join-Path $kafkaTlsPath 'broker.truststore.p12'
$authTruststore = Join-Path $kafkaTlsPath 'auth.truststore.p12'
$authTruststoreSource = Join-Path $outputPath 'oauth-ca.pem'
$brokerKeystorePassword = [Guid]::NewGuid().ToString('N')
$brokerTruststorePassword = [Guid]::NewGuid().ToString('N')
$authTruststorePassword = [Guid]::NewGuid().ToString('N')

Invoke-OpenSsl -Arguments @(
    'req', '-x509', '-newkey', 'rsa:2048', '-nodes', '-sha256', '-days', '7',
    '-keyout', $kafkaCaKey, '-out', $kafkaCaCert, '-subj', '/CN=SmartTraffic-Kafka-Local-CA',
    '-addext', 'basicConstraints=critical,CA:TRUE',
    '-addext', 'keyUsage=critical,keyCertSign,cRLSign'
)
Set-Content -LiteralPath $brokerExtensions -Encoding ascii -Value @'
basicConstraints=critical,CA:FALSE
keyUsage=critical,digitalSignature,keyEncipherment
extendedKeyUsage=serverAuth,clientAuth
subjectAltName=DNS:kafka,DNS:localhost,IP:127.0.0.1
'@
Invoke-OpenSsl -Arguments @(
    'req', '-new', '-newkey', 'rsa:2048', '-nodes', '-sha256',
    '-keyout', $brokerKey, '-out', $brokerCsr, '-subj', '/CN=kafka'
)
Invoke-OpenSsl -Arguments @(
    'x509', '-req', '-in', $brokerCsr, '-CA', $kafkaCaCert, '-CAkey', $kafkaCaKey,
    '-CAcreateserial', '-out', $brokerCert, '-days', '7', '-sha256',
    '-extfile', $brokerExtensions
)
Invoke-OpenSsl -Arguments @(
    'pkcs12', '-export', '-out', $brokerKeystore, '-inkey', $brokerKey,
    '-in', $brokerCert, '-certfile', $kafkaCaCert, '-name', 'kafka-local',
    '-passout', "pass:$brokerKeystorePassword"
)
Invoke-Keytool -Arguments @(
    '-importcert', '-noprompt', '-storetype', 'PKCS12', '-keystore', $brokerTruststore,
    '-storepass', $brokerTruststorePassword, '-alias', 'kafka-local-ca', '-file', $kafkaCaCert
)
Invoke-Keytool -Arguments @(
    '-importcert', '-noprompt', '-storetype', 'PKCS12', '-keystore', $authTruststore,
    '-storepass', $authTruststorePassword, '-alias', 'auth-local-ca', '-file', $authTruststoreSource
)

Remove-Item -LiteralPath $kafkaCaKey, $brokerKey, $brokerCsr, $brokerExtensions

$testClientsPath = Join-Path $outputPath 'test-clients.env'
$testClients = @{}
Get-Content -LiteralPath $testClientsPath | ForEach-Object {
    if ($_ -match '^([A-Z0-9_]+)=(.*)$') {
        $testClients[$Matches[1]] = $Matches[2]
    }
}

$kafkaSecrets = @{
    'smarttraffic-kafka' = [Guid]::NewGuid().ToString('N')
    'traffic-simulator-it' = [Guid]::NewGuid().ToString('N')
    'traffic-processor-it' = [Guid]::NewGuid().ToString('N')
    'traffic-archiver-it' = [Guid]::NewGuid().ToString('N')
}
$authConfigPath = Join-Path $outputPath 'application-test.yml'
$authConfig = Get-Content -LiteralPath $authConfigPath -Raw
$yamlPathPrefix = $outputPath.Replace('\', '/')
$authConfig = $authConfig.Replace(
    "file:/$yamlPathPrefix/auth-tls.p12",
    'file:/demo-assets/auth-tls.p12'
)
$authConfig = $authConfig.Replace(
    "$yamlPathPrefix/jwt-private.pem",
    '/demo-assets/jwt-private.pem'
)
$authConfig = $authConfig.Replace(
    "$yamlPathPrefix/jwt-public.pem",
    '/demo-assets/jwt-public.pem'
)
$authConfig = $authConfig.Replace(
    'https://host.docker.internal:8443',
    'https://host.docker.internal:18443'
)
$authConfig = $authConfig.Replace('access-token-ttl: PT2M', 'access-token-ttl: PT5M')
$kafkaClientsYaml = @"
      smarttraffic-kafka:
        secret: "$($kafkaSecrets['smarttraffic-kafka'])"
        scopes:
          - smarttraffic-kafka-it
        audiences:
          - smarttraffic-kafka
      traffic-simulator-it:
        secret: "$($kafkaSecrets['traffic-simulator-it'])"
        scopes:
          - smarttraffic-kafka-it
        audiences:
          - smarttraffic-kafka
      traffic-processor-it:
        secret: "$($kafkaSecrets['traffic-processor-it'])"
        scopes:
          - smarttraffic-kafka-it
        audiences:
          - smarttraffic-kafka
      traffic-archiver-it:
        secret: "$($kafkaSecrets['traffic-archiver-it'])"
        scopes:
          - smarttraffic-kafka-it
        audiences:
          - smarttraffic-kafka
"@
$authConfig = $authConfig.TrimEnd() + "`r`n" + $kafkaClientsYaml
$containerAuthConfigPath = Join-Path $outputPath 'application-test-container.yml'
Set-Content -LiteralPath $containerAuthConfigPath -Encoding utf8 -Value $authConfig

$authTruststoreContainerPath = '/etc/kafka/secrets/auth.truststore.p12'
$jaasPath = Join-Path $outputPath 'kafka-client-jaas.conf'
$jaas = @"
KafkaClient {
  org.apache.kafka.common.security.oauthbearer.OAuthBearerLoginModule required
  oauth.token.endpoint.uri="https://host.docker.internal:18443/oauth2/token"
  oauth.client.id="smarttraffic-kafka"
  oauth.client.secret="$($kafkaSecrets['smarttraffic-kafka'])"
  oauth.ssl.truststore.location="$authTruststoreContainerPath"
  oauth.ssl.truststore.password="$authTruststorePassword"
  oauth.ssl.truststore.type="PKCS12";
};
"@
Set-Content -LiteralPath $jaasPath -Encoding ascii -Value $jaas

$adminPropertiesPath = Join-Path $outputPath 'kafka-admin.properties'
$adminProperties = @"
security.protocol=SASL_SSL
sasl.mechanism=OAUTHBEARER
sasl.login.callback.handler.class=io.strimzi.kafka.oauth.client.JaasClientOauthLoginCallbackHandler
sasl.jaas.config=org.apache.kafka.common.security.oauthbearer.OAuthBearerLoginModule required oauth.token.endpoint.uri="https://host.docker.internal:18443/oauth2/token" oauth.client.id="smarttraffic-kafka" oauth.client.secret="$($kafkaSecrets['smarttraffic-kafka'])" oauth.ssl.truststore.location="/demo-assets/kafka-tls/auth.truststore.p12" oauth.ssl.truststore.password="$authTruststorePassword" oauth.ssl.truststore.type="PKCS12";
ssl.endpoint.identification.algorithm=https
ssl.truststore.location=/demo-assets/kafka-tls/broker.truststore.p12
ssl.truststore.password=$brokerTruststorePassword
ssl.truststore.type=PKCS12
"@
Set-Content -LiteralPath $adminPropertiesPath -Encoding ascii -Value $adminProperties

$kafkaOpts = "-Djava.security.auth.login.config=/tmp/kafka-client-jaas.conf -Djavax.net.ssl.trustStore=$authTruststoreContainerPath -Djavax.net.ssl.trustStorePassword=$authTruststorePassword -Djavax.net.ssl.trustStoreType=PKCS12"
$assetsForCompose = $outputPath.Replace('\', '/')
$demoEnv = @(
    "LOCAL_DEMO_ASSETS=$assetsForCompose"
    "KAFKA_CLUSTER_ID=$clusterId"
    'CLIENT_PORT=9093'
    'CONTROLLER_PORT=9094'
    'OAUTH_JWKS_ENDPOINT=https://host.docker.internal:18443/oauth2/jwks'
    'OAUTH_ISSUER_URI=https://host.docker.internal:18443'
    "OAUTH_KAFKA_CLIENT_SECRET=$($kafkaSecrets['smarttraffic-kafka'])"
    'OAUTH_KAFKA_AUDIENCE=smarttraffic-kafka'
    'OAUTH_TOKEN_ENDPOINT=https://host.docker.internal:18443/oauth2/token'
    'KAFKA_SUPER_USERS=User:CN=kafka;User:smarttraffic-kafka'
    "TLS_SECRETS_DIR=$assetsForCompose/kafka-tls"
    "KAFKA_CLIENT_JAAS_FILE=$assetsForCompose/kafka-client-jaas.conf"
    'TLS_KEYSTORE_FILENAME=broker.keystore.p12'
    "TLS_KEYSTORE_PASSWORD=$brokerKeystorePassword"
    "TLS_KEY_PASSWORD=$brokerKeystorePassword"
    'TLS_TRUSTSTORE_FILENAME=broker.truststore.p12'
    "TLS_TRUSTSTORE_PASSWORD=$brokerTruststorePassword"
    'TLS_KEYSTORE_TYPE=PKCS12'
    'TLS_TRUSTSTORE_TYPE=PKCS12'
    'TLS_CLIENT_AUTH=none'
    "KAFKA_OPTS=$kafkaOpts"
    "AUTH_TRUSTSTORE_PASSWORD=$authTruststorePassword"
    "KAFKA_TRUSTSTORE_PASSWORD=$brokerTruststorePassword"
    "KAFKA_SIMULATOR_SECRET=$($kafkaSecrets['traffic-simulator-it'])"
    "KAFKA_PROCESSOR_SECRET=$($kafkaSecrets['traffic-processor-it'])"
    "KAFKA_ARCHIVER_SECRET=$($kafkaSecrets['traffic-archiver-it'])"
    "RABBITMQ_TEST_CERT_DIR=$assetsForCompose"
    'RABBITMQ_OAUTH_JWKS_URI=https://host.docker.internal:18443/oauth2/jwks'
    "RABBITMQ_IT_PUBLISHER_SECRET=$($testClients['RABBITMQ_IT_PUBLISHER_SECRET'])"
    "RABBITMQ_IT_READER_SECRET=$($testClients['RABBITMQ_IT_READER_SECRET'])"
    "RABBITMQ_IT_ALERT_SECRET=$($testClients['RABBITMQ_IT_ALERT_SECRET'])"
    "RABBITMQ_IT_QUERY_SECRET=$($testClients['RABBITMQ_IT_QUERY_SECRET'])"
    "POSTGRES_PASSWORD=$([Guid]::NewGuid().ToString('N'))"
)
$demoEnvPath = Join-Path $outputPath 'demo.env'
Set-Content -LiteralPath $demoEnvPath -Encoding ascii -Value $demoEnv

Write-Output "Generated test-only demo assets outside the repository: $outputPath"
Write-Output 'Compose environment: demo.env (contains disposable credentials; do not share or commit)'
