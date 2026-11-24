locals {
  vm_roles = toset([
    "vm-auth",
    "vm-data",
    "vm-ingest",
    "vm-core",
    "vm-app",
  ])

  data_volume_device_names = {
    postgres = "/dev/sdf"
    kafka    = "/dev/sdg"
    rabbitmq = "/dev/sdh"
  }

  rfc1918_ipv4_cidrs = [
    "10.0.0.0/8",
    "172.16.0.0/12",
    "192.168.0.0/16",
  ]

  ipv4_cidrs_for_checks = distinct(concat(
    compact([var.vpc_cidr]),
    values(var.subnet_cidrs),
    [for rule in var.network_rules : rule.peer_ipv4_cidr if rule.peer_ipv4_cidr != null],
    tolist(var.approved_private_peer_ipv4_cidrs),
    local.rfc1918_ipv4_cidrs,
  ))

  ipv4_cidr_parts = {
    for cidr in local.ipv4_cidrs_for_checks : cidr => {
      address = try(sum([
        for index, octet in split(".", split("/", cidr)[0]) :
        tonumber(octet) * pow(256, 3 - index)
      ]), -1)
      prefix = try(tonumber(split("/", cidr)[1]), -1)
    }
  }

  ipv4_cidr_ranges = {
    for cidr, parts in local.ipv4_cidr_parts : cidr => {
      start = (
        parts.address >= 0 &&
        parts.address <= 4294967295 &&
        parts.prefix >= 0 &&
        parts.prefix <= 32
      ) ? floor(parts.address / pow(2, 32 - parts.prefix)) * pow(2, 32 - parts.prefix) : -1
      end = (
        parts.address >= 0 &&
        parts.address <= 4294967295 &&
        parts.prefix >= 0 &&
        parts.prefix <= 32
        ) ? (
        floor(parts.address / pow(2, 32 - parts.prefix)) * pow(2, 32 - parts.prefix) +
        pow(2, 32 - parts.prefix) - 1
      ) : -1
    }
  }

  vpc_ipv4_range = lookup(local.ipv4_cidr_ranges, var.vpc_cidr, {
    start = -1
    end   = -1
  })

  vpc_cidr_is_private = anytrue([
    for cidr in local.rfc1918_ipv4_cidrs :
    local.vpc_ipv4_range.start >= local.ipv4_cidr_ranges[cidr].start &&
    local.vpc_ipv4_range.end <= local.ipv4_cidr_ranges[cidr].end
  ])

  subnet_ipv4_ranges = [
    for role, cidr in var.subnet_cidrs : {
      role  = role
      start = local.ipv4_cidr_ranges[cidr].start
      end   = local.ipv4_cidr_ranges[cidr].end
    }
  ]

  subnet_cidrs_within_vpc = {
    for subnet in local.subnet_ipv4_ranges : subnet.role => (
      subnet.start >= local.vpc_ipv4_range.start &&
      subnet.end <= local.vpc_ipv4_range.end
    )
  }

  subnet_cidr_ranges_do_not_overlap = flatten([
    for left_index, left in local.subnet_ipv4_ranges : [
      for right_index, right in local.subnet_ipv4_ranges :
      left_index >= right_index ||
      left.end < right.start ||
      right.end < left.start
    ]
  ])

  configured_ipv4_networks = compact(concat([var.vpc_cidr], values(var.subnet_cidrs)))

  rule_peer_is_private = {
    for index, rule in var.network_rules : tostring(index) => rule.peer_ipv4_cidr != null ? anytrue([
      for cidr in local.rfc1918_ipv4_cidrs :
      local.ipv4_cidr_ranges[rule.peer_ipv4_cidr].start >= local.ipv4_cidr_ranges[cidr].start &&
      local.ipv4_cidr_ranges[rule.peer_ipv4_cidr].start <= local.ipv4_cidr_ranges[cidr].end
    ]) : false
  }

  rule_peer_in_configured_network = {
    for index, rule in var.network_rules : tostring(index) => rule.peer_ipv4_cidr != null ? anytrue([
      for cidr in local.configured_ipv4_networks :
      local.ipv4_cidr_ranges[rule.peer_ipv4_cidr].start >= local.ipv4_cidr_ranges[cidr].start &&
      local.ipv4_cidr_ranges[rule.peer_ipv4_cidr].start <= local.ipv4_cidr_ranges[cidr].end
    ]) : false
  }

  approved_private_peer_is_external = {
    for cidr in var.approved_private_peer_ipv4_cidrs : cidr => (
      anytrue([
        for private_cidr in local.rfc1918_ipv4_cidrs :
        local.ipv4_cidr_ranges[cidr].start >= local.ipv4_cidr_ranges[private_cidr].start &&
        local.ipv4_cidr_ranges[cidr].start <= local.ipv4_cidr_ranges[private_cidr].end
      ]) &&
      !anytrue([
        for network_cidr in local.configured_ipv4_networks :
        local.ipv4_cidr_ranges[cidr].start >= local.ipv4_cidr_ranges[network_cidr].start &&
        local.ipv4_cidr_ranges[cidr].start <= local.ipv4_cidr_ranges[network_cidr].end
      ])
    )
  }
}

