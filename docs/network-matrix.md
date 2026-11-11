# Matriz de comunicaciones de SmartTraffic

## Alcance y convenciones

Esta matriz complementa el
[overview de arquitectura](./architecture-overview.md) y el
[README](../README.md). Describe los flujos lógicos del diseño actual, no reglas
de firewall ya implementadas ni una configuración de red definitiva.

El README deja direcciones y puertos para acordar. Por ello, no se asignan
valores numéricos en esta matriz. Los protocolos de aplicación se indican solo
cuando aparecen en el diseño; transporte, puertos, listeners seguros y
configuración TLS quedan pendientes de validación.

**Clasificación:**

- **Académico:** requisito expresamente incluido en el enunciado compartido.
- **Propuesta:** flujo o control del diseño del equipo, todavía sujeto a
  aprobación y validación.
- **Pendiente:** falta definir el detalle antes de configurar reglas.

## Flujos entre zonas

| Origen | Destino | Servicio / protocolo conocido | Sentido lógico | Propósito | Identidad / autorización | Estado |
|---|---|---|---|---|---|---|
| `vm-ingest` / `traffic_sensor_simulator` | `vm-data` / Kafka | Kafka; transporte y puerto por definir | Publicación | Enviar lecturas aleatorias a un tópico. `traffic_readings` es el nombre propuesto por el README. | Identidad propia del simulador; mecanismo de broker propuesto en seguridad. | Académico: generación aleatoria e integración de Kafka. Detalles de conexión: propuesta / pendiente. |
| `vm-data` / Kafka | `vm-core` / `traffic_density_processor` | Kafka Streams; transporte y puerto por definir | Consumo de eventos | Procesar lecturas y mantener el estado de tráfico por zona. | Identidad propia del procesador y permisos de consumo propuestos. | Uso de Kafka: académico. Implementación y permisos: propuesta. |
| `vm-data` / Kafka | `vm-core` / `traffic_archiver` | Kafka; transporte y puerto por definir | Consumo de eventos | Entregar lecturas originales para persistencia histórica independiente. | Identidad propia del archiver y permisos de consumo propuestos. | Propuesta. |
| `vm-core` / `traffic_archiver` | `vm-data` / PostgreSQL | Protocolo y puerto por definir | Escritura | Guardar lecturas históricas y registros de auditoría. | Credenciales específicas para `traffic_archiver`, según propuesta. | PostgreSQL forma parte del diseño del equipo; esquema y acceso: pendiente. |
| `vm-core` / `traffic_density_processor` | `vm-data` / RabbitMQ | RabbitMQ; transporte y puerto por definir | Publicación | Publicar estados en el fanout exchange `traffic_updates`. | Identidad del procesador y permisos de publicación propuestos. | Uso de RabbitMQ: académico. Exchange y autorización: propuesta. |
| `vm-data` / RabbitMQ | `vm-app` / `user_dashboard_backend` | RabbitMQ; transporte y puerto por definir | Consumo | Entregar copias desde `dashboard_updates_queue` al backend del dashboard. | Identidad del backend y permisos de consumo propuestos. | Cola propuesta por README; pendiente de aprobación. |
| `vm-data` / RabbitMQ | `vm-app` / `alert_dispatcher` | RabbitMQ; transporte y puerto por definir | Consumo | Entregar copias desde `alert_updates_queue` para actualizar la réplica local y evaluar alertas. | Identidad del dispatcher y permisos de consumo propuestos. | Cola propuesta por README; pendiente de aprobación. |
| `vm-app` / `user_dashboard_backend` | `vm-app` / `dashboard.html` | WebSocket | Actualizaciones hacia el navegador | Mostrar el estado de tráfico en tiempo real. | Exposición del backend y autorización de usuarios por definir. | WebSocket es parte de la propuesta; origen de usuarios, endpoint y puerto pendientes. |
| `vm-app` / cada instancia de `query_client` | `vm-data` / RabbitMQ | RabbitMQ; transporte y puerto por definir | Publicación | Enviar una solicitud a `query_traffic_queue` con `correlationId` y `replyTo`. | Identidad del cliente y permisos de publicación propuestos. | Flujo propuesto en README. |
| `vm-data` / RabbitMQ | `vm-app` / `alert_dispatcher` | RabbitMQ; transporte y puerto por definir | Entrega de solicitud | Entregar consultas de `query_traffic_queue`; el dispatcher consulta su réplica local. | Identidad del dispatcher y permisos de consumo propuestos. | Flujo propuesto en README. |
| `vm-app` / `alert_dispatcher` | `vm-data` / RabbitMQ | RabbitMQ; transporte y puerto por definir | Publicación | Publicar en `query_answers` la respuesta con `correlationId` y routing key asociada a la instancia. | Identidad del dispatcher y permisos de publicación propuestos. | Topic exchange y routing keys: propuesta; bindings pendientes. |
| `vm-data` / RabbitMQ | `vm-app` / instancia solicitante de `query_client` | RabbitMQ; transporte y puerto por definir | Entrega de respuesta | Dirigir la respuesta a una cola independiente de la instancia correspondiente. | Identidad de la instancia y permiso de consumo propuestos. | Cola por instancia indicada por README; nombre y binding pendientes. |

