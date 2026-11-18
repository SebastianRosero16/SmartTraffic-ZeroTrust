# Matriz de comunicaciones de SmartTraffic

## Alcance y convenciones

Esta matriz complementa el
[overview de arquitectura](./architecture-overview.md) y el
[README](../README.md). Describe flujos lógicos previstos, no reglas `nftables`
ya implementadas. No asigna direcciones IP, puertos ni reglas definitivas.

Es importante distinguir:

- **Iniciador de conexión de red:** el cliente que abre la conexión hacia el
  servicio. En los flujos de broker, normalmente es el productor o consumidor
  quien se conecta al broker.
- **Sentido lógico del mensaje:** quién publica o produce el dato y quién lo
  consume. Puede ser opuesto al iniciador de la conexión; por ejemplo, el
  consumidor inicia la conexión a Kafka, aunque las lecturas fluyan
  lógicamente desde Kafka hacia el consumidor.

La matriz distingue requisitos obligatorios del Proyecto 12 de detalles
pendientes. La inclusión de un flujo no significa que se haya configurado o
probado. En particular, el uso de Kafka/RabbitMQ y sus mecanismos de
autenticación indicados abajo es obligatorio; nombres y parámetros concretos
solo lo son si el enunciado los fija.

**Estado de revisión al 17 de noviembre de 2026:** la demostración se abrió
desde el portátil mediante Session Manager; el túnel llegó al listener local
del dashboard en `vm-app` por el puerto remoto `8080`, el backend informó
conexión y la marca de última medición avanzó. El README registra además
respuestas de consultas y transiciones de alertas observadas durante esa
demostración. Esto es evidencia de ejecución funcional en esa sesión, no prueba
de todos los controles de seguridad ni identifica el commit desplegado.
Siguen pendientes la aprobación de CIDR, listeners y flujos productivos, así
como reconciliar el estado AWS con Terraform. La inspección de `nftables` en
las cinco VM encontró política `drop` en input, forward y output, excepciones
de salida por rol y TCP 443 hacia destinos fuera del CIDR de la VPC.

**Alcance de Terraform y estado vivo:** en el código, `network_rules` está
vacío por defecto y la salida predeterminada de Security Groups es TCP 443.
La revisión posterior encontró reglas activas por rol que no figuran como
configuración predeterminada: `vm-auth` recibe HTTPS TCP 443 desde los otros
grupos; `vm-data` recibe PostgreSQL 5432, RabbitMQ 5672 y Kafka 9092 desde
grupos específicos; `vm-app` no tiene entrada pública y usa el túnel SSM.
`vm-core` e `vm-ingest` no tienen reglas de entrada. Todos los grupos revisados
permiten HTTPS 443 temporal a `0.0.0.0/0`. Esto es el estado observado en
Security Groups, no una configuración formalmente confirmada en el repositorio.
No apliques un plan para reconciliarlo sin revisar state y recuperación de datos.

La revisión de solo lectura de rutas del 17 de noviembre de 2026 encontró una
tabla `smarttraffic-private` asociada a cinco subnets, con la ruta
`0.0.0.0/0` hacia un NAT Gateway público en estado `Available`. La tabla
`smarttraffic-public` está asociada a `smarttraffic-nat` y envía
`0.0.0.0/0` al Internet Gateway. El VPC observado usa `10.42.0.0/16`. Esta
topología difiere del diseño Terraform, que envía directamente las subnets VM
al Internet Gateway y no declara un NAT Gateway; no se debe aplicar sin
reconciliar el estado y revisar costos y datos.

La inspección actual de egreso confirmó TCP 443 a `0.0.0.0/0` en los cinco
Security Groups, además de DNS TCP/UDP hacia `10.42.0.2/32`. Los flujos
adicionales observados son: `vm-data` → Auth 443; `vm-ingest` → Auth 443 y
`vm-data` 9092; `vm-core` → Auth 443 y `vm-data` 9092/5672/5432; `vm-app` →
Auth 443 y `vm-data` 5672. El egreso HTTPS global aparece descrito como
temporal; sigue siendo un permiso amplio, no una política por destino.

