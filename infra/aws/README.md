# AWS deployment preparation

This directory is the AWS-specific infrastructure entry point for the
five-machine SmartTraffic deployment:

| Terraform role | Project VM |
|---|---|
| `vm-auth` | Auth Server |
| `vm-data` | Kafka, RabbitMQ, PostgreSQL |
| `vm-ingest` | Traffic sensor simulator |
| `vm-core` | Traffic density processor and traffic archiver |
| `vm-app` | Dashboard backend and web application |

## Safety status

The Terraform module is deliberately disabled by default:

- `enable_resource_creation` defaults to `false`; with that value the module
  creates no AWS resources.
- Enabling creation also requires `cost_review_confirmed = true`. This is a
  deliberate human gate, not a pricing guarantee or a substitute for checking
  the AWS account.
- The Terraform network design uses one public subnet per VM in one
  selected `us-east-2` Availability Zone, one Internet Gateway, and one public
  route table. Each instance receives a dynamic public IPv4 address; no NAT
  Gateway or Elastic IP is created.
- The Terraform module defines no ingress rules by default. Its built-in
  egress is TCP 443 to IPv4 Internet destinations for SSM, image downloads,
  and OS updates. Security Groups cannot restrict destinations by DNS name,
  so the rule is port-restricted, not FQDN-restricted. No SSH or public
  dashboard ingress is required for the tested SSM tunnel.
- A read-only AWS console review on 17 November 2026 confirmed that the live
  `smarttraffic` VPC differs from the Terraform network design: a route table
  named `smarttraffic-private` is associated with five subnets and sends
  `0.0.0.0/0` to an Available public NAT Gateway. The NAT Gateway's
  `smarttraffic-nat` subnet has a public route to the Internet Gateway.
  Terraform instead associates the VM subnets with one public route table and
  sends their default route directly to the Internet Gateway; it defines no
  NAT Gateway. Do not apply Terraform until its state and plan are reconciled
  with these live resources, data recovery, and cost.
- The local state matches the live network details inspected in the console:
  the VPC and NAT Gateway, all six subnet IDs/CIDRs/AZs, both route tables, the
  private default route, and the subnet associations. This does not verify all
  69 state addresses. The repository HCL still describes a public-subnet design
  without NAT. A complete read-only plan using `terraform.tfvars.example`
  finished with 0 to add, 0 to change, and 68 to destroy: the example sets
  `enable_resource_creation = false` while the state contains managed
  resources. Do not apply this plan. No AWS resources were modified.
- The five live Security Groups each allow TCP 443 to `0.0.0.0/0`, described
  as temporary HTTPS egress. Other egress is role-scoped: `vm-data` to Auth
  443 and DNS; `vm-ingest` to Auth 443 and `vm-data` 9092 plus DNS; `vm-core`
  to Auth 443 and `vm-data` 9092/5672/5432 plus DNS; `vm-app` to Auth 443 and
  `vm-data` 5672 plus DNS; `vm-auth` to DNS. `nftables` on all five VMs uses
  default-drop policies, with TCP 443 permitted to destinations outside the
  VPC CIDR. This is observed configuration, not approval of unrestricted
  HTTPS egress.
- The shared EC2 role attaches `AmazonSSMManagedInstanceCore` and is assigned
  to all five instances through an instance profile. An SSM port-forward from
  the presenter's laptop to `vm-app` succeeded; the IAM policy's exact resource
  scope was not reviewed, so least-privilege access remains unverified. No user
  credentials or access keys are stored in Terraform.
- Security-group rules are managed only with the standalone ingress/egress
  rule resources. Do not add inline `ingress` or `egress` blocks to
  `aws_security_group`. The locked AWS Provider 5.100.0 removes AWS's default
  allow-all egress rule when creating a group; only explicitly declared
  egress rules are added.
- VM-to-VM rules must use security-group references (`peer_role`). External
  IPv4 peers are restricted to individual `/32` addresses; subnet CIDRs must
  not be added without an explicit design review. A peer IP inside the VPC or
  any configured subnet is rejected.
- External RFC1918 peers, such as a reviewed VPN endpoint, require both
  `allow_private_peer_ipv4_cidrs = true` and an exact
  `approved_private_peer_ipv4_cidrs` `/32` entry. This option is disabled by
  default and can never authorize an address inside the VPC or its subnets.
- The preliminary minimum EC2 sizes and encrypted gp3 root/data-volume sizes
  are set as defaults. The selected Availability Zone, VPC CIDR, and exactly
  five non-overlapping subnet CIDRs must still be supplied after checking the
  account and approving the address plan. No AWS credentials, keys, or account
  identifiers belong in this repository.
