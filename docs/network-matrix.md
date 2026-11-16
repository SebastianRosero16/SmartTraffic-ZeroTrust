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

**Estado de revisión:** Steven aprobó la precisión del texto compartido para
Auth, Kafka y RabbitMQ; Héctor aprobó los flujos lógicos, pero no la matriz
completa como base de producción. La PR #23 de infraestructura se fusionó en
`develop` (`bdff99f2978f26ce8683c2f069a9a6b047925cd3`). Para la demostración se
decidió US East (Ohio), `us-east-2`, una AZ, cinco subredes con IPv4 públicas
dinámicas, administración por Session Manager y port forwarding de Session
Manager para el dashboard, sin SSH ni ingress público. La cuenta muestra
`us-east-2a`, `us-east-2b` y `us-east-2c` disponibles; la AZ final se elegirá
tras comprobar los tipos de instancia. Siguen pendientes los CIDR, la identidad
IAM individual de Héctor, sus permisos, el inventario y los listeners/puertos
de producción.

**Alcance actual de Terraform:** `network_rules` está vacío por defecto. La
única regla de salida automática es TCP 443 para SSM, imágenes y actualizaciones;
no hay reglas de ingress ni reglas de aplicación entre roles. Por tanto, la
infraestructura actual todavía no habilita comunicación de aplicación entre
las cinco VM. Se deberán añadir las reglas de ingress y egress para Auth,
Kafka, RabbitMQ, PostgreSQL y el dashboard cuando se confirmen sus listeners,
puertos y flujos iniciadores. Las filas de esta matriz describen requisitos
lógicos; no significan que esos flujos ya estén habilitados.

## Disponibilidad de los servicios

La base publicada `develop` (`bdff99f2978f26ce8683c2f069a9a6b047925cd3`) aún no
incluye `alert-dispatcher` ni `query-client`; los módulos se proponen en la PR
de integración actual y requieren revisión antes de estar disponibles en un
clon limpio. El round-trip de alertas y consultas pasó contra el fixture
RabbitMQ desechable; eso no valida la topología ni las identidades productivas.
En el checkout de trabajo, `mvn -B test` ejecutó 100 casos: 96 aprobados y 4
pruebas de integración omitidas por requerir servicios externos. Ese resultado
anterior no sustituye la validación con brokers.

## Flujos de aplicación