**Almacenamiento observado:** AWS contiene cinco volúmenes raíz EBS cifrados de
30 GiB, uno adjunto a cada VM. En `vm-data` el único disco observado es el
volumen raíz XFS de 30 GiB; no aparece un volumen separado para
PostgreSQL, Kafka o RabbitMQ, por lo que la configuración de datos dedicada
prevista en Terraform/Ansible no se observó como adjunta a esa instancia. No
hay todavía evidencia de un cutover de datos a `/srv/smarttraffic/{...}`.

**`vm-app` observado por SSM:** el dashboard escucha solo en
`127.0.0.1:8080`; los contenedores del dashboard, `alert-dispatcher` y dos
`query-client` estaban activos. Las imágenes usan etiquetas locales y no
incluyen `org.opencontainers.image.revision`; sus image IDs no se han
correlacionado con el checkout, por lo que el commit desplegado sigue sin
identificarse. Un `curl` local por HTTP devolvió `302` hacia
`http://127.0.0.1:8080/dashboard.html`; la prueba HTTPS y el handshake TLS
fallaron con `wrong version number`, lo que confirma que el listener 8080 no
negocia TLS directamente. El listener SSH visible en el host no equivale a
acceso de red: el Security Group de `vm-app` observado no tiene reglas de
entrada. En los cuatro contenedores de aplicación, el certificado PEM de CA
configurado para RabbitMQ tiene huella SHA-256
`58:99:8C:58:B0:66:C4:33:A0:18:81:49:85:39:64:4C:FD:5A:D0:2C:27:24:98:F5:22:7A:9C:C9:59:78:F9:AA`,
igual a la raíz presentada por el servidor. `keytool` obtuvo la cadena del
servidor en el destino configurado; el certificado hoja incluye SAN para
`10.42.2.7`, `rabbitmq`, `vm-data` y `localhost`. Esto confirma la presencia
de la CA y los nombres en el certificado, pero no una conexión AMQPS funcional
ni la validación efectiva de CA/hostname por cada cliente.

**`vm-data` observado por SSM:** `ss` mostró listeners de Kafka en 9092 y
9093 (este último en loopback), RabbitMQ en 5672 y PostgreSQL en 5432. Los
contenedores Kafka, RabbitMQ y PostgreSQL estaban activos. El ruleset
`nftables` limita la entrada a puertos 9092, 5672 y 5432 desde direcciones
privadas específicas y limita los flujos salientes entre servicios; también
permite HTTPS a destinos fuera del CIDR de la VPC. El archivo versionado
`rabbitmq-production.conf` configura `listeners.tcp = none` y un listener
SSL en 5672; `rabbitmq-diagnostics listeners` confirmó `amqp/ssl` en ese
puerto. En PostgreSQL, `SHOW ssl` devolvió `off`. La negociación TLS de Kafka
se completó en TLS 1.3 en 9092; la verificación de confianza de OpenSSL
reportó una cadena autofirmada (`CN=SmartTraffic-Demo-CA`). Esto confirma el
cifrado del canal, no que los clientes validen correctamente la CA/hostname
ni que la autenticación Kafka SASL funcione.

**`vm-core` observado por SSM:** `ss` no mostró listeners de aplicación en el
host; estaban activos `traffic-archiver` y `traffic-density-processor`. El
firewall usa política `drop` en las tres cadenas, permite las conexiones
salientes necesarias a Kafka 9092, RabbitMQ 5672, PostgreSQL 5432 y Auth Server
443, además de HTTPS a destinos fuera del CIDR de la VPC. Solo se observó el
disco raíz XFS de 30 GiB; las rutas `buildkit-mount` corresponden al mismo
dispositivo. Los dos contenedores tienen truststores PKCS12 de Kafka y Auth
Server con la CA `SmartTraffic-Demo-CA`, cuya huella coincide con la CA servida
por ambos endpoints. `keytool` consultó los destinos configurados
(`10.42.2.7:9092` y `10.42.1.201`); los certificados incluyen SAN para esas
direcciones. La configuración local valida hostname, pero aún no se confirmó
por separado una operación exitosa de cada cliente ni el commit de las imágenes.
En `traffic-density-processor`, las CA PEM de RabbitMQ y Auth Server también
coinciden por huella con las cadenas servidas; el SAN del certificado de
RabbitMQ incluye el destino configurado y el código local habilita validación
de hostname. La conexión AMQPS efectiva de este cliente no se confirmó por
separado.