- When resource creation is enabled, the VPC CIDR must be a canonical private
  IPv4 range within AWS's supported `/16` through `/28` size. Each subnet CIDR
  must be canonical, within that VPC, use an AWS-supported size, and not
  overlap another subnet.
  Requiring RFC1918 space for the VPC is a deliberate SmartTraffic security
  policy and is more restrictive than AWS's general CIDR requirements.

Do not enable resource creation until the account is confirmed to have access
to EC2 and the actual account-specific cost of EC2, EBS, public IPv4, networking,
and data transfer has been reviewed. The US$100 credit is a one-time balance,
not a monthly allowance. EBS eligibility is unconfirmed and is excluded from
credit assumptions.

## Terraform network design

- Region: US East (Ohio), `us-east-2`; exactly one account-available
  Availability Zone.
- AWS's regional-services list includes EC2, EBS, VPC, and Systems Manager as
  core services for AWS Regions. AWS lists three AZs in `us-east-2`, but the
  account-visible AZ name must be confirmed in the console. Keep
  `availability_zone` unset until that check; do not assume a particular AZ
  suffix.
- Five role-specific subnets and Security Groups: `vm-auth`, `vm-data`,
  `vm-ingest`, `vm-core`, and `vm-app`.
- Dynamic public IPv4 addresses and outbound Internet Gateway routing support
  SSM, Docker image downloads, and OS updates. No NAT Gateway is needed.
- Administration and dashboard presentation use Session Manager. The live
  demonstration reached the dashboard listener on `vm-app:8080` through a
  local port-forward; this does not add or require public dashboard ingress.
  Ansible uses an operator key over an SSM-initiated SSH tunnel.
- VM-to-VM flows must use private addresses and role-scoped Security Groups.
  The Terraform defaults contain no application ingress rules. The live
  demonstration showed application flows working, and the active AWS
  Security Groups were subsequently inspected: `vm-app`, `vm-core`, and
  `vm-ingest` had no ingress rules; `vm-auth` allowed TCP 443 from role
  Security Groups, while `vm-data` allowed Kafka 9092, PostgreSQL 5432, and
  RabbitMQ 5672 from role-specific sources. `nftables` on all five VMs
  confirmed default-drop chains plus broad HTTPS egress outside the VPC CIDR.
  RabbitMQ's live listener reported
  `amqp/ssl` on port 5672, while PostgreSQL's runtime `SHOW ssl` returned
  `off`. Kafka on `vm-data` and Auth Server on `vm-auth` negotiated TLS 1.3,
  but OpenSSL did not trust the presented self-signed CA. Runtime inspection
  found only 30 GiB root XFS disks on `vm-data`, `vm-core`, `vm-ingest`, and
  `vm-auth`; the dedicated data volumes remain unobserved.
  Reconcile the effective AWS rules with Terraform before
  claiming reproducibility. Kafka, RabbitMQ, and PostgreSQL must not be
  exposed to Internet sources.
- One AZ is a cost-conscious demo topology, not high availability. An AZ
  outage interrupts every VM; the brokers and database are single-node and
  have no cross-AZ failover.

