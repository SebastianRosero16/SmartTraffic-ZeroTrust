# Arquitectura de SmartTraffic – Zona Cero (Zero Trust)

## Alcance y estado

SmartTraffic propone simular lecturas aleatorias de sensores de tráfico,
calcular el estado de cada zona, distribuir actualizaciones y alertas, responder
consultas y conservar un histórico. La descripción de este documento está
alineada con el [README](../README.md) vigente en `develop`.

El enunciado académico compartido exige generación aleatoria de datos e
integración de Apache Kafka y RabbitMQ en una solución distribuida orquestada
con Docker Compose. La arquitectura detallada a continuación —incluida la
distribución en cinco VMs, los siete microservicios, la autenticación y la
segmentación— es el diseño propuesto por el equipo, no una afirmación de que
cada detalle sea requisito expreso del enunciado ni de que ya esté implementado.

## Cinco zonas de despliegue

| VM / zona | Componentes propuestos | Responsabilidad |
|---|---|---|
| `vm-auth` — autoridad | `auth_server` | Autenticar identidades de servicio, emitir JWT y publicar claves públicas JWKS. |
| `vm-data` — datos | Apache Kafka, RabbitMQ y PostgreSQL, en contenedores independientes | Alojar mensajería y persistencia; limitar el acceso a los servicios autorizados. |
| `vm-ingest` — ingesta | `traffic_sensor_simulator` | Generar lecturas aleatorias de sensores y publicarlas en Kafka. |
| `vm-core` — procesamiento | `traffic_density_processor`, `traffic_archiver` | Calcular el estado de tráfico por zona, publicar actualizaciones y guardar lecturas originales en PostgreSQL. |
| `vm-app` — aplicación | `user_dashboard_backend`, `alert_dispatcher`, `query_client` y `dashboard.html` | Servir el dashboard en tiempo real, gestionar alertas y responder consultas. `dashboard.html` es la interfaz web, no un microservicio. |

Cada VM tendrá su despliegue Docker. El equipo propone Docker Compose por zona;
no se plantea una sola red Docker plana que conecte indiscriminadamente todas
las VMs. La configuración de redes, recursos y aprovisionamiento sigue
pendiente de validación.

## Responsabilidades de los servicios

- **`auth_server`:** autenticar clientes de servicio, emitir JWT firmados con
  RS256 y publicar claves públicas mediante JWKS. La clave privada permanece
  únicamente bajo su control.
- **`traffic_sensor_simulator`:** generar lecturas aleatorias identificadas
  por sensor y zona, validarlas y enviarlas a Kafka.
- **`traffic_density_processor`:** consumir lecturas de Kafka, procesarlas con
  Kafka Streams, mantener estado por zona mediante una KTable, calcular
  densidad y estado, y publicar actualizaciones en RabbitMQ.
- **`traffic_archiver`:** consumir en paralelo las lecturas originales de
  Kafka y persistirlas en PostgreSQL. No depende de RabbitMQ ni del dashboard.
- **`user_dashboard_backend`:** consumir actualizaciones de RabbitMQ y enviarlas
  al navegador mediante WebSocket.
- **`alert_dispatcher`:** consumir actualizaciones, mantener una réplica local
  del estado por zona, detectar cambios de interés y procesar consultas a
  partir de esa réplica.
- **`query_client`:** simular consultas, asignar identificadores de correlación
  y recibir las respuestas destinadas a cada instancia.
- **`dashboard.html`:** mostrar en tiempo real los datos recibidos por el
  backend del dashboard.

## Flujos de mensajes

### Ingesta, procesamiento y distribución

1. `traffic_sensor_simulator` genera lecturas aleatorias y las publica en un
   tópico de Kafka. El nombre definitivo del tópico queda por aprobar; el
   README propone `traffic_readings`.
2. `traffic_density_processor` consume las lecturas mediante Kafka Streams y
   mantiene una KTable por zona. Los detalles de claves, agregaciones y
   ventanas quedan para el diseño detallado.
3. El procesador publica estados consolidados en el fanout exchange RabbitMQ
   `traffic_updates`.
4. El exchange distribuye cada actualización a colas independientes para el
   dashboard y alertas. El README muestra `dashboard_updates_queue` y
   `alert_updates_queue` como propuestas pendientes de aprobación.
5. `user_dashboard_backend` consume la cola del dashboard y transmite los
   datos a `dashboard.html` por WebSocket. `alert_dispatcher` consume la cola
   de alertas, actualiza su réplica local y evalúa las alertas.

### Persistencia histórica