**`vm-ingest` observado por SSM:** `ss` no mostró listeners de aplicación en el
host; estaba activo `traffic-sensor-simulator`. El firewall usa política
`drop` en input/forward/output y permite salida a Kafka 9092 y Auth Server 443,
además de HTTPS a destinos fuera del CIDR de la VPC. Solo se observó el disco
raíz XFS de 30 GiB; las rutas `buildkit-mount` corresponden al mismo
dispositivo. Dentro del contenedor, los truststores PKCS12 de Kafka y Auth
Server existen, son legibles y cada uno contiene una entrada `trustedCertEntry`
para `CN=SmartTraffic-Demo-CA`, con la misma huella SHA-256. `keytool
-printcert -sslserver`, ejecutado con los destinos configurados, presentó
cadenas cuya CA tiene exactamente esa misma huella; los certificados de hoja
incluyen SAN para las direcciones privadas observadas. Los logs de los últimos
cinco minutos mostraron publicaciones confirmadas en Kafka y ningún error
TLS/SASL de los patrones consultados, lo que confirma una ruta de publicación
funcional para el simulador. El commit desplegado no está identificado; las
operaciones de los clientes de `vm-core` aún no se confirmaron por separado.

**`vm-auth` observado por SSM:** Java escucha en `*:443` y el contenedor
`auth-server` estaba activo. El firewall limita la entrada a 443 desde
direcciones privadas específicas y permite HTTPS saliente a destinos fuera
del CIDR de la VPC. Una conexión local con OpenSSL negoció TLS 1.3, pero la
verificación predeterminada rechazó la cadena autofirmada
(`CN=SmartTraffic-Demo-CA`; certificado del peer `CN=10.42.1.201`). El
certificado incluye SAN para `10.42.1.201` y `vm-auth`. La CA coincide con los
truststores Auth revisados en los tres clientes Kafka y con el archivo PEM Auth
del `traffic-density-processor`; esto verifica material de confianza, no una
solicitud de token exitosa para cada cliente. Solo se observó el disco raíz XFS
de 30 GiB.

## Disponibilidad de los servicios

El checkout actual contiene cambios locales en `alert-dispatcher` y
`query-client` que aún no están publicados en un commit. La demostración AWS
del 17 de noviembre mostró alertas y respuestas de consulta, pero no se
identificó el commit desplegado ni se comparó con este checkout. Por tanto,
separa la evidencia de ejecución de la publicación/reproducibilidad del código:
antes de afirmar que un clon limpio reproduce esa demo, publica y valida el
commit que se presentará.

## Flujos de aplicación

