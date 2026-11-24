# User Dashboard Backend

Servicio que consume estados `TrafficUpdate` desde RabbitMQ por AMQPS y los
distribuye a los navegadores por WebSocket. El mismo servicio sirve
`dashboard/dashboard.html` en `/dashboard.html` y redirige `/` a esa página.
El navegador no recibe ni utiliza credenciales RabbitMQ. El acceso al HTML y
al WebSocket requiere una sesión BFF establecida mediante OpenID Connect con
el Auth Server; los tokens OAuth permanecen en el servidor.

## Configuración

Para consumir RabbitMQ, el servicio solicita tokens OAuth2
`client_credentials`. Para las sesiones del navegador usa OpenID Connect con
el Auth Server. Configura las variables de entorno antes de iniciarlo:

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
| `AUTH_ISSUER_URI` | Issuer OIDC del Auth Server; se usa para discovery y validación de identidad. |
| `DASHBOARD_OAUTH_CLIENT_ID` | Cliente Authorization Code registrado para este BFF. |
| `DASHBOARD_OAUTH_CLIENT_SECRET` | Secreto del cliente BFF; mantener fuera del repositorio. |
| `SESSION_COOKIE_SECURE` | Debe ser `true` en despliegues HTTPS; solo usar `false` en desarrollo local HTTP. |

El consumidor no declara ni enlaza la cola, por lo que la identidad solo
necesita permisos de lectura, no de configuración. La cola predeterminada y
`/ws/traffic` son valores provisionales para desarrollo, no una topología
productiva acordada. El endpoint WebSocket es same-origin; cualquier proxy
delante del backend debe permitir el upgrade WebSocket y preservar la ruta.
La sesión usa cookie HttpOnly, SameSite=Lax y una duración de 30 minutos. La
configuración de seguridad exige autenticación para todas las rutas, incluida
la página estática y el handshake del WebSocket. No deshabilites CSRF ni
expongas los tokens del BFF al navegador.
La acción **Cerrar sesión** invalida la sesión BFF y luego la del Auth Server.
El logout BFF conserva la protección CSRF y devuelve `204` para que su
redirección no se siga dentro de `fetch`. Después, el navegador obtiene el
token CSRF desde `/login` y envía `/logout` como formulario nativo; Nginx debe
enrutar esa ruta al Auth Server para que el navegador siga su redirección final
al login.

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
aislado ni confirman el despliegue de producción. Para probar login local, el
Auth Server debe estar disponible en `AUTH_ISSUER_URI` y tener registrado el
callback local. Usa `SESSION_COOKIE_SECURE=false` únicamente en esta prueba
HTTP local. **No habilites ni pruebes el formulario con contraseñas en el
dashboard público HTTP actual**; primero debe completarse el despliegue HTTPS
y la publicación segura de las rutas OIDC.
