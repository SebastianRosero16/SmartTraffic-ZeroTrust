FROM maven:3.9.9-eclipse-temurin-17 AS build

WORKDIR /workspace
COPY . .

ARG MAVEN_MODULE
RUN test -n "$MAVEN_MODULE" \
    && mvn -B -DskipTests -pl "$MAVEN_MODULE" -am install

WORKDIR /workspace/${MAVEN_MODULE}
RUN mvn -B org.apache.maven.plugins:maven-dependency-plugin:3.7.1:copy-dependencies \
    -DincludeScope=runtime \
    -DoutputDirectory=target/dependency

FROM eclipse-temurin:17-jre-jammy

ARG MAVEN_MODULE
ARG MAIN_CLASS
ENV MAIN_CLASS=${MAIN_CLASS}

RUN groupadd --system appgroup \
    && useradd --system --gid appgroup --create-home appuser \
    && mkdir -p /app/classes /app/lib \
    && chown -R root:root /app

WORKDIR /app
COPY --from=build /workspace/${MAVEN_MODULE}/target/classes/ /app/classes/
COPY --from=build /workspace/${MAVEN_MODULE}/target/dependency/ /app/lib/
COPY infra/images/entrypoint.sh /entrypoint.sh

USER appuser:appgroup
ENTRYPOINT ["sh", "/entrypoint.sh"]