| Flujo | Iniciador de conexión de red | Sentido lógico del mensaje | Servicio / protocolo conocido | Propósito | Estado |
|---|---|---|---|---|---|
| Simulador → Kafka | `traffic_sensor_simulator` abre conexión hacia Kafka en `vm-data`. | Lecturas: simulador → tópico Kafka. | Kafka con SASL_SSL/OAUTHBEARER; puerto por definir. | Publicar lecturas aleatorias; `traffic_readings` es el tópico documentado para pruebas. | El adaptador y la configuración OAuth existen en el checkout; tópico/ACL de producción, identidad, IP, listener y puerto siguen pendientes. |
| Kafka → procesador | `traffic_density_processor` abre conexión hacia Kafka en `vm-data`. | Lecturas: Kafka → procesador. | Kafka Streams/KTable con SASL_SSL/OAUTHBEARER; puerto por definir. | Consumir lecturas y calcular estado por zona. | Adaptador en el checkout; grupo/ACL, identidad, IP, listener y puerto de producción pendientes. |
| Kafka → archivador | `traffic_archiver` abre conexión hacia Kafka en `vm-data`. | Lecturas originales: Kafka → archivador. | Kafka con SASL_SSL/OAUTHBEARER; puerto por definir. | Consumir lecturas en paralelo para archivarlas. | Adaptador en el checkout; grupo/ACL, identidad, IP, listener y puerto de producción pendientes. |
| Archivador → PostgreSQL | `traffic_archiver` abre conexión hacia PostgreSQL en `vm-data`. | Escrituras históricas: archivador → PostgreSQL. | PostgreSQL con Spring Data JPA/Hibernate; puerto por definir. | Guardar lecturas históricas y auditoría según el esquema aprobado. | PostgreSQL y Spring Data JPA/Hibernate: requisitos; esquema, credenciales, IP y puerto: pendientes. |
| Procesador → RabbitMQ | `traffic_density_processor` abre conexión hacia RabbitMQ en `vm-data`. | Actualizaciones: procesador → exchange `traffic_updates`. | RabbitMQ con `rabbitmq_auth_backend_oauth2` por AMQPS; puerto por definir. | Publicar estados calculados para consumidores independientes. | Publicador OAuth2/AMQPS en el checkout; requiere permiso de escritura al exchange. Topología/ACL, IP y puerto de producción pendientes; la prueba aislada no define producción. |
| RabbitMQ → backend del dashboard | `user_dashboard_backend` abre conexión hacia RabbitMQ en `vm-data`. | Actualizaciones: RabbitMQ → backend, desde `dashboard_updates_queue` por defecto. | RabbitMQ con `rabbitmq_auth_backend_oauth2`; AMQPS configurado por el cliente. | Entregar actualizaciones para presentación. | La demo AWS mostró el dashboard conectado y con mediciones en vivo. No se verificaron la cola, ACL, listener ni el commit desplegado; la ejecución no sustituye esa revisión. |
| RabbitMQ → dispatcher de alertas | El consumidor `alert_dispatcher` abre conexión hacia RabbitMQ en `vm-data`. | Actualizaciones: RabbitMQ → dispatcher, desde una cola de alertas. | Diseño OAuth2/AMQPS; puerto productivo por confirmar. | Actualizar la réplica local y registrar transiciones como alertas. | La demo AWS mostró transiciones de alertas (ver README). El cambio local aún no está publicado y no se identificó el commit desplegado ni se revisó la política de autorización efectiva. |
| Backend → dashboard web | El navegador que carga `dashboard.html` abre la conexión WebSocket hacia `user_dashboard_backend`. | Actualizaciones: backend → navegador. | WebSocket same-origin en `/ws/traffic`; `ws` o `wss` según el esquema HTTP del backend. | Presentar tráfico en tiempo real. | Verificado en la demo AWS por el túnel SSM: el navegador mostró conexión y mediciones que avanzaban. El puerto remoto observado fue `8080`. Se inspeccionaron Security Groups y `nftables`; `vm-app` no tenía ingress y la cadena input de `nftables` aplica `drop` por defecto. No se probó exposición pública. |
| Cliente de consulta → RabbitMQ | Cada cliente `query_client` abre conexión hacia RabbitMQ en `vm-data`. | Solicitud: cliente → cola de solicitudes; incluye zonas, `requestId`, `correlationId`, `instanceId` y `replyTo`. | Diseño OAuth2/AMQPS; puerto productivo por confirmar. | Enviar consultas asíncronas y esperar respuestas. | La demo AWS mostró respuestas de consulta. El código local aún no está publicado; el commit desplegado, la cola y la autorización efectiva no se verificaron. |
| RabbitMQ → dispatcher de consultas | El consumidor `alert_dispatcher` abre conexión hacia RabbitMQ en `vm-data`. | Solicitud: RabbitMQ → dispatcher, desde la cola de solicitudes. | Diseño OAuth2/AMQPS; puerto productivo por confirmar. | Entregar consulta para responder desde la réplica local. | Las respuestas observadas en la demo indican que el flujo funcional operó; no se identificó el commit desplegado ni se revisaron cola y permisos efectivos. |
| Dispatcher → RabbitMQ | `alert_dispatcher` abre conexión hacia RabbitMQ en `vm-data`. | Respuesta: dispatcher → exchange de respuestas, con `correlationId` y routing key de instancia. | Diseño OAuth2/AMQPS; puerto productivo por confirmar. | Publicar la respuesta confirmada para una instancia. | Las respuestas observadas en la demo indican que el flujo funcional operó; el exchange, bindings y permisos efectivos no se revisaron. |
| RabbitMQ → cliente de consulta | Cada cliente `query_client` abre conexión hacia RabbitMQ en `vm-data`. | Respuesta: RabbitMQ → cola propia de respuesta → cliente. | Diseño OAuth2/AMQPS; puerto productivo por confirmar. | Entregar y correlacionar respuestas por instancia. | La demo AWS mostró respuestas a los clientes; no se identificó el commit desplegado ni se verificaron la cola, el binding y los permisos efectivos. |