| Flujo | Iniciador de conexión de red | Sentido lógico del mensaje | Servicio / protocolo conocido | Propósito | Estado |
|---|---|---|---|---|---|
| Simulador → Kafka | `traffic_sensor_simulator` abre conexión hacia Kafka en `vm-data`. | Lecturas: simulador → tópico Kafka. | Kafka con SASL_SSL/OAUTHBEARER; puerto por definir. | Publicar lecturas aleatorias; `traffic_readings` es el tópico documentado para pruebas. | El adaptador y la configuración OAuth existen en el checkout; tópico/ACL de producción, identidad, IP, listener y puerto siguen pendientes. |
| Kafka → procesador | `traffic_density_processor` abre conexión hacia Kafka en `vm-data`. | Lecturas: Kafka → procesador. | Kafka Streams/KTable con SASL_SSL/OAUTHBEARER; puerto por definir. | Consumir lecturas y calcular estado por zona. | Adaptador en el checkout; grupo/ACL, identidad, IP, listener y puerto de producción pendientes. |
| Kafka → archivador | `traffic_archiver` abre conexión hacia Kafka en `vm-data`. | Lecturas originales: Kafka → archivador. | Kafka con SASL_SSL/OAUTHBEARER; puerto por definir. | Consumir lecturas en paralelo para archivarlas. | Adaptador en el checkout; grupo/ACL, identidad, IP, listener y puerto de producción pendientes. |
| Archivador → PostgreSQL | `traffic_archiver` abre conexión hacia PostgreSQL en `vm-data`. | Escrituras históricas: archivador → PostgreSQL. | PostgreSQL con Spring Data JPA/Hibernate; puerto por definir. | Guardar lecturas históricas y auditoría según el esquema aprobado. | PostgreSQL y Spring Data JPA/Hibernate: requisitos; esquema, credenciales, IP y puerto: pendientes. |
| Procesador → RabbitMQ | `traffic_density_processor` abre conexión hacia RabbitMQ en `vm-data`. | Actualizaciones: procesador → exchange `traffic_updates`. | RabbitMQ con `rabbitmq_auth_backend_oauth2` por AMQPS; puerto por definir. | Publicar estados calculados para consumidores independientes. | Publicador OAuth2/AMQPS en el checkout; requiere permiso de escritura al exchange. Topología/ACL, IP y puerto de producción pendientes; la prueba aislada no define producción. |
| RabbitMQ → backend del dashboard | `user_dashboard_backend` abre conexión hacia RabbitMQ en `vm-data`. | Actualizaciones: RabbitMQ → backend, desde `dashboard_updates_queue` por defecto. | RabbitMQ con `rabbitmq_auth_backend_oauth2`; AMQPS configurado por el cliente. | Entregar actualizaciones para presentación. | Consumidor OAuth2/AMQPS implementado; la cola debe existir y estar enlazada antes del arranque. El nombre por defecto, permisos de lectura, IP, puerto y validación con broker siguen pendientes. |
| RabbitMQ → dispatcher de alertas | El consumidor `alert_dispatcher` abre conexión hacia RabbitMQ en `vm-data`. | Actualizaciones: RabbitMQ → dispatcher, desde una cola de alertas. | OAuth2/AMQPS; puerto por definir. | Actualizar la réplica local y notificar transiciones de estado mediante Observer. | Round-trip probado con el fixture OAuth/TLS aislado; cola, ACL, IP y puerto productivos pendientes. |
| Backend → dashboard web | El navegador que carga `dashboard.html` abre la conexión WebSocket hacia `user_dashboard_backend`. | Actualizaciones: backend → navegador. | WebSocket same-origin en `/ws/traffic`; `ws` o `wss` según el esquema HTTP del backend. | Presentar tráfico en tiempo real. | Backend e interfaz implementados; endpoint provisional. Puerto expuesto, proxy TLS, reglas de red y validación end-to-end pendientes. |
| Cliente de consulta → RabbitMQ | Cada instancia de `query_client` abre conexión hacia RabbitMQ en `vm-data`. | Solicitud: cliente → cola de solicitudes; incluye zonas, `requestId`, `correlationId`, `instanceId` y `replyTo`. | OAuth2/AMQPS; puerto por definir. | Enviar consultas asíncronas y esperar respuestas. | Round-trip probado con el fixture. El cliente declara una cola no durable, exclusiva y auto-delete; permisos, IP y puerto productivos pendientes. |
| RabbitMQ → dispatcher de consultas | El consumidor `alert_dispatcher` abre conexión hacia RabbitMQ en `vm-data`. | Solicitud: RabbitMQ → dispatcher, desde la cola de solicitudes. | OAuth2/AMQPS; puerto por definir. | Entregar consulta para responder desde la réplica local. | Consulta y respuesta probadas con el fixture; cola, permisos, IP y puerto productivos pendientes. |
| Dispatcher → RabbitMQ | `alert_dispatcher` abre conexión hacia RabbitMQ en `vm-data`. | Respuesta: dispatcher → exchange de respuestas, con `correlationId` y routing key de instancia. | OAuth2/AMQPS; puerto por definir. | Publicar la respuesta confirmada para una instancia. | Publicación confirmada y ruta probadas con el fixture; exchange, bindings, permisos e IP productivos pendientes. |
| RabbitMQ → cliente de consulta | Cada instancia de `query_client` abre conexión hacia RabbitMQ en `vm-data`. | Respuesta: RabbitMQ → cola exclusiva propia → cliente. | OAuth2/AMQPS; puerto por definir. | Entregar y correlacionar respuestas por instancia. | Respuesta no vacía y correlación verificadas; la cola se elimina al cerrar la conexión. Topología y permisos productivos pendientes. |

## Inventario de endpoints y puertos observados

Esta tabla registra interfaces encontradas en el código y documentación local;
no asigna puertos productivos ni autoriza reglas de firewall. Los valores de
fixtures, pruebas o defaults de desarrollo no deben copiarse a la configuración
de producción.

