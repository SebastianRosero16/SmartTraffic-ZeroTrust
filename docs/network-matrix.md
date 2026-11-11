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

## Flujos de aplicación

| Flujo | Iniciador de conexión de red | Sentido lógico del mensaje | Servicio / protocolo conocido | Propósito | Estado |
|---|---|---|---|---|---|
| Simulador → Kafka | `traffic_sensor_simulator` abre conexión hacia Kafka en `vm-data`. | Lecturas: simulador → tópico Kafka. | Kafka con SASL/OAUTHBEARER; puerto por definir. | Publicar lecturas aleatorias; `traffic_readings` es nombre propuesto por README. | Kafka, datos aleatorios y SASL/OAUTHBEARER: requisitos. Tópico, credenciales, IP y puerto: pendientes. |
| Kafka → procesador | `traffic_density_processor` abre conexión hacia Kafka en `vm-data`. | Lecturas: Kafka → procesador. | Kafka Streams/KTable con SASL/OAUTHBEARER; puerto por definir. | Consumir lecturas y calcular estado por zona. | Kafka Streams/KTable y autenticación SASL/OAUTHBEARER: requisitos. Claves, grupos, IP y puerto: pendientes. |
| Kafka → archivador | `traffic_archiver` abre conexión hacia Kafka en `vm-data`. | Lecturas originales: Kafka → archivador. | Kafka con SASL/OAUTHBEARER; puerto por definir. | Consumir lecturas en paralelo para archivarlas. | Kafka y SASL/OAUTHBEARER: requisitos; grupo, IP y puerto: pendientes. |
| Archivador → PostgreSQL | `traffic_archiver` abre conexión hacia PostgreSQL en `vm-data`. | Escrituras históricas: archivador → PostgreSQL. | PostgreSQL con Spring Data JPA/Hibernate; puerto por definir. | Guardar lecturas históricas y auditoría según el esquema aprobado. | PostgreSQL y Spring Data JPA/Hibernate: requisitos; esquema, credenciales, IP y puerto: pendientes. |
| Procesador → RabbitMQ | `traffic_density_processor` abre conexión hacia RabbitMQ en `vm-data`. | Actualizaciones: procesador → exchange `traffic_updates`. | RabbitMQ con `rabbitmq_auth_backend_oauth2`; puerto por definir. | Publicar estados calculados para consumidores independientes. | RabbitMQ y plugin OAuth2 con acceso anónimo prohibido: requisitos. Exchange, permisos, IP y puerto: detalles por definir. |
| RabbitMQ → backend del dashboard | `user_dashboard_backend` abre conexión hacia RabbitMQ en `vm-data`. | Actualizaciones: RabbitMQ → backend, desde `dashboard_updates_queue`. | RabbitMQ con `rabbitmq_auth_backend_oauth2`; puerto por definir. | Entregar actualizaciones para presentación. | Plugin OAuth2 y prohibición del acceso anónimo: requisitos. Nombre de cola, permisos, IP y puerto: pendientes. |
| RabbitMQ → dispatcher de alertas | `alert_dispatcher` abre conexión hacia RabbitMQ en `vm-data`. | Actualizaciones: RabbitMQ → dispatcher, desde `alert_updates_queue`. | RabbitMQ con `rabbitmq_auth_backend_oauth2`; puerto por definir. | Actualizar la réplica local y evaluar alertas. | Plugin OAuth2 y prohibición del acceso anónimo: requisitos. Nombre de cola, permisos, IP y puerto: pendientes. |
| Backend → dashboard web | El navegador que carga `dashboard.html` abre la conexión WebSocket hacia `user_dashboard_backend`. | Actualizaciones: backend → navegador. | WebSocket; endpoint, transporte y puerto por definir. | Presentar tráfico en tiempo real. | Interfaz y visualización: diseño del equipo; endpoint y exposición: pendientes. |
| Cliente de consulta → RabbitMQ | Cada `query_client` abre conexión hacia RabbitMQ en `vm-data`. | Solicitud: cliente → `query_traffic_queue`; incluye `correlationId` y `replyTo`. | RabbitMQ con `rabbitmq_auth_backend_oauth2`; puerto por definir. | Enviar una consulta de usuario. | RabbitMQ, OAuth2 y prohibición del acceso anónimo: requisitos. Nombres, identificadores, routing key, IP y puerto: pendientes. |
| RabbitMQ → dispatcher de consultas | `alert_dispatcher` abre conexión hacia RabbitMQ en `vm-data`. | Solicitud: RabbitMQ → dispatcher. | RabbitMQ con `rabbitmq_auth_backend_oauth2`; puerto por definir. | Entregar consulta para responder desde la réplica local. | RabbitMQ, OAuth2 y prohibición del acceso anónimo: requisitos; detalles de cola y conexión pendientes. |
| Dispatcher → RabbitMQ | `alert_dispatcher` abre conexión hacia RabbitMQ en `vm-data`. | Respuesta: dispatcher → Topic Exchange `query_answers`, con `correlationId` y routing key por instancia. | RabbitMQ con `rabbitmq_auth_backend_oauth2`; puerto por definir. | Publicar la respuesta correlacionada. | RabbitMQ, OAuth2 y prohibición del acceso anónimo: requisitos. Exchange, routing keys, binding, IP y puerto: pendientes. |
| RabbitMQ → cliente de consulta | Cada `query_client` abre conexión hacia RabbitMQ en `vm-data`. | Respuesta: RabbitMQ → cola propia de la instancia → cliente. | RabbitMQ con `rabbitmq_auth_backend_oauth2`; puerto por definir. | Entregar la respuesta únicamente a quien hizo la solicitud. | RabbitMQ, OAuth2 y prohibición del acceso anónimo: requisitos. Nombre de cola, binding, IP y puerto: pendientes. |

