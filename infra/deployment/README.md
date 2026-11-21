# AWS deployment workflow

The five role-specific private EC2 instances use the single canonical Compose
file `infra/compose.yaml`. Each VM activates only its role profile; `vm-data`
starts PostgreSQL with the bootstrap profile first, then activates `data` after
Auth is ready. The public dashboard EC2 is a separate Nginx reverse-proxy edge
and must not run a second dashboard backend. Security Groups have no application
ingress by default; services communicate over private addresses:

| VM | Profile | Services |
|---|---|---|
| `vm-auth` | `auth` | Auth Server |
| `vm-data` | `postgres` → `data` | PostgreSQL, then Kafka and RabbitMQ |
| `vm-ingest` | `ingest` | Traffic sensor simulator |
| `vm-core` | `core` | Traffic density processor, traffic archiver |
| `vm-app` | `app` | Dashboard backend, alert dispatcher, two query clients |
| `vm-dashboard-public` | Nginx edge | Public HTTPS/OIDC/WebSocket proxy to private dashboard and Auth services |

The `postgres` profile starts only PostgreSQL on `vm-data`. Ansible uses it
before Auth because Auth now reads dashboard users from PostgreSQL; it starts
Kafka and RabbitMQ afterward with the regular `data` profile, once Auth is
available to serve JWKS.

A `dashboard` profile is also defined for an optional dedicated EC2 node that
runs only the dashboard backend. Use it only when `vm-app` does **not** run the
`app` profile; both consuming `dashboard_updates_queue` on the same RabbitMQ
broker would split messages between two consumers.

### Validating a profile locally

Use `infra/.env.validation.example` (no real secrets) to check that the
Compose configuration resolves for a given profile:

```sh
# From infra/
docker compose --env-file .env.validation.example -f compose.yaml --profile auth    config --quiet
docker compose --env-file .env.validation.example -f compose.yaml --profile data    config --quiet
docker compose --env-file .env.validation.example -f compose.yaml --profile postgres config --quiet
docker compose --env-file .env.validation.example -f compose.yaml --profile ingest  config --quiet
docker compose --env-file .env.validation.example -f compose.yaml --profile core    config --quiet
docker compose --env-file .env.validation.example -f compose.yaml --profile app     config --quiet
docker compose --env-file .env.validation.example -f compose.yaml --profile dashboard config --quiet
```

### Migrating from per-VM Compose projects

The old deployment used five separate Compose projects
(`smarttraffic-vm-auth`, `smarttraffic-vm-data`, `smarttraffic-vm-ingest`,
`smarttraffic-vm-core`, `smarttraffic-vm-app`). The canonical project is
`smarttraffic`. Because all services use `network_mode: host`, both projects
cannot run at the same time on the same VM: they would compete for the same
ports.

The per-VM Compose files are retained only to stop the legacy projects during
this cutover. All new deployment configuration belongs in `infra/compose.yaml`;
remove the legacy files after the cutover is verified.

The cutover is a **manual step** that must happen before running Ansible.
Ansible checks for running legacy projects and fails closed if it finds any.

#### Cutover status

The migration to the canonical Compose file was completed on all five VMs.
All running containers were confirmed to use
`/opt/smarttraffic/infra/compose.yaml`. The per-VM Compose files have been
removed from the repository. The legacy guard in Ansible remains active: if
a legacy project name (`smarttraffic-vm-*`) is found running on any VM,
Ansible will fail with a clear message before starting any services.

#### Idempotence

If Ansible is rerun after the canonical project is already running, `docker
compose up --wait` is a no-op for containers that are already up. The legacy
guard only checks for running legacy projects; it does not fail if they exist
but are stopped.



The following Compose files are kept separate because they exist only to
support integration tests and local fixtures; they are not deployment files:

- `infra/vm-data/postgres-it/compose.yaml` — spins up a disposable PostgreSQL
  container for the archiver integration tests.
- `infra/vm-data/rabbitmq/compose.yaml` — spins up a RabbitMQ container for
  the OAuth integration tests.

Ansible and production deployments never reference these files.

Compose files use Linux host networking intentionally: each private VM's
nftables firewall can enforce the service-to-service policy directly. The
dashboard backend binds to the `vm-app` private interface on port 8080, with
access limited to the public edge's Security Group and private IP. The edge
proxies HTTPS and `/ws/traffic`; it does not connect to RabbitMQ. Its
OIDC/login routes go to Auth over verified private TLS. Keep the public Auth
issuer at the edge IP, while service-to-service token and JWKS URLs remain on
the private Auth address. The edge routes and certificate renewal procedure
are in `infra/vm-dashboard-public/README.md`.
The earlier AWS demonstration used an SSM port-forward to
`127.0.0.1:8080` on `vm-app` (local port `18080`). The Terraform defaults do
not declare application rules; reconcile effective AWS rules with this source
before claiming a reproducible deployment. See the
[network matrix](../../docs/network-matrix.md).

