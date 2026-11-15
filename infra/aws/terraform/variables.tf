variable "aws_region" {
  description = "AWS region selected after checking account eligibility and service limits."
  type        = string
}

variable "enable_resource_creation" {
  description = "Must remain false until the account and all resource costs are confirmed."
  type        = bool
  default     = false
}

variable "cost_review_confirmed" {
  description = "Explicit acknowledgement that current account pricing and free allowances were checked."
  type        = bool
  default     = false
}

variable "availability_zone" {
  description = "Availability Zone selected for the single-AZ student deployment."
  type        = string
  default     = ""
}

variable "vpc_cidr" {
  description = "Approved private IPv4 range for the SmartTraffic VPC."
  type        = string
  default     = ""

  validation {
    condition = var.vpc_cidr == "" || try(
      can(cidrnetmask(var.vpc_cidr)) &&
      tonumber(split("/", var.vpc_cidr)[1]) >= 16 &&
      tonumber(split("/", var.vpc_cidr)[1]) <= 28 &&
      cidrhost(var.vpc_cidr, 0) == split("/", var.vpc_cidr)[0],
      false
    )
    error_message = "vpc_cidr must be a canonical IPv4 network within the AWS-supported /16 through /28 range."
  }
}

variable "subnet_cidrs" {
  description = "Approved subnet CIDR for each logical VM; values must not overlap."
  type        = map(string)
  default     = {}

  validation {
    condition = alltrue([
      for cidr in values(var.subnet_cidrs) : try(
        can(cidrnetmask(cidr)) &&
        tonumber(split("/", cidr)[1]) >= 16 &&
        tonumber(split("/", cidr)[1]) <= 28 &&
        cidrhost(cidr, 0) == split("/", cidr)[0],
        false
      )
    ])
    error_message = "Every subnet_cidrs value must be a canonical IPv4 network within the AWS-supported /16 through /28 range."
  }
}

variable "instance_types" {
  description = "EC2 instance type for each logical VM, selected only after checking account-specific free eligibility."
  type        = map(string)
  default     = {}
}

variable "root_volume_sizes_gib" {
  description = "Encrypted gp3 root-volume size for each VM. EBS storage may incur charges."
  type        = map(number)
  default     = {}

  validation {
    condition     = alltrue([for size in values(var.root_volume_sizes_gib) : size > 0])
    error_message = "Every root volume size must be greater than zero."
  }
}

variable "allow_private_peer_ipv4_cidrs" {
  description = "Explicitly enable individually approved RFC1918 /32 peers outside the SmartTraffic VPC and subnets, such as a reviewed VPN endpoint."
  type        = bool
  default     = false
}

variable "approved_private_peer_ipv4_cidrs" {
  description = "Exact RFC1918 /32 addresses approved for external private-network peers. These can never overlap the SmartTraffic VPC or its subnets."
  type        = set(string)
  default     = []

  validation {
    condition = alltrue([
      for cidr in var.approved_private_peer_ipv4_cidrs :
      can(cidrnetmask(cidr)) && endswith(cidr, "/32")
    ])
    error_message = "Every approved private peer must be a valid single-host IPv4 /32 CIDR."
  }
}

variable "network_rules" {
  description = "Explicit least-privilege rules. Use peer_role for VM-to-VM flows; external IPv4 peers must be single-host /32 CIDRs."
  type = list(object({
    direction      = string
    target_role    = string
    protocol       = string
    from_port      = number
    to_port        = number
    peer_role      = optional(string)
    peer_ipv4_cidr = optional(string)
    description    = string
  }))
  default = []

  validation {
    condition = alltrue([
      for rule in var.network_rules :
      contains(["ingress", "egress"], rule.direction) &&
      contains(["vm-auth", "vm-data", "vm-ingest", "vm-core", "vm-app"], rule.target_role) &&
      contains(["-1", "tcp", "udp", "icmp"], rule.protocol) &&
      ((rule.peer_role != null) != (rule.peer_ipv4_cidr != null)) &&
      (rule.peer_role == null || contains(["vm-auth", "vm-data", "vm-ingest", "vm-core", "vm-app"], rule.peer_role)) &&
      (rule.peer_ipv4_cidr == null || (
        can(cidrnetmask(rule.peer_ipv4_cidr)) &&
        endswith(rule.peer_ipv4_cidr, "/32")
      )) &&
      (rule.protocol == "-1" || (
        rule.from_port >= 0 &&
        rule.to_port >= rule.from_port &&
        rule.to_port <= 65535
      ))
    ])
    error_message = "Network rules must target known VMs, use one peer VM or a single-host IPv4 /32 CIDR, and use valid ports."
  }
}