## Inventario de endpoints y puertos observados

Esta tabla registra interfaces encontradas en el código y documentación local;
no asigna puertos productivos ni autoriza reglas de firewall. Los valores de
fixtures, pruebas o defaults de desarrollo no deben copiarse a la configuración
de producción.

| Servicio | Interfaz configurada | Puerto de producción | Evidencia y límite |
|---|---|---|---|
| Kafka | Clientes configuran `KAFKA_BOOTSTRAP_SERVERS` y usan SASL_SSL/OAUTHBEARER; el código habilita validación de hostname HTTPS. El truststore Kafka es distinto al truststore HTTPS de Auth Server. | `9092` observado en la interfaz del host; controlador `9093` observado en loopback. OpenSSL negoció TLS 1.3; su almacén predeterminado rechazó la cadena autofirmada (`CN=SmartTraffic-Demo-CA`). Las huellas de CA de los tres clientes inspeccionados coinciden con la CA servida; los SAN incluyen el destino privado Kafka `10.42.2.7`. El simulador publicó eventos confirmados; faltan las operaciones efectivas de procesador/archivador y el commit desplegado. | El contenedor Kafka estaba activo. El Compose AWS del checkout configura `CLIENT:SASL_SSL`; el simulador publicó en ejecución, pero no se identificó el commit desplegado ni se verificó por separado la operación de los otros clientes. |
| Auth Server — token | Clientes solicitan token con OAuth `client_credentials`/`client_secret_basic` a `AUTH_TOKEN_ENDPOINT_URI` por HTTPS; la respuesta vuelve en esa misma conexión. | Java escucha en `*:443` en `vm-auth`; OpenSSL local negoció TLS 1.3, pero la cadena autofirmada (`CN=SmartTraffic-Demo-CA`) no fue confiada por el almacén predeterminado. Las huellas de CA en los truststores de los tres clientes Kafka inspeccionados coinciden con la CA servida; los SAN incluyen el destino privado `10.42.1.201` y `vm-auth`. | El contenedor `auth-server` estaba activo. Las publicaciones recientes del simulador confirman una ruta funcional, pero no se trazaron por separado las solicitudes OAuth de los clientes de `vm-core` ni se identificó el commit desplegado. |
| Auth Server — JWKS | Kafka y RabbitMQ pueden iniciar conexiones HTTPS salientes al JWKS configurado; no es una conexión iniciada por Auth Server por cada mensaje. Kafka también puede solicitar token para la identidad interbroker cuando su configuración lo requiera. | Pendiente en producción: endpoint, puerto, CA y ruta permitida desde `vm-data` a `vm-auth`. | El Auth Server expone `/oauth2/jwks` cuando RSA está configurado. El fixture Kafka documenta validación de firma, audience e issuer mediante JWKS. Los brokers deben confiar en HTTPS de Auth Server; esto no aprueba endpoints productivos. |
| RabbitMQ | Clientes configuran `RABBITMQ_HOST`/`RABBITMQ_PORT` y usan AMQPS, validando certificado y hostname del broker. La consulta JWKS del broker es HTTPS independiente. | `5672` observado como listener del host; RabbitMQ informa `amqp/ssl`. | Contenedor `rabbitmq:4.3.7` activo. `rabbitmq-diagnostics listeners` confirmó AMQP sobre TLS. La CA PEM de `traffic-density-processor` y de los cuatro clientes de `vm-app` coincide por huella con la raíz servida. El certificado del broker incluye SAN para `10.42.2.7`, `rabbitmq`, `vm-data` y `localhost`; el código local activa hostname verification. La consulta de certificados no demuestra operaciones AMQPS exitosas ni validación efectiva por cada cliente. El fixture usa `5671` y `8443` solo para pruebas. |
| PostgreSQL | El archivador usa `SPRING_DATASOURCE_URL` JDBC. | `5432` observado en todas las interfaces del host; `SHOW ssl` dentro del contenedor devolvió `off`, por lo que PostgreSQL no ofrece TLS actualmente. | Contenedor `postgres:17-alpine` activo. El fixture de integración publica `127.0.0.1:5433` hacia `5432` del contenedor; ambos son datos de prueba local. |
| Backend del dashboard | `SERVER_PORT` tiene default HTTP de desarrollo `8080`; el navegador usa HTTP/WebSocket same-origin en `/ws/traffic`. | `127.0.0.1:8080` observado en `vm-app`; `curl` HTTP devolvió 302 a `/dashboard.html` y HTTPS/TLS falló con `wrong version number`. Se alcanzó mediante el túnel SSM, no por ingress público. | El backend no negocia TLS directamente en 8080. La demo mostró conexión y mediciones. La terminación TLS pública no se ha aprobado. `nftables` de las cinco VM tiene política input `drop`; la configuración de HTTPS saliente debe revisarse por su alcance fuera del CIDR de la VPC. |
Para tráfico entre VMs, no usar `kafka`, `localhost` ni alias de una red Docker
como destino. El listener Kafka debe anunciar un nombre o dirección privada
alcanzable y coherente con el SAN del certificado; el listener de control KRaft
debe permanecer fuera del alcance de clientes salvo decisión explícita. RabbitMQ
debe aceptar conexiones solo desde roles que tengan flujos aprobados en esta
matriz (`vm-core` y, para consumidores publicados, `vm-app`).

