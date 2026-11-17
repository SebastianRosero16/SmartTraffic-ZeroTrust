# Application container image

`app.Dockerfile` is a shared multi-stage image recipe for the seven Java
services. It builds the selected Maven module and its reactor dependencies
using a persistent BuildKit Maven cache, and copies only compiled classes and
runtime libraries into a Java 17 runtime
Alpine image, runs as the unprivileged `appuser:appgroup`, and starts Java with
container-aware memory sizing (`-XX:MaxRAMPercentage=75.0`) and
`-XX:+ExitOnOutOfMemoryError`.

Build from the repository root, for example:

```powershell
docker build `
  --file infra/images/app.Dockerfile `
  --build-arg MAVEN_MODULE=services/alert-dispatcher `
  --build-arg MAIN_CLASS=edu.smarttraffic.alerts.AlertDispatcherApplication `
  --tag smarttraffic/alert-dispatcher:local .
```

Set `MAVEN_MODULE` and `MAIN_CLASS` to the corresponding module and entry point
for each service. Runtime credentials, certificates, and Spring configuration
must be mounted or injected at container start; the image build excludes local
environment files, keys, certificates, Terraform state, and Maven outputs.

The image build needs network access to the Maven repository on a cold cache.
Base-image digests and per-VM resource limits still require review before
deployment.