The two query clients are fixed replicas defined as separate Compose services,
not scaled copies. Each has a distinct OAuth identity, response queue, and
routing key so correlated replies cannot be consumed by the wrong replica.
Add further instances only with their own pre-provisioned identities and
queues.

## Preparation

1. Keep `enable_resource_creation = false` until Terraform's plan and current
   account costs are reviewed. EC2, EBS, public IPv4, networking, and data
   transfer can consume the account's credits.
2. Ansible tunnels SSH through Systems Manager; no inbound SSH Security Group
   rule is needed. The current Terraform configuration does not provision an
   operator SSH key. Before running Ansible, verify that the operator's public
   key is already authorized for `ec2-user` on every target and that its
   matching private key is local and outside Git. Do not change an existing
   instance's key configuration or apply a plan that replaces instances
   without reviewing the Terraform state and data-recovery plan.
3. Use Terraform's instance outputs to copy
   `ansible/inventory/hosts.example.yml` to the ignored `hosts.yml`, and
   `ansible/group_vars/all.example.yml` to the ignored `group_vars/all.yml`.
   Fill the VPC CIDR, VPC resolver address, five private VM addresses, the
   `vm-dashboard-public` private IP used in the `vm-app` firewall rule, and
   the storage-mode-specific data volume names or IDs; these local inventories
   are excluded from Git.
4. Choose `smarttraffic_data_storage_mode` before deployment. Use `ebs` for
   dedicated EBS data volumes, set each `volume_id`, and verify the IDs and
   existing `ext4`/`xfs` filesystems. For an existing host whose data is in
   Docker named volumes, use `docker_named_volumes` and set each `volume_name`
   to the exact existing name reported by `docker volume inspect`; this reuses
   data in place without copying, formatting, or creating disks. In either
   mode, verify the backup and storage mapping before setting
   `smarttraffic_data_cutover_confirmed: true`. The playbook fails closed for
   missing devices or named volumes and never formats or deletes data.
5. Copy each Compose `.env.example` to a protected local deployment directory:
   - `vm-auth.env`, `vm-data.env`, `vm-ingest.env`, `vm-core.env`, and
     `vm-app.env` go in `SMARTTRAFFIC_SECRETS_DIR`.
   - Each VM also has a same-named subdirectory containing its mounted keys,
     certificates, truststores, and (for Auth) runtime configuration.
   - `vm-data` needs PostgreSQL `server.crt` and `server.key` under
     `postgres-tls`; `vm-auth` needs `postgres-ca/postgres-ca.pem`, an
     `auth-config` directory containing the existing Auth `application.yml`
     (with the dashboard settings from
     `infra/vm-auth/dashboard-auth.application-fragment.yml`) and
     `dashboard-user-password`; `vm-app` needs
     `client-tls/auth-truststore.p12`.
   - Never commit that directory, `.env` files, private keys, passwords, or
     certificates. Values marked `REPLACE_WITH_SECRET` are placeholders, not
     usable credentials.
   - The playbook verifies that each protected role `.env` has every key from
     its matching example and contains no `REPLACE_WITH...` or angle-bracket
     placeholders. Compose receives `.env.validation.example` first and the
     protected role file second, so it can interpolate services from inactive
     profiles while the protected values take precedence. The validation file
     contains no production secrets and must never be the only runtime env file.
6. Confirm the edge IP certificate matches the public IP and the private Auth
   certificate matches the upstream TLS name configured in Nginx; provision
   matching OAuth audiences and distinct client identities. PostgreSQL
   requires a server certificate, its CA on `vm-auth`,
   and separate `dashboard_auth_runtime`/`dashboard_auth_migrator` roles. The
   entrypoint init script creates these roles only when PostgreSQL initializes
   a blank data directory. The playbook also runs the same idempotent role and
   schema provisioning script after PostgreSQL is healthy and before Auth
   starts, so it handles existing databases without relying on entrypoint
   scripts rerunning. Back up existing data before deployment and verify the
   backup; do not recreate or reformat the PostgreSQL data volume. The playbook
   creates the `traffic_readings` topic and grants Kafka ACLs to the simulator,
   processor, and archiver; the Streams ACL is limited to topics prefixed by
   its application ID.
7. Set matching dashboard OAuth client IDs/secrets in `vm-auth.env` and
   `vm-app.env`. For the direct-IP demo, use the same public IP in
   `AUTH_ISSUER_URI` and
   `DASHBOARD_OAUTH_REDIRECT_URI=https://<edge-ip>/login/oauth2/code/dashboard`.
   The initial viewer password file must be a single line of at least 12
   characters. The Auth DB role names/passwords in `vm-auth.env` must match
   those in `vm-data.env`. Follow
   [`infra/vm-dashboard-public/README.md`](../vm-dashboard-public/README.md)
   to install the IP certificate and proxy routes before enabling public login.

