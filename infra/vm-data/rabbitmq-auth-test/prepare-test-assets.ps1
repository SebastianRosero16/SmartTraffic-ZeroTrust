[CmdletBinding()]
param(
    [string]$OutputDirectory = (Join-Path $env:TEMP 'smarttraffic-rabbitmq-it')
)

$ErrorActionPreference = 'Stop'

$repositoryRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..\..')).TrimEnd('\')
$outputPath = [System.IO.Path]::GetFullPath($OutputDirectory).TrimEnd('\')
$repositoryPrefix = $repositoryRoot + '\'
if ($outputPath.Equals($repositoryRoot, [System.StringComparison]::OrdinalIgnoreCase) -or
        $outputPath.StartsWith($repositoryPrefix, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw 'Test keys and certificates must be generated outside the repository.'
}

if (Test-Path -LiteralPath $outputPath) {
    if (-not (Get-Item -LiteralPath $outputPath).PSIsContainer) {
        throw "The output path is not a directory: $outputPath"
    }
    if (Get-ChildItem -LiteralPath $outputPath -Force | Select-Object -First 1) {
        throw "The output directory must be empty: $outputPath"
    }
} else {
    New-Item -ItemType Directory -Path $outputPath | Out-Null
}

$opensslCommand = Get-Command openssl.exe -ErrorAction SilentlyContinue
$opensslPath = if ($opensslCommand) { $opensslCommand.Source } else { 'C:\Program Files\Git\usr\bin\openssl.exe' }
if (-not (Test-Path -LiteralPath $opensslPath)) {
    throw 'OpenSSL was not found. Install OpenSSL or Git for Windows.'
}

function Invoke-OpenSsl {
    param([Parameter(Mandatory = $true)][string[]]$Arguments)

    & $script:opensslPath @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "OpenSSL failed: openssl $($Arguments -join ' ')"
    }
}

$caKeyPath = Join-Path $outputPath 'test-ca-key.pem'
$caCertPath = Join-Path $outputPath 'oauth-ca.pem'
$serialPath = Join-Path $outputPath 'oauth-ca.srl'
$authKeyPath = Join-Path $outputPath 'auth-tls-key.pem'
$authCsrPath = Join-Path $outputPath 'auth-tls.csr'
$authCertPath = Join-Path $outputPath 'auth-tls.pem'
$authExtensionsPath = Join-Path $outputPath 'auth-tls-extensions.cnf'
$rabbitKeyPath = Join-Path $outputPath 'server-key.pem'
$rabbitCsrPath = Join-Path $outputPath 'server.csr'
$rabbitCertPath = Join-Path $outputPath 'server.pem'
$rabbitExtensionsPath = Join-Path $outputPath 'rabbit-tls-extensions.cnf'
$storePath = Join-Path $outputPath 'auth-tls.p12'
$jwtPrivatePath = Join-Path $outputPath 'jwt-private.pem'
$jwtPublicPath = Join-Path $outputPath 'jwt-public.pem'

Invoke-OpenSsl -Arguments @(
    'genpkey', '-algorithm', 'RSA', '-pkeyopt', 'rsa_keygen_bits:2048',
    '-out', $jwtPrivatePath
)
Invoke-OpenSsl -Arguments @('pkey', '-in', $jwtPrivatePath, '-pubout', '-out', $jwtPublicPath)
Invoke-OpenSsl -Arguments @(
    'req', '-x509', '-newkey', 'rsa:2048', '-nodes', '-sha256', '-days', '7',
    '-keyout', $caKeyPath, '-out', $caCertPath, '-subj', '/CN=SmartTraffic-RabbitMQ-IT-CA',
    '-addext', 'basicConstraints=critical,CA:TRUE',
    '-addext', 'keyUsage=critical,keyCertSign,cRLSign'
)

Set-Content -LiteralPath $authExtensionsPath -Encoding ascii -Value @'
basicConstraints=critical,CA:FALSE
keyUsage=critical,digitalSignature,keyEncipherment
extendedKeyUsage=serverAuth
subjectAltName=DNS:host.docker.internal
'@
Invoke-OpenSsl -Arguments @(
    'req', '-new', '-newkey', 'rsa:2048', '-nodes', '-sha256',
    '-keyout', $authKeyPath, '-out', $authCsrPath, '-subj', '/CN=host.docker.internal'
)
Invoke-OpenSsl -Arguments @(
    'x509', '-req', '-in', $authCsrPath, '-CA', $caCertPath, '-CAkey', $caKeyPath,
    '-CAcreateserial', '-out', $authCertPath, '-days', '7', '-sha256',
    '-extfile', $authExtensionsPath
)

Set-Content -LiteralPath $rabbitExtensionsPath -Encoding ascii -Value @'
basicConstraints=critical,CA:FALSE
keyUsage=critical,digitalSignature,keyEncipherment
extendedKeyUsage=serverAuth
subjectAltName=DNS:rabbitmq
'@
Invoke-OpenSsl -Arguments @(
    'req', '-new', '-newkey', 'rsa:2048', '-nodes', '-sha256',
    '-keyout', $rabbitKeyPath, '-out', $rabbitCsrPath, '-subj', '/CN=rabbitmq'
)
Invoke-OpenSsl -Arguments @(
    'x509', '-req', '-in', $rabbitCsrPath, '-CA', $caCertPath, '-CAkey', $caKeyPath,
    '-CAserial', $serialPath, '-out', $rabbitCertPath, '-days', '7', '-sha256',
    '-extfile', $rabbitExtensionsPath
)

$storePassword = [Guid]::NewGuid().ToString('N')
Invoke-OpenSsl -Arguments @(
    'pkcs12', '-export', '-out', $storePath, '-inkey', $authKeyPath,
    '-in', $authCertPath, '-certfile', $caCertPath, '-name', 'auth-test',
    '-passout', "pass:$storePassword"
)

$authPropertiesPath = ($outputPath.Replace('\', '/') + '/application-test.yml')
$testClientsPath = Join-Path $outputPath 'test-clients.env'
$storePathForYaml = $storePath.Replace('\', '/')
$jwtPrivatePathForYaml = $jwtPrivatePath.Replace('\', '/')
$jwtPublicPathForYaml = $jwtPublicPath.Replace('\', '/')
$publisherSecret = [Guid]::NewGuid().ToString('N')
$readerSecret = [Guid]::NewGuid().ToString('N')
$alertSecret = [Guid]::NewGuid().ToString('N')
$querySecret = [Guid]::NewGuid().ToString('N')
$authProperties = @"
server:
  address: 0.0.0.0
  port: 8443
  ssl:
    enabled: true
    key-store: "file:/$storePathForYaml"
    key-store-password: "$storePassword"
    key-store-type: PKCS12
    key-alias: auth-test

smarttraffic:
  auth:
    issuer: https://host.docker.internal:8443
    access-token-ttl: PT2M
    key-id: rabbitmq-it-rsa
    private-key-path: "$jwtPrivatePathForYaml"
    public-key-path: "$jwtPublicPathForYaml"
    clients:
      rabbit-publisher-it:
        secret: "$publisherSecret"
        scopes:
          - smarttraffic-rabbitmq-it.write:smarttraffic-it/traffic-updates-it
          - smarttraffic-rabbitmq-it.write:smarttraffic-it/query-answers-it
          - smarttraffic-rabbitmq-it.write:smarttraffic-it/query-answers-it/unmatched-it
        audiences:
          - smarttraffic-rabbitmq-it
      rabbit-reader-it:
        secret: "$readerSecret"
        scopes:
          - smarttraffic-rabbitmq-it.read:smarttraffic-it/dashboard-updates-it
        audiences:
          - smarttraffic-rabbitmq-it
      rabbit-alert-it:
        secret: "$alertSecret"
        scopes:
          - smarttraffic-rabbitmq-it.read:smarttraffic-it/alert-updates-it
          - smarttraffic-rabbitmq-it.read:smarttraffic-it/query-requests-it
          - smarttraffic-rabbitmq-it.write:smarttraffic-it/query-answers-it/client-it
        audiences:
          - smarttraffic-rabbitmq-it
      rabbit-query-client-it:
        secret: "$querySecret"
        scopes:
          - smarttraffic-rabbitmq-it.write:smarttraffic-it/query-requests-it
          - smarttraffic-rabbitmq-it.read:smarttraffic-it/query-replies-it
        audiences:
          - smarttraffic-rabbitmq-it
"@
Set-Content -LiteralPath $authPropertiesPath -Encoding utf8 -Value $authProperties
Set-Content -LiteralPath $testClientsPath -Encoding ascii -Value @(
    "RABBITMQ_IT_PUBLISHER_SECRET=$publisherSecret"
    "RABBITMQ_IT_READER_SECRET=$readerSecret"
    "RABBITMQ_IT_ALERT_SECRET=$alertSecret"
    "RABBITMQ_IT_QUERY_SECRET=$querySecret"
)
Remove-Item -LiteralPath $caKeyPath

Write-Output "Generated test-only keys and certificates outside the repository: $outputPath"
Write-Output 'Auth Server config: application-test.yml'
Write-Output 'Test client credentials: test-clients.env (keep outside Git)'
Write-Output 'JWKS URI for RabbitMQ: https://host.docker.internal:8443/oauth2/jwks'
Write-Output "Run the Auth Server with: mvn -pl services/auth-server spring-boot:run `"-Dspring-boot.run.arguments=--spring.config.additional-location=file:$authPropertiesPath`""
