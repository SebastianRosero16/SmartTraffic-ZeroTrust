# AWS deployment workflow

The five private EC2 instances run separate Compose projects:

| VM | Compose file | Services |
|---|---|---|
| `vm-auth` | `infra/vm-auth/compose.yaml` | Auth Server |
| `vm-data` | `infra/vm-data/compose.aws.yaml` | Kafka, RabbitMQ, PostgreSQL |
| `vm-ingest` | `infra/vm-ingest/compose.yaml` | Traffic sensor simulator |
| `vm-core` | `infra/vm-core/compose.yaml` | Traffic density processor, traffic archiver |
| `vm-app` | `infra/vm-app/compose.yaml` | Dashboard backend, alert dispatcher, two query clients |

Compose files use Linux host networking intentionally: each VM's nftables
firewall can enforce the service-to-service policy directly. The EC2 Security
Groups and nftables both deny unsolicited inbound traffic; the dashboard is
available to the presenter only through an SSM port-forward to
`127.0.0.1:8080`. The exercise's application listeners are Kafka TCP `9092`,
RabbitMQ TLS `5672`, PostgreSQL `5432`, and HTTPS Auth `443`.

The two query clients are separate Compose services, not scaled copies. Each
has a distinct OAuth identity, response queue, and routing key so correlated
replies cannot be consumed by the wrong replica. Add further instances only
with their own pre-provisioned identities and queues.

## Preparation

1. Keep `enable_resource_creation = false` until Terraform's plan and current
   account costs are reviewed. The NAT Gateway, Elastic IP, EC2 instances,
   EBS, and data transfer can consume the account's credits.
2. Create an operator SSH key pair locally. Register only its public key
   through `operator_ssh_public_key`; keep the private key outside Git and on
   the operator's workstation. Ansible tunnels SSH through Systems Manager,
   so no inbound SSH Security Group rule is needed.
3. Use Terraform's instance outputs to copy
   `ansible/inventory/hosts.example.yml` to the ignored `hosts.yml`, and
   `ansible/group_vars/all.example.yml` to the ignored `group_vars/all.yml`.
   Fill the VPC CIDR, VPC resolver address, and five private VM addresses
   there; these local inventories are excluded from Git.
4. Copy each Compose `.env.example` to a protected local deployment directory:
   - `vm-auth.env`, `vm-data.env`, `vm-ingest.env`, `vm-core.env`, and
     `vm-app.env` go in `SMARTTRAFFIC_SECRETS_DIR`.
   - Each VM also has a same-named subdirectory containing its mounted keys,
     certificates, truststores, and (for Auth) runtime configuration.
   - Never commit that directory, `.env` files, private keys, passwords, or
     certificates. Values marked `REPLACE_WITH_SECRET` are placeholders, not
     usable credentials.
5. Confirm the TLS certificate SANs match the private IPs used for Auth,
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

The playbook validates each Compose configuration and nftables file before
starting services. It builds each VM's required images with Docker Buildx,
then starts Compose with `--no-build` to keep image building and container
startup as separate explicit steps. It starts Auth first (brokers retrieve JWKS),
then data services and Kafka topic/ACLs, consumers/dashboard/query clients, and
finally the traffic simulator. Images are built on their destination VMs; size EC2 and EBS for the
JVM limits plus build-time memory. This workflow has been run against the
project's AWS deployment. The playbook result and live service/data-flow checks
are deployment evidence, but do not alone prove dashboard access, query/alert
behavior, or account-specific cost.

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
Héctor opens the dashboard locally through SSM port forwarding; no dashboard
port is exposed to the Internet.