En paralelo al procesamiento, `traffic_archiver` consume las lecturas originales
de Kafka con un grupo de consumidores independiente, las valida y las guarda
en PostgreSQL. La conservación, el esquema y la protección del histórico
requieren decisiones de diseño y pruebas.

### Consultas y respuestas correlacionadas

1. Cada instancia de `query_client` publica una solicitud en la Work Queue
   `query_traffic_queue` con `correlationId` y el destino de respuesta en
   `replyTo`.
2. `alert_dispatcher` consume la solicitud y obtiene el estado solicitado de
   su réplica local; no consulta PostgreSQL directamente.
3. El dispatcher publica la respuesta en el Topic Exchange `query_answers`,
   conservando el `correlationId` y usando una routing key asociada a la
   instancia solicitante.
4. Cada instancia de `query_client` recibe respuestas en su propia cola. Se
   requieren colas de respuesta independientes, un identificador de instancia
   y manejo de espera y fallos; el nombre y binding exactos quedan por definir.

## Identidad y seguridad entre servicios

El diseño del equipo propone identidades separadas para cada servicio y
autenticación de servicio a servicio mediante JWT RS256. `auth_server` publica
las claves públicas por JWKS; los consumidores validan tokens según claims,
audiencia, expiración y permisos que se definan. La clave privada no debe
copiarse a otros servicios.

Para Kafka, el README propone SASL/OAUTHBEARER y ACL. Para RabbitMQ propone el
plugin OAuth2, autenticación de conexión y permisos sobre exchanges y colas. La
autenticación no concede por sí misma acceso a todos los recursos. Para
PostgreSQL se proponen credenciales específicas para `traffic_archiver`;
los JWT de los brokers no sustituyen esa autenticación.

TLS y la aplicación efectiva de permisos se deben configurar y comprobar. La
presencia de estos mecanismos en el diseño no significa que estén
implementados. No deben guardarse claves privadas, tokens ni credenciales
reales en Git.

## Segmentación, Docker y recursos

La arquitectura propone cinco zonas VM independientes y reglas de `nftables`
con denegación por defecto: permitir solo los flujos autorizados y bloquear el
resto. Las reglas definitivas dependen de la matriz de comunicaciones y de la
validación del tráfico que atraviese las redes Docker. La
[matriz de comunicaciones](./network-matrix.md) registra los flujos conocidos
y deja sin especificar los puertos no acordados.

Docker y Docker Compose permiten desplegar y reproducir los componentes por
VM, pero no reemplazan la segmentación entre VMs ni la autorización. Se propone
aislar los servicios, ejecutar contenedores de aplicación sin privilegios y
establecer límites de CPU y memoria según los recursos reales. Los valores,
réplicas y comportamiento del entorno deben validarse; declararlos en archivos
no prueba que se apliquen.

Vagrant y/o Ansible se evaluarán para aprovisionar las máquinas y configurarlas.
Vagrant para crear VMs y Ansible para configurarlas es una propuesta, no una
decisión aprobada. La disponibilidad del hipervisor y la capacidad del equipo
deben comprobarse antes de instalar o desplegar.

## Diagrama lógico

El diagrama refleja la arquitectura propuesta y los nombres que aparecen en el
README. No especifica direcciones IP, puertos ni una topología de red
definitiva.

