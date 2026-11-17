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
- The approved demonstration network uses one public subnet per VM in one
  selected `us-east-2` Availability Zone, one Internet Gateway, and one public
  route table. Each instance receives a dynamic public IPv4 address; no NAT
  Gateway or Elastic IP is created.
- Security Groups have no ingress rules by default. The only built-in egress
  is TCP 443 to IPv4 Internet destinations for SSM, image downloads, and OS
  updates. Security Groups cannot restrict destinations by DNS name, so this
  is port-restricted but not FQDN-restricted. No SSH, dashboard, Kafka,
  RabbitMQ, or PostgreSQL ingress from the Internet is configured.
- The shared EC2 role attaches `AmazonSSMManagedInstanceCore` and is assigned
  to all five instances through an instance profile. Héctor must still use an
  appropriately scoped IAM identity; no user credentials or access keys are
  stored in Terraform.
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

## Approved demonstration design

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
- Administration and dashboard presentation use Session Manager. Use
  port-forwarding from Héctor's laptop after the backend's production port is
  confirmed. No SSH or public dashboard ingress rule is configured; Ansible
  uses an operator key over an SSM-initiated SSH tunnel.
- VM-to-VM flows must use private addresses and role-scoped Security Groups.
  Until production listeners and ports are approved, no application ingress
  rules are created. Kafka, RabbitMQ, and PostgreSQL are never exposed to
  Internet sources.
- One AZ is a cost-conscious demo topology, not high availability. An AZ
  outage interrupts every VM; the brokers and database are single-node and
  have no cross-AZ failover.

The source matrix is [docs/network-matrix.md](../../docs/network-matrix.md).
Production ports are still unconfirmed: Auth token/JWKS listeners; Kafka client
and broker/controller listeners; RabbitMQ AMQPS; PostgreSQL; and the dashboard
HTTP/WebSocket listener. Values such as fixture ports `5671`, `5432`, `5433`,
`8080`, and `9092`/`9093` are not approved production assignments. Do not add
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

All root volumes are encrypted gp3. `vm-data` also receives three separately
managed, encrypted gp3 data volumes: PostgreSQL 30 GiB, Kafka 20 GiB, and
RabbitMQ 10 GiB. EBS is billed while volumes exist even if EC2 is stopped.
The data volumes use Terraform `prevent_destroy`; deliberate retention and
recovery work is required before their removal.

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
connectivity. No real Terraform plan or AWS operation is part of this PR.