## Autenticación y distribución de claves

| Flujo | Iniciador de conexión de red | Sentido lógico del mensaje | Servicio / protocolo conocido | Propósito | Estado |
|---|---|---|---|---|---|
| Servicio → `auth_server` (solicitud de token) | El microservicio solicitante abre conexión hacia `auth_server` en `vm-auth`. | Credenciales y solicitud: servicio → auth server. | Endpoint, protocolo y puerto por definir. | Autenticar la identidad del servicio y solicitar su JWT. | `auth_server`, JWT y autenticación: requisitos. Endpoint, claims, credenciales y formato exacto: pendientes. |
| `auth_server` → servicio (respuesta de token) | Ocurre en la conexión iniciada por el servicio solicitante; no es una conexión nueva desde `auth_server`. | JWT emitido: auth server → servicio. | Respuesta a la solicitud anterior; firma y transporte por definir. | Entregar el JWT al servicio autenticado. | JWT: requisito. RS256, JWKS, endpoint, claims y TLS no fijados: pendientes o propuestas. |
| Kafka → JWKS de `auth_server` | Solo si se adopta JWKS, el mecanismo validador de Kafka abre conexión hacia `auth_server` en `vm-auth`. | Solicitud de claves: Kafka → auth server; respuesta: auth server → Kafka. | JWKS; endpoint, protocolo y puerto por definir. | Obtener claves públicas para validar tokens, si esta propuesta se adopta. | JWKS no especificado por el requisito; flujo condicional pendiente. |
| RabbitMQ → JWKS de `auth_server` | Solo si se adopta JWKS, el mecanismo validador de RabbitMQ abre conexión hacia `auth_server` en `vm-auth`. | Solicitud de claves: RabbitMQ → auth server; respuesta: auth server → RabbitMQ. | JWKS; endpoint, protocolo y puerto por definir. | Obtener claves públicas para validar tokens, si esta propuesta se adopta. | JWKS no especificado por el requisito; flujo condicional pendiente. |

El Proyecto 12 requiere autenticación de los servicios, SASL/OAUTHBEARER para
Kafka y `rabbitmq_auth_backend_oauth2` para RabbitMQ, con acceso anónimo
prohibido. TLS y los detalles de autorización por recurso no deben darse por
sentados si no están fijados en el enunciado.

## Servicios auxiliares y administración

| Flujo | Iniciador de conexión de red | Sentido lógico del mensaje | Servicio / protocolo conocido | Propósito | Estado |
|---|---|---|---|---|---|
| VM → DNS | El host o servicio que necesite resolver nombres inicia la consulta hacia el resolver configurado. | Consulta y respuesta DNS. | DNS; transporte, servidor y puerto por definir. | Resolución de nombres, solo si la configuración lo requiere. | Condicional y pendiente; no se asigna resolver. |
| VM → NTP | El host o servicio de tiempo inicia la conexión hacia la fuente de tiempo configurada. | Solicitud y respuesta de sincronización de tiempo. | NTP; servidor, transporte y puerto por definir. | Sincronizar reloj, si se habilita. | Condicional y pendiente; no se asigna servidor. |
| Equipo de administración → VM | El equipo o host autorizado inicia la conexión hacia la VM. | Comandos de administración: host → VM; respuestas: VM → host. | SSH; puerto y origen por definir. | Administración remota, solo si se requiere y autoriza. | Condicional y pendiente; no se establecen reglas ni redes de origen. |
| VM → Internet para aprovisionamiento | La VM que requiera paquetes o imágenes inicia la conexión hacia el destino externo. | Solicitud de descarga: VM → Internet; respuesta: Internet → VM. | Protocolo y destinos por definir según los repositorios necesarios. | Aprovisionar o actualizar software, si se decide hacerlo desde la VM. | Condicional y pendiente; no se permite acceso general por defecto. |

## Uso posterior para reglas `nftables`

El Proyecto 12 exige `nftables` y la política `DEFAULT DROP`. Bloquear por
defecto es obligatorio. Las direcciones, excepciones y reglas concretas no
están determinadas por esa política y deben acordarse antes de implementarlas.
Antes de derivar reglas de esta matriz, el equipo debe completar:

- Direcciones y rangos de origen y destino, sin inventarlos en esta etapa.
- Interfaces y rutas de cada VM, incluyendo tráfico que atraviese Docker.
- Protocolo de transporte, puertos y listeners seguros para cada conexión.
- Reglas de retorno y estado de conexión.
- Orígenes autorizados para dashboard, WebSocket y administración.
- Necesidad real de DNS, NTP y salida a Internet durante aprovisionamiento.
- Identidades, autenticación, permisos y configuración TLS de cada flujo.

Los nombres `traffic_readings`, `dashboard_updates_queue` y
`alert_updates_queue` aparecen como propuestas en el README. Deben confirmarse
antes de fijarlos en configuración. Las filas de DNS, NTP, SSH y salida a
Internet son condicionales, no autorizaciones de red.