resource "terraform_data" "deployment_guard" {
  count = var.enable_resource_creation ? 1 : 0

  input = "SmartTraffic AWS deployment requested"

  lifecycle {
    precondition {
      condition     = var.cost_review_confirmed
      error_message = "Review the account plan, EC2 offer, EBS, public IPv4, networking, and data-transfer costs before enabling resource creation."
    }

    precondition {
      condition     = can(cidrnetmask(var.vpc_cidr))
      error_message = "Set an approved private IPv4 VPC CIDR before enabling resource creation."
    }

    precondition {
      condition     = local.vpc_cidr_is_private
      error_message = "The VPC CIDR must be contained in an RFC1918 private IPv4 range."
    }

    precondition {
      condition     = var.availability_zone != ""
      error_message = "Select an Availability Zone available to the account before enabling resource creation."
    }

    precondition {
      condition = (
        length(setsubtract(local.vm_roles, toset(keys(var.subnet_cidrs)))) == 0 &&
        length(setsubtract(toset(keys(var.subnet_cidrs)), local.vm_roles)) == 0
      )
      error_message = "Provide exactly one approved subnet CIDR for each of the five logical VMs."
    }

    precondition {
      condition     = alltrue(values(local.subnet_cidrs_within_vpc))
      error_message = "Every subnet CIDR must be wholly contained within the approved VPC CIDR."
    }

    precondition {
      condition     = alltrue(local.subnet_cidr_ranges_do_not_overlap)
      error_message = "Subnet CIDR ranges must not overlap."
    }

    precondition {
      condition = (
        length(setsubtract(local.vm_roles, toset(keys(var.instance_types)))) == 0 &&
        length(setsubtract(toset(keys(var.instance_types)), local.vm_roles)) == 0 &&
        alltrue([for instance_type in values(var.instance_types) : instance_type != ""])
      )
      error_message = "Provide an account-eligible EC2 instance type for each of the five logical VMs."
    }

    precondition {
      condition = (
        length(setsubtract(local.vm_roles, toset(keys(var.root_volume_sizes_gib)))) == 0 &&
        length(setsubtract(toset(keys(var.root_volume_sizes_gib)), local.vm_roles)) == 0
      )
      error_message = "Provide a reviewed root-volume size for each of the five logical VMs."
    }

    precondition {
      condition = (
        length(setsubtract(toset(["postgres", "kafka", "rabbitmq"]), toset(keys(var.data_volume_sizes_gib)))) == 0 &&
        length(setsubtract(toset(keys(var.data_volume_sizes_gib)), toset(["postgres", "kafka", "rabbitmq"]))) == 0
      )
      error_message = "Provide separate data-volume sizes for PostgreSQL, Kafka, and RabbitMQ."
    }

    precondition {
      condition = alltrue([
        for index, rule in var.network_rules :
        rule.peer_ipv4_cidr == null || !local.rule_peer_in_configured_network[tostring(index)]
      ])
      error_message = "Do not use peer_ipv4_cidr for an address inside the SmartTraffic VPC or any configured subnet; use peer_role for VM-to-VM communication."
    }

    precondition {
      condition = alltrue([
        for index, rule in var.network_rules :
        rule.peer_ipv4_cidr == null ||
        !local.rule_peer_is_private[tostring(index)] ||
        (
          var.allow_private_peer_ipv4_cidrs &&
          contains(var.approved_private_peer_ipv4_cidrs, rule.peer_ipv4_cidr)
        )
      ])
      error_message = "Private IPv4 peers outside the VPC require allow_private_peer_ipv4_cidrs = true and an exact approved_private_peer_ipv4_cidrs entry."
    }

    precondition {
      condition = alltrue([
        for cidr in var.approved_private_peer_ipv4_cidrs :
        local.approved_private_peer_is_external[cidr]
      ])
      error_message = "Approved private peers must be RFC1918 /32 addresses outside the SmartTraffic VPC and all configured subnets."
    }
  }
}

