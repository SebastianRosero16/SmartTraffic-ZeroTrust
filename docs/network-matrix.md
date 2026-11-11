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

**Clasificación:** requisito explícito del Proyecto 12; propuesta del equipo;
o detalle pendiente de especificación. La inclusión de un flujo no significa
que se haya configurado o probado.

## Flujos de aplicación

| Flujo | Iniciador de conexión de red | Sentido lógico del mensaje | Servicio / protocolo conocido | Propósito | Estado |
|---|---|---|---|---|---|
| Simulador → Kafka | `traffic_sensor_simulator` abre conexión hacia Kafka en `vm-data`. | Lecturas: simulador → tópico Kafka. | Kafka; transporte y puerto por definir. | Publicar lecturas aleatorias; `traffic_readings` es nombre propuesto por README. | Kafka y fuente aleatoria: requisito. Nombre y parámetros: pendientes. |
| Kafka → procesador | `traffic_density_processor` abre conexión hacia Kafka en `vm-data`. | Lecturas: Kafka → procesador. | Kafka Streams; transporte y puerto por definir. | Consumir lecturas y calcular estado por zona. | Kafka Streams: requisito. Claves, grupos y parámetros: pendientes. |
| Kafka → archivador | `traffic_archiver` abre conexión hacia Kafka en `vm-data`. | Lecturas originales: Kafka → archivador. | Kafka; transporte y puerto por definir. | Consumir lecturas en paralelo para archivarlas. | Kafka: requisito; grupo y parámetros: pendientes. |
| Archivador → PostgreSQL | `traffic_archiver` abre conexión hacia PostgreSQL en `vm-data`. | Escrituras históricas: archivador → PostgreSQL. | Protocolo y puerto por definir. | Guardar lecturas históricas y auditoría según el esquema aprobado. | PostgreSQL: requisito; esquema y credenciales: pendientes. |
| Procesador → RabbitMQ | `traffic_density_processor` abre conexión hacia RabbitMQ en `vm-data`. | Actualizaciones: procesador → exchange `traffic_updates`. | RabbitMQ; transporte y puerto por definir. | Publicar estados calculados para consumidores independientes. | RabbitMQ: requisito; exchange y autorización: propuesta. |
| RabbitMQ → backend del dashboard | `user_dashboard_backend` abre conexión hacia RabbitMQ en `vm-data`. | Actualizaciones: RabbitMQ → backend, desde `dashboard_updates_queue`. | RabbitMQ; transporte y puerto por definir. | Entregar actualizaciones para presentación. | Cola propuesta por README; pendiente de aprobación. |
| RabbitMQ → dispatcher de alertas | `alert_dispatcher` abre conexión hacia RabbitMQ en `vm-data`. | Actualizaciones: RabbitMQ → dispatcher, desde `alert_updates_queue`. | RabbitMQ; transporte y puerto por definir. | Actualizar la réplica local y evaluar alertas. | Cola propuesta por README; pendiente de aprobación. |
| Backend → dashboard web | El navegador que carga `dashboard.html` abre la conexión WebSocket hacia `user_dashboard_backend`. | Actualizaciones: backend → navegador. | WebSocket; endpoint, transporte y puerto por definir. | Presentar tráfico en tiempo real. | Interfaz y visualización: diseño del equipo; endpoint y exposición: pendientes. |
| Cliente de consulta → RabbitMQ | Cada `query_client` abre conexión hacia RabbitMQ en `vm-data`. | Solicitud: cliente → `query_traffic_queue`; incluye `correlationId` y `replyTo`. | RabbitMQ; transporte y puerto por definir. | Enviar una consulta de usuario. | Flujo propuesto en README; identificadores y formato pendientes. |
| RabbitMQ → dispatcher de consultas | `alert_dispatcher` abre conexión hacia RabbitMQ en `vm-data`. | Solicitud: RabbitMQ → dispatcher. | RabbitMQ; transporte y puerto por definir. | Entregar consulta para responder desde la réplica local. | Flujo propuesto en README. |
| Dispatcher → RabbitMQ | `alert_dispatcher` abre conexión hacia RabbitMQ en `vm-data`. | Respuesta: dispatcher → Topic Exchange `query_answers`, con `correlationId` y routing key por instancia. | RabbitMQ; transporte y puerto por definir. | Publicar la respuesta correlacionada. | Exchange y enrutamiento propuestos; binding pendiente. |
| RabbitMQ → cliente de consulta | Cada `query_client` abre conexión hacia RabbitMQ en `vm-data`. | Respuesta: RabbitMQ → cola propia de la instancia → cliente. | RabbitMQ; transporte y puerto por definir. | Entregar la respuesta únicamente a quien hizo la solicitud. | Cola independiente por instancia propuesta; nombre y binding pendientes. |

