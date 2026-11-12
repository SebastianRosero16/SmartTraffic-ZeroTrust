FROM maven:3.9.9-eclipse-temurin-17@sha256:f58d59b6273e785ac0a4477f6e9b5ba1d7731c75b906c0f7b34076f1851318cc AS oauth-dependencies

WORKDIR /build
COPY kafka-oauth/pom.xml ./pom.xml

RUN mkdir -p /out \
    && mvn -B -ntp -C \
      org.apache.maven.plugins:maven-dependency-plugin:3.8.1:tree \
      -Dscope=runtime \
      -DoutputFile=/out/dependency-tree.txt \
    && mvn -B -ntp -C \
      org.apache.maven.plugins:maven-dependency-plugin:3.8.1:copy-dependencies \
      -DincludeScope=runtime \
      -DincludeArtifactIds=kafka-oauth-server,kafka-oauth-common,kafka-oauth-client,nimbus-jose-jwt \
      -DoutputDirectory=/out/libs \
    && test -f /out/libs/kafka-oauth-server-0.18.1.jar \
    && test -f /out/libs/kafka-oauth-common-0.18.1.jar \
    && test -f /out/libs/kafka-oauth-client-0.18.1.jar \
    && test -f /out/libs/nimbus-jose-jwt-10.0.2.jar \
    && cd /out/libs \
    && sha256sum ./*.jar > /out/SHA256SUMS \
    && sha256sum --check /out/SHA256SUMS

FROM apache/kafka:4.4.0@sha256:833b867a9585a141da455dc2b57431c65cbccb27f11c91f694444cca9f777e0f

USER root
RUN mkdir -p /var/lib/kafka/data /opt/kafka/strimzi-oauth \
    && chown -R appuser:appuser /var/lib/kafka/data /opt/kafka/strimzi-oauth
COPY --from=oauth-dependencies /out/libs/ /opt/kafka/libs/
COPY --from=oauth-dependencies /out/dependency-tree.txt /opt/kafka/strimzi-oauth/dependency-tree.txt
COPY --from=oauth-dependencies /out/SHA256SUMS /opt/kafka/strimzi-oauth/SHA256SUMS
USER appuser