data "aws_ssm_parameter" "amazon_linux_2023" {
  count = var.enable_resource_creation ? 1 : 0

  name       = "/aws/service/ami-amazon-linux-latest/al2023-ami-kernel-default-x86_64"
  depends_on = [terraform_data.deployment_guard]
}

resource "aws_vpc" "smarttraffic" {
  count = var.enable_resource_creation ? 1 : 0

  cidr_block           = var.vpc_cidr
  enable_dns_support   = true
  enable_dns_hostnames = true

  tags = {
    Name      = "smarttraffic"
    Project   = "SmartTraffic"
    ManagedBy = "Terraform"
  }

  depends_on = [terraform_data.deployment_guard]
}

resource "aws_subnet" "vm" {
  for_each = var.enable_resource_creation ? var.subnet_cidrs : {}

  vpc_id                  = aws_vpc.smarttraffic[0].id
  cidr_block              = each.value
  availability_zone       = var.availability_zone
  map_public_ip_on_launch = true

  tags = {
    Name      = "smarttraffic-${each.key}"
    Tier      = "public"
    Project   = "SmartTraffic"
    ManagedBy = "Terraform"
  }
}

resource "aws_internet_gateway" "smarttraffic" {
  count = var.enable_resource_creation ? 1 : 0

  vpc_id = aws_vpc.smarttraffic[0].id

  tags = {
    Name      = "smarttraffic-igw"
    Project   = "SmartTraffic"
    ManagedBy = "Terraform"
  }
}

resource "aws_route_table" "public" {
  count = var.enable_resource_creation ? 1 : 0

  vpc_id = aws_vpc.smarttraffic[0].id

  tags = {
    Name      = "smarttraffic-public"
    Project   = "SmartTraffic"
    ManagedBy = "Terraform"
  }
}

resource "aws_route" "internet" {
  count = var.enable_resource_creation ? 1 : 0

  route_table_id         = aws_route_table.public[0].id
  destination_cidr_block = "0.0.0.0/0"
  gateway_id             = aws_internet_gateway.smarttraffic[0].id
}

resource "aws_route_table_association" "vm" {
  for_each = var.enable_resource_creation ? var.subnet_cidrs : {}

  subnet_id      = aws_subnet.vm[each.key].id
  route_table_id = aws_route_table.public[0].id
}

resource "aws_security_group" "vm" {
  for_each = var.enable_resource_creation ? { for role in local.vm_roles : role => role } : {}

  name        = "smarttraffic-${each.key}"
  description = "Default-deny security group for ${each.key}; add only reviewed network_rules."
  vpc_id      = aws_vpc.smarttraffic[0].id

  tags = {
    Name      = "smarttraffic-${each.key}"
    Project   = "SmartTraffic"
    ManagedBy = "Terraform"
  }
}

resource "aws_vpc_security_group_egress_rule" "https" {
  for_each = var.enable_resource_creation ? { for role in local.vm_roles : role => role } : {}

  security_group_id = aws_security_group.vm[each.key].id
  cidr_ipv4         = "0.0.0.0/0"
  ip_protocol       = "tcp"
  from_port         = 443
  to_port           = 443
  description       = "HTTPS egress for SSM, image downloads, and operating system updates."
}