### Aclaración sobre estado de implementación y seguridad

En este checkout existen adaptadores de Kafka para el simulador, el procesador
y el archivador. `docs/kafka-oauth-integration.md` también los describe junto
con la configuración del cliente y los requisitos de las pruebas de integración.
La guía no demuestra por sí sola que la conexión se haya validado en AWS ni
autoriza endpoints productivos. La existencia de código, una prueba unitaria o
una ejecución histórica documentada tampoco demuestra por sí sola la integración
vigente con broker. Steven aprobó la precisión del texto compartido sobre OAuth,
TLS, JWKS y ACLs; esa aprobación no demuestra una integración Kafka vigente ni
aprueba decisiones productivas.

TLS ya se usa en los clientes y fixtures descritos: HTTPS hacia token/JWKS,
SASL_SSL para clientes Kafka y AMQPS para clientes RabbitMQ. Esto no aprueba
certificados, CA, SAN, puertos ni reglas productivas. El fixture RabbitMQ no
exige certificado cliente AMQPS; `verify_none` en ese contexto no significa
desactivar la validación TLS/hostname HTTPS del JWKS. El listener KRaft de
controller del fixture usa TLS mutuo y no es un listener para las otras VMs.

Los scopes OAuth no sustituyen autorización por recursos:

| Broker | Permisos mínimos por identidad (propuesta; no aplicados ni aprobados para producción) |
|---|---|
| Kafka | Simulador: `WRITE`/`DESCRIBE` sobre el tópico y `IDEMPOTENT_WRITE` de clúster si aplica; procesador y archivador: `READ`/`DESCRIBE` sobre el tópico y `READ` en sus grupos. Kafka Streams puede necesitar permisos acotados a tópicos internos y operaciones de clúster; confirmarlos contra la topología real. |
| RabbitMQ | Procesador: `write` solo al exchange de actualizaciones; backend: `read` solo a su cola. Los flujos de alertas/consultas no están en la base publicada; si se aprueban, asignar lectura/escritura solo a las colas/exchanges necesarios. `configure` solo si el servicio debe crear recursos o bindings; no otorgarlo por defecto. |

