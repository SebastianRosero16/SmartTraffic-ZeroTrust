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
- The module does not assign public IPv4 addresses, create internet routes, or
  open inbound or outbound security-group rules by default.
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
- Instance types, EBS sizes, Availability Zone, VPC/subnet CIDRs, and every
  network rule must be supplied explicitly. No AWS credentials, keys, or
  account identifiers belong in this repository.
- When resource creation is enabled, the VPC CIDR must be a canonical private
  IPv4 range within AWS's supported `/16` through `/28` size. Each subnet CIDR
  must be canonical, within that VPC, use an AWS-supported size, and not
  overlap another subnet.
  Requiring RFC1918 space for the VPC is a deliberate SmartTraffic security
  policy and is more restrictive than AWS's general CIDR requirements.

Do not enable resource creation until the account is confirmed to have access
to EC2 and the actual account-specific cost of EC2, EBS, addresses, networking,
and data transfer has been reviewed. The AWS Free Tier page and the earlier
750-hour offer must not be assumed to apply to this account. The five EC2
instances cannot be left running continuously under a shared 750-instance-hour
allowance without exceeding it.

The private-only network scaffold is not yet a complete runnable deployment:
the team still needs to approve the communication matrix, a management and
software-download path, and the corresponding AWS and `nftables` rules. Ansible
configuration and application Compose deployment should be added only after
those choices and the guest OS are confirmed.

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