## Autenticación y distribución de claves

| Flujo | Iniciador de conexión de red | Sentido lógico del mensaje | Servicio / protocolo conocido | Propósito | Estado |
|---|---|---|---|---|---|
| Servicio → `auth_server` (solicitud de token) | El microservicio solicitante abre conexión hacia `auth_server` en `vm-auth`. | Credenciales y solicitud: servicio → auth server. | Endpoint, protocolo y puerto por definir. | Autenticar la identidad de servicio. | Autenticación: requisito. Endpoint, credenciales y formato: pendientes. |
| `auth_server` → servicio (respuesta de token) | Ocurre en la conexión iniciada por el servicio solicitante; no es una conexión nueva desde `auth_server`. | Token emitido: auth server → servicio. | Respuesta a la solicitud anterior. | Devolver credencial autenticada al solicitante. | Autenticación: requisito. JWT y RS256 son detalles propuestos, no asumidos como requisito explícito. |
| Kafka → JWKS de `auth_server` | El mecanismo validador de Kafka abre conexión hacia `auth_server` en `vm-auth`. | Solicitud JWKS: Kafka → auth server; claves públicas: auth server → Kafka. | JWKS, si se adopta; endpoint, protocolo y puerto por definir. | Obtener claves públicas para validar tokens. | JWKS y mecanismo de validación son propuesta; endpoint y TLS pendientes. |
| RabbitMQ → JWKS de `auth_server` | El mecanismo validador de RabbitMQ abre conexión hacia `auth_server` en `vm-auth`. | Solicitud JWKS: RabbitMQ → auth server; claves públicas: auth server → RabbitMQ. | JWKS, si se adopta; endpoint, protocolo y puerto por definir. | Obtener claves públicas para validar tokens. | JWKS y plugin OAuth2 son propuesta; endpoint y TLS pendientes. |

Las conexiones de los servicios a Kafka, RabbitMQ y PostgreSQL deben usar la
autenticación y autorización acordadas. Los mecanismos concretos —SASL/
OAUTHBEARER, ACL, OAuth2, credenciales de base de datos y TLS— deben
confirmarse; no se deducen como detalles obligatorios solo por el requisito de
autenticación.

## Servicios auxiliares y administración

| Flujo | Iniciador de conexión de red | Sentido lógico del mensaje | Servicio / protocolo conocido | Propósito | Estado |
|---|---|---|---|---|---|
| VM → DNS | El host o servicio que necesite resolver nombres inicia la consulta hacia el resolver configurado. | Consulta y respuesta DNS. | DNS; transporte, servidor y puerto por definir. | Resolución de nombres, solo si la configuración lo requiere. | Condicional y pendiente; no se asigna resolver. |
| VM → NTP | El host o servicio de tiempo inicia la conexión hacia la fuente de tiempo configurada. | Solicitud y respuesta de sincronización de tiempo. | NTP; servidor, transporte y puerto por definir. | Sincronizar reloj, si se habilita. | Condicional y pendiente; no se asigna servidor. |
| Equipo de administración → VM | El equipo o host autorizado inicia la conexión hacia la VM. | Comandos de administración: host → VM; respuestas: VM → host. | SSH; puerto y origen por definir. | Administración remota, solo si se requiere y autoriza. | Condicional y pendiente; no se establecen reglas ni redes de origen. |
| VM → Internet para aprovisionamiento | La VM que requiera paquetes o imágenes inicia la conexión hacia el destino externo. | Solicitud de descarga: VM → Internet; respuesta: Internet → VM. | Protocolo y destinos por definir según los repositorios necesarios. | Aprovisionar o actualizar software, si se decide hacerlo desde la VM. | Condicional y pendiente; no se permite acceso general por defecto. |

## Uso posterior para reglas `nftables`

El Proyecto 12 exige el uso de `nftables`; la política de denegación por
defecto y la lista concreta de excepciones son decisiones de diseño que deben
aprobarse. Antes de derivar reglas de esta matriz, el equipo debe completar:

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