`KAFKA_OAUTH_SCOPE` es opcional: los scopes OAuth no autorizan operaciones
Kafka; las ACLs del broker son la política de recursos. En RabbitMQ, los scopes
del fixture `smarttraffic-rabbitmq-it` solo corresponden al publicador y lector
de prueba, no a una política productiva.

Los nombres, scopes y recursos `*-it` del fixture RabbitMQ son exclusivos de
pruebas; esa suite valida un publicador y un lector, no los consumidores
productivos de alertas/consultas. La ejecución del 15/11 aceptó un token
firmado por una clave confiable con otro `iss`; se conserva como observación
limitada a ese fixture/configuración/ejecución. No debe afirmarse que esa
configuración valide igualdad de `iss` ni generalizar el resultado a toda
configuración/plugin.

## Autenticación y distribución de claves

| Flujo | Iniciador de conexión de red | Sentido lógico del mensaje | Servicio / protocolo conocido | Propósito | Estado |
|---|---|---|---|---|---|
| Servicio → `auth_server` (solicitud de token) | Cada servicio con cliente OAuth abre conexión hacia `auth_server` en `vm-auth`. | Solicitud OAuth y credenciales: servicio → Auth Server. | HTTPS OAuth `client_credentials`/`client_secret_basic`, endpoint `/oauth2/token`; puerto por definir. | Autenticar la identidad de servicio y solicitar JWT. | El flujo y endpoint están implementados/documentados; host, puerto, TLS/CA, identidades, issuer y audiencias productivos siguen pendientes. |
| `auth_server` → servicio (respuesta de token) | Ocurre en la conexión iniciada por el servicio; no es una conexión nueva desde Auth Server. | JWT emitido: Auth Server → servicio. | Respuesta HTTPS del token endpoint. | Entregar el JWT al cliente autenticado. | Respuesta en la misma conexión. Claims, política final de issuer/audiencias y transporte productivo deben aprobarse. |
| Kafka → Auth Server (JWKS) | El broker Kafka abre conexión saliente a `auth_server` en `vm-auth` para consultar JWKS; el retorno ocurre en esa conexión. | Petición de claves: Kafka → Auth Server; respuesta: Auth Server → Kafka. | HTTPS al endpoint JWKS `/oauth2/jwks` cuando RSA está configurado; puerto por definir. | Obtener claves públicas para validar JWT. | Configurado por el fixture Kafka; ruta, TLS/CA y endpoint productivos pendientes. |
| Kafka → Auth Server (token interbroker, condicional) | El cliente interbroker OAuth de Kafka puede solicitar un token al endpoint de Auth Server. | Solicitud/respuesta de token en la conexión HTTPS iniciada por Kafka. | HTTPS OAuth; depende de la configuración interbroker aprobada. | Autenticar conexiones internas del broker si se mantiene esa configuración. | Fixture local documenta esta identidad; producción no configurada ni aprobada. |
| RabbitMQ → Auth Server (JWKS) | El broker RabbitMQ abre conexión saliente a `auth_server` en `vm-auth`; el retorno ocurre en esa conexión. | Petición de claves: RabbitMQ → Auth Server; respuesta: Auth Server → RabbitMQ. | HTTPS al JWKS configurado; puerto por definir. | Obtener claves públicas para validar JWT de clientes. | Fixture usa esta dependencia; endpoint, TLS/CA y ruta productivos pendientes. |

El Proyecto 12 requiere autenticación de los servicios, SASL/OAUTHBEARER para
Kafka y `rabbitmq_auth_backend_oauth2` para RabbitMQ, con acceso anónimo
prohibido. El código/fixtures actuales usan TLS en los flujos indicados arriba;
la configuración productiva de confianza y la autorización por recurso deben
acordarse antes de crear reglas.

## Servicios auxiliares y administración

