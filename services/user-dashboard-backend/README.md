# User Dashboard Backend

Servicio que consume estados `TrafficUpdate` desde RabbitMQ por AMQPS y los
distribuye a los navegadores por WebSocket. El mismo servicio sirve
`dashboard/dashboard.html` en `/dashboard.html` y redirige `/` a esa página.
El navegador no recibe ni utiliza credenciales RabbitMQ.

## Configuración

El servicio solicita tokens OAuth2 `client_credentials` al proveedor existente.
Configura las variables de entorno antes de iniciarlo:

| Variable | Propósito |
|---|---|
| `RABBITMQ_HOST` | Host del broker. |
| `RABBITMQ_PORT` | Puerto AMQPS del broker. |
| `RABBITMQ_VHOST` | Virtual host al que se autoriza el servicio. |
| `RABBITMQ_OAUTH_CLIENT_ID` | Identidad OAuth2 del backend. |
| `RABBITMQ_OAUTH_CLIENT_SECRET` | Secreto OAuth2; mantener fuera del repositorio. |
| `RABBITMQ_OAUTH_SCOPE` | Scope con permiso `read` sobre la cola. |
| `AUTH_TOKEN_ENDPOINT_URI` | URL HTTPS del token endpoint OAuth2. |
| `AUTH_TLS_CA_CERT_PATH` | Ruta al certificado CA que valida el token endpoint. |
| `RABBITMQ_TLS_CA_CERT_PATH` | Ruta al certificado CA que valida el broker AMQPS. |
| `RABBITMQ_DASHBOARD_QUEUE` | Cola que consume; por defecto `dashboard_updates_queue`. Debe existir y estar enlazada al exchange de actualizaciones. |
| `SERVER_PORT` | Puerto HTTP del backend; por defecto `8080`. |

El consumidor no declara ni enlaza la cola, por lo que la identidad solo
necesita permisos de lectura, no de configuración. La cola predeterminada y
`/ws/traffic` son valores provisionales para desarrollo, no una topología
productiva acordada. El endpoint WebSocket es same-origin; cualquier proxy
delante del backend debe permitir el upgrade WebSocket y preservar la ruta.

En las pruebas aisladas existentes, los nombres del fixture son distintos:
vhost `smarttraffic-it`, exchange `traffic-updates-it` y cola
`dashboard-updates-it`. El scope del cliente de prueba debe autorizar lectura
en ese vhost y cola. No confundir esos valores de fixture con los defaults del
servicio.

## Comportamiento

- Envía a los clientes el estado más reciente disponible al conectarse y cada
  actualización nueva por zona.
- Descarta marcas de tiempo anteriores a la última actualización conservada.
- Rechaza y registra mensajes RabbitMQ que no se puedan deserializar; los
  mensajes inválidos no se reencolan.
- El navegador valida el contrato, conserva las últimas mediciones por zona y
  reintenta la conexión WebSocket con espera creciente.
- `densityValue` se presenta como promedio de vehículos por lectura, no como
  porcentaje.

## Compilación y pruebas

Desde la raíz del repositorio:

```powershell
mvn -B -pl services/user-dashboard-backend -am test
```

Para ejecutar el servicio:

```powershell
mvn -pl services/user-dashboard-backend -am spring-boot:run
```

Después, abre `http://localhost:8080/`. Una conexión funcional requiere que
RabbitMQ, el proveedor OAuth2, los certificados CA y la cola estén
configurados. Las pruebas unitarias no sustituyen una validación con el broker
aislado ni confirman el despliegue de producción.