resource "aws_vpc_security_group_ingress_rule" "approved" {
  for_each = {
    for index, rule in var.network_rules : tostring(index) => rule
    if var.enable_resource_creation && rule.direction == "ingress"
  }

  security_group_id = aws_security_group.vm[each.value.target_role].id
  ip_protocol       = each.value.protocol
  from_port         = each.value.protocol == "-1" ? null : each.value.from_port
  to_port           = each.value.protocol == "-1" ? null : each.value.to_port
  cidr_ipv4         = each.value.peer_ipv4_cidr
  referenced_security_group_id = (
    each.value.peer_role == null ? null : aws_security_group.vm[each.value.peer_role].id
  )
  description = each.value.description
}

resource "aws_iam_role" "ssm" {
  count = var.enable_resource_creation ? 1 : 0

  name = "smarttraffic-ec2-ssm"
  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect = "Allow"
      Action = "sts:AssumeRole"
      Principal = {
        Service = "ec2.amazonaws.com"
      }
    }]
  })

  tags = {
    Name      = "smarttraffic-ec2-ssm"
    Project   = "SmartTraffic"
    ManagedBy = "Terraform"
  }
}

resource "aws_iam_role_policy_attachment" "ssm_core" {
  count = var.enable_resource_creation ? 1 : 0

  role       = aws_iam_role.ssm[0].name
  policy_arn = "arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore"
}

resource "aws_iam_instance_profile" "ssm" {
  count = var.enable_resource_creation ? 1 : 0

  name = "smarttraffic-ec2-ssm"
  role = aws_iam_role.ssm[0].name

  tags = {
    Name      = "smarttraffic-ec2-ssm"
    Project   = "SmartTraffic"
    ManagedBy = "Terraform"
  }
}

resource "aws_vpc_security_group_egress_rule" "approved" {
  for_each = {
    for index, rule in var.network_rules : tostring(index) => rule
    if var.enable_resource_creation && rule.direction == "egress"
  }

  security_group_id = aws_security_group.vm[each.value.target_role].id
  ip_protocol       = each.value.protocol
  from_port         = each.value.protocol == "-1" ? null : each.value.from_port
  to_port           = each.value.protocol == "-1" ? null : each.value.to_port
  cidr_ipv4         = each.value.peer_ipv4_cidr
  referenced_security_group_id = (
    each.value.peer_role == null ? null : aws_security_group.vm[each.value.peer_role].id
  )
  description = each.value.description
}

resource "aws_instance" "vm" {
  for_each = var.enable_resource_creation ? { for role in local.vm_roles : role => role } : {}

  ami                         = data.aws_ssm_parameter.amazon_linux_2023[0].value
  instance_type               = var.instance_types[each.key]
  subnet_id                   = aws_subnet.vm[each.key].id
  vpc_security_group_ids      = [aws_security_group.vm[each.key].id]
  associate_public_ip_address = true
  iam_instance_profile        = aws_iam_instance_profile.ssm[0].name

  root_block_device {
    volume_type           = "gp3"
    volume_size           = var.root_volume_sizes_gib[each.key]
    encrypted             = true
    delete_on_termination = true
  }

  metadata_options {
    http_endpoint = "enabled"
    http_tokens   = "required"
  }

  tags = {
    Name      = "smarttraffic-${each.key}"
    Project   = "SmartTraffic"
    ManagedBy = "Terraform"
  }

  depends_on = [
    terraform_data.deployment_guard,
    aws_vpc_security_group_egress_rule.https,
    aws_vpc_security_group_egress_rule.approved,
  ]
}

resource "aws_ebs_volume" "data" {
  for_each = var.enable_resource_creation ? var.data_volume_sizes_gib : {}

  availability_zone = var.availability_zone
  size              = each.value
  type              = "gp3"
  encrypted         = true

  tags = {
    Name      = "smarttraffic-data-${each.key}"
    Project   = "SmartTraffic"
    ManagedBy = "Terraform"
  }

  lifecycle {
    prevent_destroy = true
  }
}

resource "aws_volume_attachment" "data" {
  for_each = var.enable_resource_creation ? var.data_volume_sizes_gib : {}

  device_name = local.data_volume_device_names[each.key]
  volume_id   = aws_ebs_volume.data[each.key].id
  instance_id = aws_instance.vm["vm-data"].id
}