| Flujo | Iniciador de conexión de red | Sentido lógico del mensaje | Servicio / protocolo conocido | Propósito | Estado |
|---|---|---|---|---|---|
| VM → DNS | El host o servicio que necesite resolver nombres inicia la consulta hacia el resolver configurado. | Consulta y respuesta DNS. | DNS; transporte, servidor y puerto por definir. | Resolución de nombres, solo si la configuración lo requiere. | Condicional y pendiente; no se asigna resolver. |
| VM → NTP | El host o servicio de tiempo inicia la conexión hacia la fuente de tiempo configurada. | Solicitud y respuesta de sincronización de tiempo. | NTP; servidor, transporte y puerto por definir. | Sincronizar reloj, si se habilita. | Condicional y pendiente; no se asigna servidor. |
| Portátil del presentador → VMs | El portátil inicia la sesión; el SSM Agent de la VM mantiene el canal saliente hacia Systems Manager. | Comandos y respuestas de administración por el canal de SSM. | AWS Systems Manager Session Manager; sin SSH público ni ingress administrativo. | Administrar las VM sin ejecutar microservicios en el portátil. | Se abrió una sesión SSM desde el portátil hacia `vm-app`. No se inspeccionó la política IAM para confirmar que limite el acceso a ese destino; la sesión exitosa no demuestra alcance mínimo. |
| Portátil del presentador → dashboard | El portátil abre port forwarding de Session Manager hacia el listener de `vm-app`; el navegador usa el puerto local del túnel. | HTTP/WebSocket entre el navegador y el backend a través del túnel. | Session Manager port forwarding; no requiere ingress público. | Presentar el dashboard desde el portátil. | Verificado: puerto remoto `8080`, puerto local `18080`; el navegador recibió datos en vivo. Security Groups y `nftables` se inspeccionaron; `vm-app` no tenía ingress y `nftables` aplica `drop` en input. No se probó exposición pública. |
| VM → Internet para SSM y aprovisionamiento | Cada VM inicia conexiones salientes hacia los endpoints de Systems Manager, repositorios de imágenes y repositorios del sistema operativo. | Solicitud de conexión/descarga: VM → Internet; respuesta por la misma conexión. | En Terraform y Security Groups se observó TCP 443; `nftables` en las cinco VM permite TCP 443 a destinos fuera del CIDR de la VPC. | Registrar y administrar las instancias con SSM; descargar imágenes y actualizaciones. | Los Security Groups no filtran por FQDN. La excepción HTTPS está activa en las cinco VM y no limita los destinos a una lista de dominios; se debe decidir si esta salida amplia es necesaria. DNS/NTP y otros destinos opcionales siguen sujetos a aprobación. |

## Uso posterior para reglas `nftables`

El Proyecto 12 exige `nftables` y la política `DEFAULT DROP`. Bloquear por
defecto es obligatorio. Las direcciones, excepciones y reglas concretas no
están determinadas por esa política y deben acordarse antes de implementarlas.
Antes de derivar reglas de esta matriz, el equipo debe completar:

- Direcciones y rangos de origen y destino, sin inventarlos en esta etapa.
- Interfaces y rutas de cada VM, incluyendo tráfico que atraviese Docker.
- Protocolo de transporte, puertos y listeners seguros para cada conexión.
- Para tráfico entre VMs de la VPC, preferir referencias al Security Group del
  rol (`peer_role`) si la topología AWS acordada las permite; no convertirlas
  todavía en reglas literales ni sustituirlas por CIDR privados `/32`. Cualquier
  excepción privada externa —por ejemplo, una VPN— debe aprobarse aparte y
  permanecer deshabilitada por defecto.
- Mantener egress denegado salvo flujos salientes aprobados; no abrir todo el
  tráfico ni reglas generales para hacer funcionar los fixtures.
- Reglas de retorno y estado de conexión. Para Security Groups de AWS, derivar
  egress en el rol que inicia cada flujo e ingress en el rol destino; el
  seguimiento stateful de respuestas no autoriza nuevas conexiones inversas.
  Validar por separado las reglas `nftables` de cada VM y el tráfico Docker.
- Orígenes autorizados para dashboard, WebSocket y administración.
- Necesidad real de DNS, NTP y salida a Internet durante aprovisionamiento.
- Identidades, autenticación, permisos y configuración TLS de cada flujo.

Los nombres `traffic_readings`, `dashboard_updates_queue` y
`alert_updates_queue` aparecen como propuestas en el README. Deben confirmarse
antes de fijarlos en configuración. Las filas de DNS, NTP, SSH y salida a
Internet son condicionales, no autorizaciones de red.
