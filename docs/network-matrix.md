# Matriz de comunicaciones de SmartTraffic

## Alcance y convenciones

Esta matriz complementa el
[overview de arquitectura](./architecture-overview.md) y el
[README](../README.md). Describe flujos lógicos previstos, no reglas `nftables`
ya implementadas. No asigna direcciones IP ni confirma listeners productivos.
Los puertos expresamente fijados por el Ejercicio 12 se resumen más abajo.

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
completa como base de producción. El usuario eligió VMs privadas, administración
por SSM, túnel SSM al dashboard y NAT temporal con DNS y TCP 443 saliente. No
se han creado recursos AWS ni reglas `nftables`.

### Decisiones de despliegue del Ejercicio 12

- Puertos requeridos: Kafka TCP `9092`, RabbitMQ TCP `5672`, PostgreSQL TCP
  `5432` y Auth en `443` (el enunciado escribe `443/HTTP`, por lo que el
  protocolo debe contrastarse con el servicio). El código exige HTTPS para
  token/JWKS en producción; el despliegue debe terminar TLS en Auth `443`.
- RabbitMQ debe conservar AMQPS en el puerto requerido `5672`. El listener
  TLS `5671` del fixture local no cambia; no se debe copiar como puerto de AWS.
- Las cinco VMs no tendrán IP pública ni SSH público. SSM es la vía de
  administración; el dashboard se abrirá por port forwarding SSM, sin ingreso
  público en el Security Group. El `8080` del backend sigue siendo provisional
  hasta confirmar el puerto remoto.
- Se aprobó NAT temporal para DNS y TCP 443 saliente durante aprovisionamiento
  y demo. La excepción de HTTPS es amplia por destino y debe permanecer
  explícita, apagada por defecto y limitada al tiempo de uso.
- Inferencia de JWKS: se permite TCP `443` desde `vm-data` hacia `vm-auth`.
  Kafka y RabbitMQ en `vm-data` consultan JWKS para validar JWT, y el propio
  bloque de `vm-data` autoriza la salida a Auth. Aunque la lista de entrada de
  `vm-auth` en el enunciado omite `vm-data`, se incluye esta excepción mínima
  para que la validación remota funcione. No autoriza otros puertos ni flujos.
- Los listeners productivos, certificados, SAN/CA, identidades, permisos y
  direcciones privadas aún deben configurarse y probarse. Los puertos de un
  fixture no sustituyen los puertos exigidos por el ejercicio.

## Disponibilidad de los servicios

La base publicada compartida `develop` en `270efc1ccc27ccb9992ba65539faf70dc3902b24`
no incluye `alert-dispatcher` ni `query-client`; Steven confirmó que tampoco
están en su checkout limpio de esa base. Sus directorios y código aparecen
como cambios locales sin seguimiento en este checkout de Sebastián. Por ello,
los flujos de alertas y consultas describen trabajo local pendiente de
revisión/publicación: no están disponibles al clonar `develop` ni se han
validado con un broker. Las pruebas unitarias existentes en esa copia local no
se ejecutaron durante esta revisión. Actualizar este estado cuando esos cambios
se publiquen y revisen.

## Flujos de aplicación