```mermaid
flowchart LR
    subgraph AUTH["vm-auth - autoridad"]
        AS["auth_server<br/>JWT RS256 / JWKS"]
    end

    subgraph INGEST["vm-ingest - ingesta"]
        SIM["traffic_sensor_simulator"]
    end

    subgraph CORE["vm-core - procesamiento"]
        PROC["traffic_density_processor<br/>Kafka Streams / KTable"]
        ARCH["traffic_archiver"]
    end

    subgraph DATA["vm-data - datos"]
        KAFKA["Apache Kafka"]
        subgraph RMQ["RabbitMQ"]
            UPDATES["Fanout Exchange<br/>traffic_updates"]
            DASHQ["dashboard_updates_queue<br/>(propuesta)"]
            ALERTQ["alert_updates_queue<br/>(propuesta)"]
            QUERYQ["Work Queue<br/>query_traffic_queue"]
            ANSWERS["Topic Exchange<br/>query_answers"]
            REPLYQ["Colas de respuesta<br/>una por instancia"]
        end
        PG["PostgreSQL"]
    end

    subgraph APP["vm-app - aplicación"]
        DASH["user_dashboard_backend"]
        ALERT["alert_dispatcher<br/>réplica local de estados"]
        QUERY["query_client<br/>una cola de respuesta por instancia"]
        WEB["dashboard.html"]
    end

    SIM -->|"lecturas aleatorias"| KAFKA
    KAFKA -->|"lecturas para procesamiento"| PROC
    KAFKA -->|"lecturas originales"| ARCH
    ARCH -->|"persistencia histórica"| PG
    PROC -->|"estados por zona"| UPDATES
    UPDATES --> DASHQ
    UPDATES --> ALERTQ
    DASHQ --> DASH
    ALERTQ --> ALERT
    DASH -->|"WebSocket"| WEB
    QUERY -->|"solicitud: replyTo + correlationId"| QUERYQ
    QUERYQ -->|"solicitud"| ALERT
    ALERT -->|"respuesta correlacionada"| ANSWERS
    ANSWERS --> REPLYQ
    REPLYQ -->|"respuesta a la instancia solicitante"| QUERY

    SIM -. "solicita identidad / recibe JWT" .-> AS
    PROC -. "solicita identidad / recibe JWT" .-> AS
    ARCH -. "solicita identidad / recibe JWT" .-> AS
    DASH -. "solicita identidad / recibe JWT" .-> AS
    ALERT -. "solicita identidad / recibe JWT" .-> AS
    QUERY -. "solicita identidad / recibe JWT" .-> AS
    KAFKA -. "obtiene claves públicas JWKS" .-> AS
    RMQ -. "obtiene claves públicas JWKS" .-> AS
```

## Requisitos, propuestas y decisiones pendientes

### Requisitos del enunciado compartido

- La fuente de datos es aleatoria.
- Se deben integrar Apache Kafka y RabbitMQ en una arquitectura distribuida
  orquestada con Docker Compose.
- La entrega incluye el repositorio y una demostración funcional, con
  explicación concisa de arquitectura y decisiones de diseño.

### Propuestas del diseño del equipo

- Separar el sistema en cinco VMs con los componentes de la tabla anterior.
- Implementar siete microservicios Java/Spring Boot y `dashboard.html`.
- Usar Kafka Streams y una KTable para calcular estado por zona.
- Distribuir actualizaciones con el fanout `traffic_updates` y usar los nombres
  de colas indicados en el diagrama como referencia propuesta.
- Responder consultas con `alert_dispatcher`, una réplica local y respuestas
  correlacionadas por instancia.
- Usar JWT RS256/JWKS, SASL/OAUTHBEARER y ACL en Kafka, OAuth2 en RabbitMQ,
  credenciales específicas en PostgreSQL, TLS y reglas `nftables`.
- Evaluar Vagrant y/o Ansible y aplicar límites de recursos por contenedor.

Estas propuestas se mantienen sujetas a aprobación y validación del equipo.

### Decisiones pendientes

- Aprobar nombres y contratos de topics, mensajes, colas, bindings y routing
  keys, incluidos los nombres de las colas de respuesta por instancia.
- Definir algoritmo, claves, agregaciones, ventanas y umbrales de densidad.
- Precisar credenciales de servicio, claims, audiencias, scopes, expiración,
  renovación y permisos de JWT.
- Determinar configuración TLS y listeners seguros para Kafka, RabbitMQ,
  PostgreSQL, autenticación, JWKS y dashboard.
- Acordar direcciones, puertos y sentidos concretos de la matriz de red antes
  de escribir reglas definitivas.
- Definir esquema, auditoría, retención, copias de seguridad e integridad del
  histórico.
- Elegir proveedor de virtualización y confirmar compatibilidad con Vagrant,
  Ansible y los recursos disponibles.
- Medir y acordar límites de CPU y memoria, número de réplicas, monitoreo,
  registros, reintentos, idempotencia y recuperación.

## Riesgos técnicos iniciales

- Cinco VMs y varios brokers o servicios pueden superar los recursos del host.
- Una política de firewall incompleta puede bloquear flujos válidos o permitir
  accesos excesivos, especialmente a través de redes Docker.
- La exposición de secretos o de la clave privada RS256 comprometería la
  identidad de los servicios.
- Sin decisiones de entrega, reintentos e idempotencia pueden producirse
  mensajes perdidos o duplicados.
- El procesamiento y la persistencia paralelos pueden observar o conservar
  eventos en momentos distintos.
- Réplicas locales múltiples de `alert_dispatcher` requieren consistencia para
  responder consultas correctamente.
- La falta de monitoreo y registros correlacionados entre zonas complica la
  integración y el diagnóstico.

## Estado

Este documento describe el diseño inicial propuesto. No confirma que se hayan
creado o probado VMs, contenedores, servicios, reglas de firewall ni
automatizaciones.
