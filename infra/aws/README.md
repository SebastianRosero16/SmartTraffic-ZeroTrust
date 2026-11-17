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
- The five VM subnets do not assign public IPv4 addresses. Resource creation
  also requires the explicit `enable_temporary_nat_egress = true` choice and
  an approved NAT subnet CIDR; this creates one public subnet, an Internet
  Gateway, an Elastic IP, a NAT Gateway, and private default routes. NAT and
  public IPv4 charges continue while those resources exist.
- The temporary NAT option adds only outbound TCP 443 rules for the five VM
  security groups. It does not open inbound access or general-purpose egress.
- A shared EC2 instance role grants `AmazonSSMManagedInstanceCore` so the
  private instances can be administered through Systems Manager. The operator
  still needs their own AWS identity and Session Manager permissions.
- Security-group rules are managed only with the standalone ingress/egress
  rule resources. Do not add inline `ingress` or `egress` blocks to
  `aws_security_group`. The locked AWS Provider 5.100.0 removes AWS's default
  allow-all egress rule when creating a group; only explicitly declared
  egress rules are added.
- The NAT option is for temporary SSM and software provisioning access, not a
  permanent public service path. Do not enable it until the expected runtime
  and NAT/data-transfer cost have been reviewed.
- VM-to-VM rules must use security-group references (`peer_role`). External
  IPv4 peers are restricted to individual `/32` addresses; subnet CIDRs must
  not be added without an explicit design review. A peer IP inside the VPC or
  any configured subnet is rejected.
- External RFC1918 peers, such as a reviewed VPN endpoint, require both
  `allow_private_peer_ipv4_cidrs = true` and an exact
  `approved_private_peer_ipv4_cidrs` `/32` entry. This option is disabled by
  default and can never authorize an address inside the VPC or its subnets.
- Instance types, EBS sizes, Availability Zone, VPC/subnet CIDRs, and every
  network rule must be supplied explicitly. No AWS credentials, keys, or
  account identifiers belong in this repository.

Do not enable resource creation until account access and the actual
account-specific cost of EC2, EBS, public IPv4, NAT Gateway, and data transfer
have been reviewed. Credits are not a guarantee of zero cost after they expire
or are exhausted.

The [Ansible deployment](../deployment/README.md) installs Docker, configures
host `nftables` with default-drop policies, and deploys a separate Compose
project to each VM. Containers use host networking so every VM's host firewall
can enforce the project's per-VM communication policy; no application port is
published to the public Internet. The production listeners match the exercise
(`9092`, `5672`, `5432`, and HTTPS Auth `443`); local fixture ports remain
separate.

Ansible connects from the operator workstation through an SSM SSH tunnel. The
EC2 key pair contains only the operator's public key; the private key stays
local. Port 22 remains closed in AWS Security Groups. The presenter can use a
separate IAM identity and SSM port forwarding for the dashboard without
receiving this SSH private key or the account credentials.

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
contact AWS and cannot prove the live behavior of Security Groups.