| Flujo | Iniciador de conexión de red | Sentido lógico del mensaje | Servicio / protocolo conocido | Propósito | Estado |
|---|---|---|---|---|---|
| Simulador → Kafka | `traffic_sensor_simulator` abre conexión hacia Kafka en `vm-data`. | Lecturas: simulador → tópico Kafka. | Kafka SASL_SSL/OAUTHBEARER, TCP `9092`. | Publicar lecturas aleatorias; `traffic_readings` es el tópico documentado para el despliegue. | Puerto exigido por el ejercicio; tópico/ACL e identidad de despliegue aún requieren aprovisionamiento y validación. |
| Kafka → procesador | `traffic_density_processor` abre conexión hacia Kafka en `vm-data`. | Lecturas: Kafka → procesador. | Kafka Streams/KTable con SASL_SSL/OAUTHBEARER, TCP `9092`. | Consumir lecturas y calcular estado por zona. | Puerto exigido; grupo/ACL e identidad de despliegue pendientes. |
| Kafka → archivador | `traffic_archiver` abre conexión hacia Kafka en `vm-data`. | Lecturas originales: Kafka → archivador. | Kafka SASL_SSL/OAUTHBEARER, TCP `9092`. | Consumir lecturas en paralelo para archivarlas. | Puerto exigido; grupo/ACL e identidad de despliegue pendientes. |
| Archivador → PostgreSQL | `traffic_archiver` abre conexión hacia PostgreSQL en `vm-data`. | Escrituras históricas: archivador → PostgreSQL. | PostgreSQL con Spring Data JPA/Hibernate, TCP `5432`. | Guardar lecturas históricas y auditoría según el esquema aprobado. | Puerto exigido; identidad y credenciales de despliegue por aprovisionar. |
| Procesador → RabbitMQ | `traffic_density_processor` abre conexión hacia RabbitMQ en `vm-data`. | Actualizaciones: procesador → exchange `traffic_updates`. | RabbitMQ OAuth2 por AMQPS, TCP `5672`. | Publicar estados calculados para consumidores independientes. | Puerto exigido; identidad y permisos de despliegue por aprovisionar. El fixture local usa otro puerto y no se modifica. |
| RabbitMQ → backend del dashboard | `user_dashboard_backend` abre conexión hacia RabbitMQ en `vm-data`. | Actualizaciones: RabbitMQ → backend, desde `dashboard_updates_queue` por defecto. | RabbitMQ con `rabbitmq_auth_backend_oauth2`; AMQPS configurado por el cliente. | Entregar actualizaciones para presentación. | Consumidor OAuth2/AMQPS implementado; la cola debe existir y estar enlazada antes del arranque. El nombre por defecto, permisos de lectura, IP, puerto y validación con broker siguen pendientes. |
| RabbitMQ → dispatcher de alertas | El consumidor `alert_dispatcher` abriría conexión hacia RabbitMQ en `vm-data`. | Actualizaciones: RabbitMQ → dispatcher, desde una cola de alertas. | Diseño OAuth2/AMQPS; puerto por definir. | Actualizar la réplica local y registrar transiciones como alertas. | No existe en la base publicada ni en el checkout limpio de Steven; solo está como código local de Sebastián. No está publicado ni validado con broker. |
| Backend → dashboard web | El navegador que carga `dashboard.html` abre la conexión WebSocket hacia `user_dashboard_backend`. | Actualizaciones: backend → navegador. | WebSocket same-origin en `/ws/traffic`; `ws` o `wss` según el esquema HTTP del backend. | Presentar tráfico en tiempo real. | Backend e interfaz implementados; endpoint provisional. Puerto expuesto, proxy TLS, reglas de red y validación end-to-end pendientes. |
| Cliente de consulta → RabbitMQ | Cada cliente `query_client` abriría conexión hacia RabbitMQ en `vm-data`. | Solicitud: cliente → cola de solicitudes; incluye zonas, `requestId`, `correlationId`, `instanceId` y `replyTo`. | Diseño OAuth2/AMQPS; puerto por definir. | Enviar consultas asíncronas y esperar respuestas. | No existe en la base publicada ni en el checkout limpio de Steven; solo está como código local de Sebastián. No está publicado ni validado con broker. |
| RabbitMQ → dispatcher de consultas | El consumidor `alert_dispatcher` abriría conexión hacia RabbitMQ en `vm-data`. | Solicitud: RabbitMQ → dispatcher, desde la cola de solicitudes. | Diseño OAuth2/AMQPS; puerto por definir. | Entregar consulta para responder desde la réplica local. | Flujo de arquitectura/código local no publicado; cola, permisos y pruebas con broker pendientes. |
| Dispatcher → RabbitMQ | `alert_dispatcher` abriría conexión hacia RabbitMQ en `vm-data`. | Respuesta: dispatcher → exchange de respuestas, con `correlationId` y routing key de instancia. | Diseño OAuth2/AMQPS; puerto por definir. | Publicar la respuesta confirmada para una instancia. | Flujo de arquitectura/código local no publicado; exchange, bindings, permisos y pruebas con broker pendientes. |
| RabbitMQ → cliente de consulta | Cada cliente `query_client` abriría conexión hacia RabbitMQ en `vm-data`. | Respuesta: RabbitMQ → cola propia de respuesta → cliente. | Diseño OAuth2/AMQPS; puerto por definir. | Entregar y correlacionar respuestas por instancia. | Flujo de arquitectura/código local no publicado; cola, binding, permisos y pruebas con broker pendientes. |

## Inventario de endpoints y puertos observados

