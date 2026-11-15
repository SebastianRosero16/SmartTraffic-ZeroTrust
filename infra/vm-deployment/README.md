# Despliegue de SmartTraffic en cinco VMs

## Estado

Este directorio es un esqueleto de despliegue preparado en la rama
`feature/docker-vm-deployment`, desde la cabeza publicada de `develop`
`270efc1ccc27ccb9992ba65539faf70dc3902b24`.

No es un despliegue de producción ni un procedimiento ejecutable todavía. No
se han creado VMs o recursos AWS, ni se han aplicado reglas de firewall,
iniciado contenedores o validado conectividad entre VMs. La región, la oferta
de la cuenta, el presupuesto, las direcciones y la matriz autorizada siguen
pendientes.

## Distribución lógica propuesta

| VM | Componentes previstos |
|---|---|
| `vm-auth` | `auth_server` |
| `vm-data` | Kafka, RabbitMQ y PostgreSQL |
| `vm-ingest` | `traffic_sensor_simulator` |
| `vm-core` | `traffic_density_processor` y `traffic_archiver` |
| `vm-app` | `user_dashboard_backend`, `alert_dispatcher`, `query_client` y el dashboard web |

La separación lógica no define VPC, subredes, rutas, CIDR, interfaces, puertos,
IP, DNS ni reglas de acceso. Estos datos deben acordarse con el equipo y
reflejarse primero en la matriz de comunicaciones. No usar `localhost` ni
nombres internos de Docker para conexiones entre VMs. Las conexiones entre
zonas y los listeners anunciados por Kafka deben parametrizarse con valores
aprobados y nombres/IP alcanzables por las otras VMs; el certificado TLS debe
ser compatible con el nombre anunciado.

El acceso al dashboard desde el portátil de administración queda pendiente de
acordar y probar mediante una ruta privada autorizada. No se configura una IP
pública, un puerto de host ni exposición por defecto.

## Reutilización y límites de alcance

- `infra/vm-data/compose.yaml` es el entorno local de prueba de Kafka OAuth; no
  se copia ni se presenta como Compose de producción.
- `infra/vm-data/rabbitmq/compose.yaml` es el fixture RabbitMQ existente; no se
  copia ni se declara producción.
- La referencia a `postgres-it/compose.yaml` y `app.Dockerfile` no está
  disponible en la cabeza publicada usada para esta rama. Se espera una
  versión publicada antes de integrarlos; no se crean reemplazos.
- Los manifiestos de producción por VM se añadirán cuando estén disponibles
  los artefactos de aplicación, las decisiones de red y la configuración
  coordinada de brokers y persistencia.
- No guardar secretos, JWT, claves privadas ni certificados en este
  directorio. La configuración local se mantiene en archivos ignorados por
  Git o en un gestor de secretos aprobado.

## Estructura preparada

- `compose/README.md`: alcance previsto y estado de validación de los cinco
  Compose de VM.
- `ansible/ansible.cfg`: configuración conservadora para el futuro control
  Ansible desde el portátil.
- `ansible/inventory.example.yml`: grupos lógicos sin direcciones asignadas.
- `ansible/group_vars/all.example.yml`: nombres de parámetros con valores
  vacíos o deshabilitados hasta su aprobación.

No hay aún un playbook ni archivos Compose de producción; por lo tanto, no hay
comandos de aprovisionamiento o despliegue que ejecutar desde este esqueleto.
`nftables` con política predeterminada DROP sigue siendo un requisito, pero no
se instala una plantilla incompleta que pueda cortar el acceso administrativo
o adivinar excepciones.

## Procedimiento pendiente para el portátil

Una vez publicados los artefactos faltantes y aprobados los parámetros:

1. Partir de un clon limpio de la cabeza acordada y confirmar SHA y rama.
2. Copiar `ansible/inventory.example.yml` a `ansible/inventory.yml` y
   `ansible/group_vars/all.example.yml` a `ansible/group_vars/all.yml`.
   Completar únicamente las direcciones y opciones confirmadas; ambos
   archivos locales están excluidos por `.gitignore`.
3. Coordinar con Sebastián las interfaces, persistencia, puertos y ruta privada
   al dashboard; coordinar con Steven OAuth, TLS/certificados, identidades y
   permisos de Kafka/RabbitMQ.
4. Completar los Compose por VM y verificar cada uno con `docker compose
   config`. Construir, arrancar, comprobar salud, redes y parada solo en un
   entorno autorizado y después de acordar datos y permisos. Registrar
   resultados observados; un archivo declarativo no prueba que una regla o
   límite esté activo.
5. Validar inventario y sintaxis Ansible, revisar el modo de comprobación y
   obtener autorización antes de aplicar cambios a hosts.
6. Usar un clon limpio autorizado para repetir las instrucciones y documentar
   cualquier diferencia.

Hasta completar esas etapas, la validación de los cinco Compose, Ansible,
`nftables`, conectividad, dashboard y VMs figura como pendiente. No ejecutar
acciones AWS desde este procedimiento sin autorización explícita y revisión
del costo.
