# AWS deployment workflow

The five role-specific EC2 instances all use the single canonical Compose file
`infra/compose.yaml`. Each VM activates only its own profile; no VM runs
services from another profile. Security Groups have no application ingress by
default; services communicate over private addresses:

| VM | Profile | Services |
|---|---|---|
| `vm-auth` | `auth` | Auth Server |
| `vm-data` | `data` | Kafka, RabbitMQ, PostgreSQL |
| `vm-ingest` | `ingest` | Traffic sensor simulator |
| `vm-core` | `core` | Traffic density processor, traffic archiver |
| `vm-app` | `app` | Dashboard backend, alert dispatcher, two query clients |

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

The cutover is a **manual step** that must happen before running Ansible.
Ansible checks for running legacy projects and fails closed if it finds any.

#### Cutover procedure (per VM, in order)

> **Never use `docker compose down -v`**. The `-v` flag deletes named volumes
> and would destroy Kafka, RabbitMQ, and PostgreSQL data. Use only `stop`.

The commands below require the per-VM `.env` file that was used to start the
legacy project. These files live in `SMARTTRAFFIC_SECRETS_DIR` on the operator
machine and are copied to `/etc/smarttraffic/<vm>.env` on each VM by Ansible.
Run these commands **on each VM** (via SSH or SSM session):

```sh
# On vm-ingest — stop the simulator first to avoid new data during cutover
docker compose \
  --file /opt/smarttraffic/infra/vm-ingest/compose.yaml \
  --env-file /etc/smarttraffic/vm-ingest.env \
  --project-name smarttraffic-vm-ingest \
  stop

# On vm-core
docker compose \
  --file /opt/smarttraffic/infra/vm-core/compose.yaml \
  --env-file /etc/smarttraffic/vm-core.env \
  --project-name smarttraffic-vm-core \
  stop

# On vm-app
docker compose \
  --file /opt/smarttraffic/infra/vm-app/compose.yaml \
  --env-file /etc/smarttraffic/vm-app.env \
  --project-name smarttraffic-vm-app \
  stop

# On vm-data — stop brokers after consumers are down
docker compose \
  --file /opt/smarttraffic/infra/vm-data/compose.aws.yaml \
  --env-file /etc/smarttraffic/vm-data.env \
  --project-name smarttraffic-vm-data \
  stop

# On vm-auth — stop last
docker compose \
  --file /opt/smarttraffic/infra/vm-auth/compose.yaml \
  --env-file /etc/smarttraffic/vm-auth.env \
  --project-name smarttraffic-vm-auth \
  stop
```

Verify all legacy projects are stopped before running Ansible:

```sh
docker compose ls   # none of the smarttraffic-vm-* names should appear as running
```

Ansible will then start the canonical project (`smarttraffic`) with the
appropriate profile per VM. If any legacy project is still running, Ansible
fails with a message listing which projects to stop.

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

Compose files use Linux host networking intentionally: each VM's nftables
firewall can enforce the service-to-service policy directly. The dashboard
was reached in the AWS demonstration only through an SSM port-forward
targeting `127.0.0.1:8080` on `vm-app` (local port `18080`). This verified that presentation
path, not the current Security Group or nftables rules. The Terraform defaults
do not declare application rules, while application flows were observed in
the live demo; reconcile the effective AWS rules with this source before
claiming a reproducible deployment. See the
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
   Fill the VPC CIDR, VPC resolver address, five private VM addresses, and
   `data_volume_ids` from Terraform there; these local inventories are
   excluded from Git.
4. Before deployment, verify the three EBS IDs against the AWS account. Back
   up and manually migrate any existing Kafka, RabbitMQ, or PostgreSQL data
   before setting `smarttraffic_data_cutover_confirmed: true`. Prepare only
   verified blank volumes manually, or preserve their existing supported
   `ext4`/`xfs` filesystems; never format a volume containing data. The
   playbook checks every attached device and filesystem before changing any
   VM, never runs a formatting command, and fails closed if a device is
   missing or unsupported.
5. Copy each Compose `.env.example` to a protected local deployment directory:
   - `vm-auth.env`, `vm-data.env`, `vm-ingest.env`, `vm-core.env`, and
     `vm-app.env` go in `SMARTTRAFFIC_SECRETS_DIR`.
   - Each VM also has a same-named subdirectory containing its mounted keys,
     certificates, truststores, and (for Auth) runtime configuration.
   - Never commit that directory, `.env` files, private keys, passwords, or
     certificates. Values marked `REPLACE_WITH_SECRET` are placeholders, not
     usable credentials.
6. Confirm the TLS certificate SANs match the private IPs used for Auth,
   Kafka, and RabbitMQ; provision matching OAuth audiences and distinct client
   identities. The playbook creates the `traffic_readings` topic and grants
   Kafka ACLs to the simulator, processor, and archiver; the Streams ACL is
   limited to topics prefixed by its application ID.

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

After Terraform has been applied by the account owner and all inventory,
network, certificate, and secret values are populated:

```sh
ansible-playbook --syntax-check site.yml
ansible-playbook site.yml
```

The playbook validates the required EBS devices, every Compose configuration,
and nftables file before starting services. It excludes local environment
files, inventories, certificates, keys, Terraform state, and secret directories
from source sync, then checks the remote checkout for excluded files. It builds
each VM's required images with Docker Buildx, then starts Compose with
`--no-build` to keep image building and container startup as separate explicit
steps. It starts Auth first (brokers retrieve JWKS), then data services and
Kafka topic/ACLs, consumers/dashboard/query clients, and finally the traffic
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
Open the dashboard locally through SSM port forwarding; no dashboard port is
exposed to the Internet.

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