| Servicio | Interfaz configurada | Puerto de producción | Evidencia y límite |
|---|---|---|---|
| Kafka | Clientes configuran `KAFKA_BOOTSTRAP_SERVERS` y usan SASL_SSL/OAUTHBEARER; validan TLS del broker. El truststore del broker es distinto al truststore HTTPS de Auth Server. | Pendiente; listener, nombre anunciado, destino alcanzable, CA y SAN requieren acuerdo. | El fixture parametriza `CLIENT_PORT` y `CONTROLLER_PORT` desde `.env`, no publica puertos por defecto y mantiene el controller listener fuera del alcance de clientes. `kafka:<CLIENT_PORT>`, `localhost:9093`, `dummy:9092` y el override de host loopback son solo pruebas/desarrollo. |
| Auth Server — token | Clientes solicitan token con OAuth `client_credentials`/`client_secret_basic` a `AUTH_TOKEN_ENDPOINT_URI` por HTTPS; la respuesta vuelve en esa misma conexión. | Pendiente en producción: host, puerto, certificado/CA y perfiles de audiencia/issuer. | El código documenta `/oauth2/token`; el Auth Server actual no selecciona una audiencia distinta por broker para cada solicitud. Los endpoints de fixtures y pruebas no son destinos productivos. |
| Auth Server — JWKS | Kafka y RabbitMQ pueden iniciar conexiones HTTPS salientes al JWKS configurado; no es una conexión iniciada por Auth Server por cada mensaje. Kafka también puede solicitar token para la identidad interbroker cuando su configuración lo requiera. | Pendiente en producción: endpoint, puerto, CA y ruta permitida desde `vm-data` a `vm-auth`. | El Auth Server expone `/oauth2/jwks` cuando RSA está configurado. El fixture Kafka documenta validación de firma, audience e issuer mediante JWKS. Los brokers deben confiar en HTTPS de Auth Server; esto no aprueba endpoints productivos. |
| RabbitMQ | Clientes configuran `RABBITMQ_HOST`/`RABBITMQ_PORT` y usan AMQPS, validando certificado y hostname del broker. La consulta JWKS del broker es HTTPS independiente. | Pendiente; listener, host, puerto, CA y SAN productivos no están acordados. | El fixture 4.3.7 usa OAuth2 (`rabbitmq_auth_backend_oauth2`), SASL PLAIN para transportar el token, RS256, JWKS HTTPS, validación de audience y `exp`, y no permite anónimo. `5671` es interno a la red del fixture, sin publicación al host; `8443` es del Auth Server de prueba. Ninguno es asignación productiva. |
| PostgreSQL | El archivador usa `SPRING_DATASOURCE_URL` JDBC. | Pendiente; host y puerto entre VMs no están acordados. | El fixture de integración publica `127.0.0.1:5433` hacia `5432` del contenedor; ambos son datos de prueba local. |
| Backend del dashboard | `SERVER_PORT` tiene default HTTP de desarrollo `8080`; el navegador usa HTTP/WebSocket same-origin en `/ws/traffic`. | Pendiente; exposición, proxy/ruta y terminación TLS deben acordarse. | El default local no autoriza exposición en AWS. |
| Administración remota | El portátil de Héctor administra las VMs; Ansible necesita que `ansible_host` y `ansible_port` se asignen a hosts/grupos del inventario, no solo como datos anidados. | SSM, sin puerto administrativo entrante en las VMs. | Usar los ID de instancia y un plugin de conexión SSM compatible; mantener el inventario generado localmente e ignorado por Git. Se eligió explorar un usuario IAM individual con MFA porque IAM Identity Center no está disponible en esta cuenta. La identidad y sus permisos mínimos aún no se han creado; se requieren también el rol de instancia, SSM Agent y conectividad saliente a Systems Manager. No se autoriza SSH público. |
| Portátil de Héctor → dashboard | El navegador de Héctor conecta al backend de `vm-app` mediante port forwarding de Session Manager. | HTTP/WebSocket a través del túnel; el listener productivo está por confirmar. | El acceso público no está habilitado; el túnel se inicia desde el portátil autorizado. | Pendiente: autorizar la identidad IAM individual con MFA para SSM/port forwarding de las instancias etiquetadas y probar desde su portátil. |
Para tráfico entre VMs, no usar `kafka`, `localhost` ni alias de una red Docker
como destino. El listener Kafka debe anunciar un nombre o dirección privada
alcanzable y coherente con el SAN del certificado; el listener de control KRaft
debe permanecer fuera del alcance de clientes salvo decisión explícita. RabbitMQ
debe aceptar conexiones solo desde roles que tengan flujos aprobados en esta
matriz (`vm-core` y, para consumidores publicados, `vm-app`).