Esta tabla registra interfaces encontradas en el código y documentación local;
no asigna puertos productivos ni autoriza reglas de firewall. Los valores de
fixtures, pruebas o defaults de desarrollo no deben copiarse a la configuración
de producción.

| Servicio | Interfaz configurada | Puerto de producción | Evidencia y límite |
|---|---|---|---|
| Kafka | Clientes usan `KAFKA_BOOTSTRAP_SERVERS` con SASL_SSL/OAUTHBEARER y validan TLS del broker. El truststore del broker es distinto al truststore HTTPS de Auth Server. | TCP `9092`, requisito del ejercicio; el listener de control KRaft no se expone a clientes. | El Compose AWS anuncia la IP privada de `vm-data`. El certificado debe incluir esa IP en SAN; el truststore y ACLs deben provisionarse. Los puertos configurables del fixture local se mantienen separados. |
| Auth Server — token | Clientes solicitan token con OAuth `client_credentials`/`client_secret_basic`; respuesta en la conexión iniciada por el cliente. | HTTPS TCP `443`; `/oauth2/token`. | El Compose AWS configura TLS en el servidor. Issuer, certificados, identidades, audiencias y secretos deben provisionarse para las IPs privadas. |
| Auth Server — JWKS | Kafka y RabbitMQ en `vm-data` abren conexiones HTTPS salientes a Auth; las respuestas regresan por esas conexiones. | HTTPS TCP `443`; `/oauth2/jwks`. | La regla desde `vm-data` es la excepción mínima inferida para permitir la validación JWT que exige el proyecto. El certificado CA/SAN y la clave RSA deben provisionarse. |
| RabbitMQ | Clientes configuran `RABBITMQ_HOST`/`RABBITMQ_PORT` y usan AMQPS con validación de certificado/hostname; el broker consulta JWKS por HTTPS. | AMQPS TCP `5672`, requisito del ejercicio. | El Compose AWS conserva TLS en `5672`; el fixture local usa TLS en `5671`, que no es el puerto AWS. Certificado SAN, CA, identidades, scopes y ACLs aún deben provisionarse. |
| PostgreSQL | El archivador usa `SPRING_DATASOURCE_URL` JDBC. | TCP `5432`, requisito del ejercicio. | El Compose AWS mantiene PostgreSQL en el puerto del contenedor/host `5432`; la publicación local `127.0.0.1:5433` es exclusiva de pruebas. |
| Backend del dashboard | `SERVER_PORT` se configura para HTTP/WebSocket same-origin en `/ws/traffic`. | HTTP TCP `8080` solo en loopback de `vm-app`; acceso remoto por túnel SSM. | `8080` es el default del código, no un número del enunciado. No se publica al exterior ni se agrega ingress al Security Group. |
| Administración remota | El portátil autorizado solicita sesiones mediante AWS Systems Manager Session Manager; el SSM Agent de cada VM inicia el canal saliente. | Comandos/respuestas por el canal de SSM. | SSM seleccionado; sin SSH público. El inventario/uso de Ansible por SSM aún debe probarse. | Las instancias necesitan un rol con `AmazonSSMManagedInstanceCore`, SSM Agent y HTTPS saliente a Systems Manager. El operador necesita permisos propios en AWS; no compartir claves. |
| Portátil de Héctor → dashboard | El navegador conecta al backend de `vm-app` a través de un túnel SSM de port forwarding. | HTTP/WebSocket en el canal del túnel. | Túnel SSM seleccionado; puerto remoto por confirmar (el `8080` del backend es default local). | No requiere regla de ingreso pública en el Security Group. El host y `nftables` deben permitir la conexión local del SSM Agent al puerto del servicio. |

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
| Servicio → `auth_server` (solicitud de token) | Cada servicio con cliente OAuth abre conexión hacia `auth_server` en `vm-auth`. | Solicitud OAuth y credenciales: servicio → Auth Server. | HTTPS TCP `443`, OAuth `client_credentials`/`client_secret_basic`, endpoint `/oauth2/token`. | Autenticar la identidad de servicio y solicitar JWT. | Puerto y protocolo definidos para el despliegue; certificados, identidades, issuer y audiencias deben provisionarse. |
| `auth_server` → servicio (respuesta de token) | Ocurre en la conexión iniciada por el servicio; no es una conexión nueva desde Auth Server. | JWT emitido: Auth Server → servicio. | Respuesta HTTPS del token endpoint. | Entregar el JWT al cliente autenticado. | Respuesta en la misma conexión. Claims, política final de issuer/audiencias y transporte productivo deben aprobarse. |
| Kafka → Auth Server (JWKS) | El broker Kafka abre conexión saliente a `auth_server` en `vm-auth` para consultar JWKS; el retorno ocurre en esa conexión. | Petición de claves: Kafka → Auth Server; respuesta: Auth Server → Kafka. | HTTPS TCP `443`, endpoint `/oauth2/jwks`. | Obtener claves públicas para validar JWT. | El puerto/protocolo se infieren del despliegue y de la necesidad de JWKS; CA, SAN y endpoint privado requieren provisionamiento. |
| Kafka → Auth Server (token interbroker, condicional) | El cliente interbroker OAuth de Kafka puede solicitar un token al endpoint de Auth Server. | Solicitud/respuesta de token en la conexión HTTPS iniciada por Kafka. | HTTPS OAuth; depende de la configuración interbroker aprobada. | Autenticar conexiones internas del broker si se mantiene esa configuración. | Fixture local documenta esta identidad; producción no configurada ni aprobada. |
| RabbitMQ → Auth Server (JWKS) | El broker RabbitMQ abre conexión saliente a `auth_server` en `vm-auth`; el retorno ocurre en esa conexión. | Petición de claves: RabbitMQ → Auth Server; respuesta: Auth Server → RabbitMQ. | HTTPS TCP `443`, endpoint `/oauth2/jwks`. | Obtener claves públicas para validar JWT de clientes. | La excepción de red `vm-data → vm-auth:443` es necesaria con el validador JWKS remoto actual. |