## Identidad y distribución de claves

| Origen | Destino | Servicio / protocolo conocido | Sentido lógico | Propósito | Identidad / autorización | Estado |
|---|---|---|---|---|---|---|
| Cada uno de `traffic_sensor_simulator`, `traffic_density_processor`, `traffic_archiver`, `user_dashboard_backend`, `alert_dispatcher` y `query_client` | `vm-auth` / `auth_server` | Solicitud de token; endpoint, protocolo y puerto por definir | Solicitud de credenciales hacia `auth_server`; JWT de respuesta hacia el servicio solicitante | Obtener identidad de servicio para acceder a componentes protegidos. | `client_id` y `client_secret`; JWT RS256 según propuesta. No aplica a `auth_server` para emitir tokens. | Propuesta del equipo; endpoint, claims, scopes, audiencia, renovación y permisos pendientes. |
| `vm-data` / Kafka | `vm-auth` / JWKS de `auth_server` | JWKS; protocolo y puerto por definir | Solicitud de claves desde Kafka; claves públicas de respuesta | Validar tokens según la propuesta de autenticación de Kafka. | Claves públicas solamente; nunca transferir la clave privada. | Propuesta; endpoint, TLS y mecanismo de validación pendientes. |
| `vm-data` / RabbitMQ | `vm-auth` / JWKS de `auth_server` | JWKS; protocolo y puerto por definir | Solicitud de claves desde RabbitMQ; claves públicas de respuesta | Validar tokens según la propuesta OAuth2 para RabbitMQ. | Claves públicas solamente; nunca transferir la clave privada. | Propuesta; plugin, endpoint, TLS y mecanismo de validación pendientes. |

## Política de filtrado y asuntos por resolver

El diseño del equipo propone reglas `nftables` con denegación por defecto y
permisos mínimos para los flujos aprobados. Esta matriz aún no es una lista de
reglas ejecutables: faltan como mínimo:

- Direcciones IP y rangos de cada VM.
- Puertos y listeners definitivos, incluidos los seguros.
- Protocolo de transporte y configuración TLS por conexión.
- Reglas para conexiones de retorno y tráfico que atraviese redes Docker.
- Origen permitido para la interfaz web y el endpoint WebSocket.
- Accesos de administración autorizados.
- Mecanismo de autenticación, permisos y manejo de secretos de cada servicio.

Los nombres `dashboard_updates_queue` y `alert_updates_queue` son propuestas
del README pendientes de aprobación. El nombre `traffic_readings` también es
propuesto. Las reglas no deben abrir rangos generales para resolver problemas
de conectividad; primero se debe acordar el flujo requerido y actualizar esta
matriz.

La mención de Kafka, RabbitMQ y Docker Compose en el objetivo académico no
define por sí misma puertos ni una topología de red. La inclusión de una fila
de tráfico tampoco confirma que la conexión se haya configurado, protegido o
probado.
