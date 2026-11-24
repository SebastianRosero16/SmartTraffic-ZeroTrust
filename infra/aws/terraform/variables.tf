variable "aws_region" {
  description = "AWS deployment region assigned to the SmartTraffic university demonstration."
  type        = string
  default     = "us-east-2"

  validation {
    condition     = var.aws_region == "us-east-2"
    error_message = "The SmartTraffic demonstration account is assigned to us-east-2 (Ohio)."
  }
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
  description = "Account-available Availability Zone name in us-east-2, selected after checking the AWS console."
  type        = string
  default     = ""

  validation {
    condition     = var.availability_zone == "" || can(regex("^us-east-2[a-z]$", var.availability_zone))
    error_message = "availability_zone must be empty or an Availability Zone name in us-east-2."
  }
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
  description = "Preliminary minimum Linux EC2 sizes for the low-load university demonstration."
  type        = map(string)
  default = {
    vm-auth   = "t3.small"
    vm-data   = "t3.large"
    vm-ingest = "t3.small"
    vm-core   = "t3.medium"
    vm-app    = "t3.small"
  }
}

variable "root_volume_sizes_gib" {
  description = "Encrypted gp3 root-volume size for each VM. EBS storage may incur charges."
  type        = map(number)
  default = {
    vm-auth   = 20
    vm-data   = 30
    vm-ingest = 20
    vm-core   = 20
    vm-app    = 20
  }

  validation {
    condition     = alltrue([for size in values(var.root_volume_sizes_gib) : size > 0])
    error_message = "Every root volume size must be greater than zero."
  }
}

variable "data_volume_sizes_gib" {
  description = "Separate encrypted gp3 data-volume sizes for PostgreSQL, Kafka, and RabbitMQ on vm-data."
  type        = map(number)
  default = {
    postgres = 30
    kafka    = 20
    rabbitmq = 10
  }

  validation {
    condition = (
      alltrue([for size in values(var.data_volume_sizes_gib) : size > 0]) &&
      length(setsubtract(toset(["postgres", "kafka", "rabbitmq"]), toset(keys(var.data_volume_sizes_gib)))) == 0 &&
      length(setsubtract(toset(keys(var.data_volume_sizes_gib)), toset(["postgres", "kafka", "rabbitmq"]))) == 0
    )
    error_message = "Provide positive data-volume sizes for exactly postgres, kafka, and rabbitmq."
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
        endswith(rule.peer_ipv4_cidr, "/32") &&
        (
          rule.direction != "ingress" ||
          can(regex("^(10\\.|192\\.168\\.|172\\.(1[6-9]|2[0-9]|3[01])\\.).*/32$", rule.peer_ipv4_cidr))
        ) &&
        (
          rule.direction != "egress" ||
          can(regex("^(10\\.|192\\.168\\.|172\\.(1[6-9]|2[0-9]|3[01])\\.).*/32$", rule.peer_ipv4_cidr)) ||
          (
            rule.protocol == "tcp" &&
            rule.from_port == 443 &&
            rule.to_port == 443
          )
        )
      )) &&
      (rule.protocol == "-1" || (
        rule.from_port >= 0 &&
        rule.to_port >= rule.from_port &&
        rule.to_port <= 65535
      ))
    ])
    error_message = "Network rules must target known VMs and use valid ports. IPv4 ingress peers must be RFC1918 /32s; public IPv4 egress peers may use only HTTPS (TCP 443)."
  }
}