### Aclaración sobre estado de implementación y seguridad

En este checkout existen adaptadores de Kafka para el simulador, el procesador
y el archivador. Sin embargo, `docs/kafka-oauth-integration.md` todavía afirma
que esos adaptadores no existen. Esa guía debe reconciliarse con el código antes
de usarla como evidencia del estado. La existencia de código, una prueba unitaria
o una ejecución histórica documentada no demuestra por sí sola la integración
vigente con broker ni autoriza endpoints productivos. Steven aprobó la precisión
del texto compartido sobre OAuth, TLS, JWKS y ACLs; esa aprobación no demuestra
una integración Kafka vigente ni aprueba decisiones productivas.

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
| RabbitMQ | Procesador: `write` solo al exchange de actualizaciones; backend: `read` solo a su cola. Para los flujos propuestos de alertas/consultas, asignar lectura/escritura solo a las colas/exchanges aprobados. `configure` solo si el servicio debe crear recursos o bindings; no otorgarlo por defecto. |

`KAFKA_OAUTH_SCOPE` es opcional: los scopes OAuth no autorizan operaciones
Kafka; las ACLs del broker son la política de recursos. En RabbitMQ, los scopes
del fixture `smarttraffic-rabbitmq-it` solo corresponden al publicador y lector
de prueba, no a una política productiva.

Los nombres, scopes y recursos `*-it` del fixture RabbitMQ son exclusivos de
pruebas; la suite OAuth valida publicador/lector y el round-trip de alertas y
consultas usa identidades desechables adicionales. La ejecución del 16/11 aceptó
un token firmado por una clave confiable con otro `iss`; se conserva como
observación limitada a ese fixture/configuración/ejecución. No debe afirmarse
que esa configuración valide igualdad de `iss` ni generalizar el resultado a
toda configuración/plugin.

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
| Portátil de Héctor → VMs | El portátil inicia la sesión; el SSM Agent de la VM mantiene el canal saliente hacia Systems Manager. | Comandos y respuestas de administración por el canal de SSM. | AWS Systems Manager Session Manager; sin SSH público ni ingress administrativo. | Administrar las VM sin ejecutar microservicios en el portátil. | Decisión de demo: Session Manager, rol de instancia `AmazonSSMManagedInstanceCore` y salida TCP 443. Pendiente crear el usuario IAM individual de Héctor con MFA y permisos mínimos, configurar acceso a las API de AWS, etiquetas/inventario y validar SSM Agent/conectividad. |
| Portátil de Héctor → dashboard | El portátil abre port forwarding de Session Manager hacia el listener de `vm-app`; el navegador usa el puerto local del túnel. | HTTP/WebSocket entre el navegador y el backend a través del túnel. | Session Manager port forwarding; no requiere ingress público. | Presentar el dashboard desde el portátil. | Estrategia decidida. Pendiente autorizar la identidad IAM de Héctor y configurar IAM/CLI/plugin; confirmar listener y puertos local/remoto. No habilita reglas de red de aplicación entre las VM. |
| VM → Internet para SSM y aprovisionamiento | Cada VM inicia conexiones salientes hacia los endpoints de Systems Manager, repositorios de imágenes y repositorios del sistema operativo. | Solicitud de conexión/descarga: VM → Internet; respuesta por la misma conexión. | TCP 443 a IPv4 Internet mediante IPv4 pública dinámica e Internet Gateway; no se crea NAT Gateway. | Registrar y administrar las instancias con SSM; descargar imágenes y actualizaciones. | Salida configurada por defecto solo en TCP 443. Security Groups no filtran por FQDN, así que los destinos no quedan limitados a una lista de dominios. DNS/NTP y otros destinos opcionales no se habilitan como reglas de egress aquí y deberán verificarse en el despliegue. |

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