El Proyecto 12 requiere autenticación de los servicios, SASL/OAUTHBEARER para
Kafka y `rabbitmq_auth_backend_oauth2` para RabbitMQ, con acceso anónimo
prohibido. El código/fixtures actuales usan TLS en los flujos indicados arriba;
la configuración productiva de confianza y la autorización por recurso deben
acordarse antes de crear reglas.

## Servicios auxiliares y administración

| Flujo | Iniciador de conexión de red | Sentido lógico del mensaje | Servicio / protocolo conocido | Propósito | Estado |
|---|---|---|---|---|---|
| VM → DNS | El host o servicio inicia consultas al resolver DNS de la VPC. | Consulta y respuesta DNS. | UDP/TCP 53 al resolver de la VPC. | Resolver nombres para SSM y aprovisionamiento. | DNS temporal permitido; `nftables` debe limitarlo al resolver de la VPC. Los Security Groups no sustituyen esa política local. |
| VM → NTP | El host o servicio de tiempo inicia la conexión hacia la fuente de tiempo configurada. | Solicitud y respuesta de sincronización de tiempo. | NTP; servidor, transporte y puerto por definir. | Sincronizar reloj, si se habilita. | Condicional y pendiente; no se asigna servidor. |
| Portátil autorizado → VMs | El operador solicita sesiones de administración mediante AWS Systems Manager. | Comandos/respuestas por el canal SSM. | Session Manager; no SSH público. | Administrar las VMs sin exponer puertos entrantes a Internet. | Seleccionado. Requiere identidad IAM del operador, rol de instancia, SSM Agent y conectividad saliente de la VM. |
| VM → Internet para aprovisionamiento | La VM que requiera paquetes o imágenes inicia la conexión a través del NAT temporal. | Solicitud de descarga: VM → Internet; respuesta: Internet → VM. | TCP 443; DNS al resolver de la VPC. | SSM, descarga de imágenes y aprovisionamiento durante la preparación/demo. | Permitido temporalmente mediante un NAT compartido y HTTPS saliente; destino público amplio, costeado y apagado por defecto. No habilita ingreso desde Internet. |

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
- Puerto real del dashboard para el túnel SSM; no crear ingreso público.
- DNS al resolver de la VPC y TCP 443 saliente temporal aprobados; NTP sigue
  sin seleccionarse.
- Identidades, autenticación, permisos y configuración TLS de cada flujo.

Los nombres `traffic_readings`, `dashboard_updates_queue` y
`alert_updates_queue` aparecen como propuestas en el README. Deben confirmarse
antes de fijarlos en configuración. Las filas de DNS, NTP, SSH y salida a
Internet son condicionales, no autorizaciones de red.
