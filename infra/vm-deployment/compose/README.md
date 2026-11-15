# Compose por VM

Se prevén manifiestos independientes para `vm-auth`, `vm-data`, `vm-ingest`,
`vm-core` y `vm-app`. No se agregan todavía porque la cabeza publicada de esta
rama no contiene `app.Dockerfile` ni el Compose `postgres-it` citado por el
equipo, y faltan parámetros de red y coordinación de persistencia y brokers.
No se deben duplicar esos artefactos ni convertir los fixtures locales de
Kafka/RabbitMQ en Compose de producción.

| Rol | Manifiesto de producción | `config` / build / arranque / salud / parada / redes |
|---|---|---|
| `vm-auth` | Pendiente | Pendiente |
| `vm-data` | Pendiente | Pendiente |
| `vm-ingest` | Pendiente | Pendiente |
| `vm-core` | Pendiente | Pendiente |
| `vm-app` | Pendiente | Pendiente |

Al implementarlos, los endpoints de servicios en otras VMs, listeners y
direcciones de publicación deberán ser obligatorios y parametrizados, sin
valores predeterminados que expongan puertos o usen `localhost`/alias Docker
como direcciones entre VMs. No incluir credenciales, claves ni certificados;
montarlos desde rutas locales protegidas o un gestor de secretos aprobado.
Los límites de recursos y la ejecución sin privilegios se documentarán junto
con la validación efectiva de cada servicio.