The source matrix is [docs/network-matrix.md](../../docs/network-matrix.md).
The dashboard listener on `vm-app:127.0.0.1:8080` returned HTTP 302 to
`/dashboard.html`; HTTPS/TLS did not negotiate on that port. It was reached
through the SSM demonstration, but this is not approval for public ingress.
Production assignments remain
unconfirmed for Auth token/JWKS, Kafka client and broker/controller listeners,
RabbitMQ AMQPS, and PostgreSQL. Values such as fixture ports `5671`, `5432`,
`5433`, and `9092`/`9093` are not confirmed production assignments. Do not add
them to `network_rules` until the service owners confirm listeners, TLS names,
and client-to-service flows. The required SSM egress port is TCP 443. See
[AWS services by Region](https://aws.amazon.com/about-aws/global-infrastructure/regional-product-services/)
and [AWS Availability Zones](https://docs.aws.amazon.com/global-infrastructure/latest/regions/aws-availability-zones.html).

## Compute, storage, and recovery

The preliminary minimum Linux sizes are `t3.small` for `vm-auth`,
`vm-ingest`, and `vm-app`; `t3.large` for `vm-data`; and `t3.medium` for
`vm-core`. These are demo starting points, not load-tested production
recommendations. `vm-data` hosts Kafka, RabbitMQ, and PostgreSQL together and
is a single point of failure.

All five live root volumes observed in the AWS console are encrypted gp3,
with 30 GiB attached to their respective instances. This is not the complete
Terraform design: `vm-data` was designed to receive three separately managed
data volumes—PostgreSQL 30 GiB, Kafka 20 GiB, and RabbitMQ 10 GiB—but those
separate volumes were not attached in the inspected live state. EBS is billed
while volumes exist even if EC2 is stopped.

The effective Docker CPU and memory limits were checked with `docker inspect`
on all 11 running containers:

| VM | Container(s) | CPU limit | Memory limit |
|---|---|---:|---:|
| `vm-auth` | Auth Server | 0.5 | 768 MiB |
| `vm-data` | Kafka | 1 | 3 GiB |
| `vm-data` | RabbitMQ | 0.5 | 1 GiB |
| `vm-data` | PostgreSQL | 0.25 | 1 GiB |
| `vm-ingest` | Traffic sensor simulator | 0.5 | 768 MiB |
| `vm-core` | Traffic archiver | 0.5 | 1 GiB |
| `vm-core` | Traffic density processor | 1 | 1.5 GiB |
| `vm-app` | Dashboard backend | 0.5 | 768 MiB |
| `vm-app` | Alert dispatcher | 0.5 | 768 MiB |
| `vm-app` | Each of two query clients | 0.25 | 384 MiB |

These are configured limits observed in the running containers. `docker stats`
usage values were point-in-time samples and are not capacity or load-test
results.

Ansible identifies attached volumes by their EBS-derived NVMe device paths and
mounts existing `ext4` or `xfs` filesystems persistently at
`/srv/smarttraffic/postgres`, `/srv/smarttraffic/kafka`, and
`/srv/smarttraffic/rabbitmq`; Compose binds each service's own data directory
to its corresponding mount. The playbook never formats a volume and fails if
a device is absent or has no supported filesystem. Prepare a verified blank
volume manually before deployment; never format a volume that may contain
data. Back up and migrate existing Docker-volume data before setting
`smarttraffic_data_cutover_confirmed: true`; without that confirmation,
Ansible refuses to change hosts. Before replacing or terminating `vm-data`,
stop the containers cleanly and verify a backup. Reattach a retained volume to
a replacement instance in the same AZ; for recovery in another AZ, restore a
snapshot into a new volume there. Snapshots and application-consistent
PostgreSQL backups have separate storage costs, and restore procedures must be
tested. The EBS mount and guarded cutover changes have not been exercised
against live AWS infrastructure.

## Approximate costs

Planning figures for one VM-hour per running instance in `us-east-2` (Ohio),
Linux On-Demand, excluding taxes and usage-based charges. The EC2 T3 and gp3
rates below are budgeting assumptions, not a live Ohio quote; confirm the
account-specific amounts in the [AWS Pricing Calculator](https://calculator.aws/)
before enabling creation:

- Five dynamic public IPv4 addresses: about US$4.33 for 173 hours or US$18.25
  for 730 hours, using US$0.005 per address-hour (AWS lists this IPv4 rate
  across commercial regions).
- The minimum EC2 mix above: about US$32.52 for 173 hours or US$137.24 for
  730 hours, using planning rates of about US$0.0209/hour per `t3.small`,
  US$0.0418/hour per `t3.medium`, and US$0.0835/hour per `t3.large`, before any
  T3 surplus CPU-credit charges.
- 170 GiB of gp3 volumes (110 GiB root plus 60 GiB data): about US$13.60 per
  30-day month, using a planning rate of US$0.08/GiB-month, including when
  instances are stopped.
- Approximate combined subtotal: US$50.45 at 173 EC2 hours with a full-month
  EBS allocation, or US$169.09 at 24/7. This excludes data transfer, snapshots,
  CloudWatch/S3 logs, taxes, and other account-specific charges.

These are estimates, not an AWS quote. Review current [EC2](https://aws.amazon.com/ec2/instance-types/t3/),
[EBS](https://aws.amazon.com/ebs/pricing/), and
[VPC](https://aws.amazon.com/vpc/pricing/) prices and the account's credit
terms before enabling creation.

## Validation without AWS

Install Terraform locally, then from `infra/aws/terraform` run:

```powershell
terraform fmt -check -recursive
terraform init -backend=false
terraform validate
terraform test
```

These commands do not create infrastructure. Do not run `terraform apply`
while `enable_resource_creation` is false or before the cost and network
reviews are approved. Terraform state and filled-in `.tfvars` files must remain
local and must never be committed.

The AWS mock tests verify planned Terraform configuration only. They do not
contact AWS and cannot prove live behavior, account service quotas, AZ
availability, public-repository access, SSM registration, or application
connectivity. A read-only plan against AWS using the example variables
completed, but proposed 68 destructions as described above; do not apply it.
No AWS resources were modified.