## Operator prerequisites

Run Ansible from Linux/WSL with Python, Ansible, `rsync`, AWS CLI,
`session-manager-plugin`, OpenSSH, and AWS credentials authorized for SSM
sessions. Install the collection and set local paths in the shell:

```sh
ansible-galaxy collection install -r requirements.yml
export SMARTTRAFFIC_CHECKOUT=/path/to/SmartTraffic-ZeroTrust
export SMARTTRAFFIC_SECRETS_DIR=/path/to/private/smarttraffic-secrets
export SMARTTRAFFIC_SSH_PRIVATE_KEY=/path/to/private/ssh/key
```

Keep the Ansible working copy in WSL's Linux filesystem when possible. A
checkout under `/mnt/c` may be treated as world-writable, causing Ansible to
ignore its repository `ansible.cfg`; if using that path, pass the inventory
explicitly and review the warning before deployment.

After Terraform has been applied by the account owner and all inventory,
network, certificate, and secret values are populated:

```sh
ansible-playbook --syntax-check site.yml
ansible-playbook site.yml
```

The playbook validates the selected data-storage mode, Compose volume names,
every Compose configuration, and nftables file before starting services. In
`ebs` mode it also validates and mounts the required EBS filesystems; in
`docker_named_volumes` mode it verifies the existing volumes and reuses them
directly. The per-role environment preflight checks required keys and rejects
example placeholders without logging secret values. It excludes local
environment files, inventories, certificates, keys, Terraform state, and
secret directories from source sync, then checks the remote checkout for
excluded files. It builds each VM's required images with Docker Buildx, then
starts Compose with
`--no-build` to keep image building and container startup as separate explicit
steps. It starts PostgreSQL with TLS first, then Auth, Kafka/RabbitMQ, Kafka
topic/ACLs, consumers/dashboard/query clients, and finally the traffic
simulator. Images are built on their destination VMs; size EC2 and EBS for the
JVM limits plus build-time memory. The EBS mount and guarded cutover changes
have not been exercised against AWS; do not treat local validation as a live
deployment or data-migration test.

The Auth Server runs as UID/GID `10001`. Docker grants only
`NET_BIND_SERVICE` so the non-root Java process can listen on HTTPS port 443;
Ansible mounts its certificates and keys read-only for group `10001`.

Amazon Linux 2023 does not provide the Docker Buildx and Compose plugins in its
configured repositories. The playbook installs pinned upstream plugin
releases into Docker's system CLI-plugin directory and verifies their SHA-256
checksums before use.

## Presenter access

The operator retains AWS account ownership and runs Terraform/Ansible. Give
the presenter a separate, least-privilege AWS identity with SSM session and
port-forward permissions scoped to the tagged SmartTraffic instances. Do not
share the root user, operator SSH private key, or application credentials.
The dashboard is reached through the HTTPS Nginx edge; never expose backend
port 8080. SSM remains the administration and recovery path.

### Windows laptop with WSL

Use an Ubuntu WSL shell with the [AWS CLI v2](https://docs.aws.amazon.com/cli/latest/userguide/getting-started-install.html)
and [Session Manager plugin](https://docs.aws.amazon.com/systems-manager/latest/userguide/install-plugin-debian-and-ubuntu.html)
installed. Sign in using the team's approved AWS method; the identity must be
authorized to start an SSM session to `vm-app`. The instance must be online
and managed by Systems Manager. Do not put access keys, instance credentials,
or application secrets in this repository.

Use only the authentication method approved by the AWS account owner:

- If the team uses IAM Identity Center and has already configured a profile,
  run `aws sso login --profile PROFILE`. If it is not configured, ask the
  account owner for the SSO start URL and region before running
  `aws configure sso --profile PROFILE`.
- If the team uses browser-based console sign-in for the CLI and has granted
  the required local-development sign-in permission, run
  `aws login --region us-east-2`.

Verify the selected identity locally with
`aws sts get-caller-identity --region us-east-2`; do not share its output. For
a named profile, add `--profile PROFILE` to the AWS commands below.

Use an available local port such as `18080` (check it in PowerShell with
`Get-NetTCPConnection -LocalPort 18080 -State Listen`). In Ubuntu WSL, replace
the target with the current `vm-app` instance ID from the authorized account:

```bash
aws ssm start-session \
  --target i-REPLACE_WITH_VM_APP_INSTANCE_ID \
  --document-name AWS-StartPortForwardingSession \
  --parameters '{"portNumber":["8080"],"localPortNumber":["18080"]}' \
  --region us-east-2
```

Keep that terminal open and browse from Windows to
`http://127.0.0.1:18080/`. Stop the forward by pressing `Ctrl+C` in the WSL
terminal. This command opens a temporary tunnel only; it does not deploy,
restart, or modify AWS resources. Do not run Terraform or Ansible as part of
the presentation.
