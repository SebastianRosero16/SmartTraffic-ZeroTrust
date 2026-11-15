mock_provider "aws" {
  override_during = plan

  mock_data "aws_ssm_parameter" {
    defaults = {
      value = "ami-0123456789abcdef0"
    }
  }

  mock_resource "aws_security_group" {
    defaults = {
      ingress = []
      egress  = []
    }
  }
}

run "resource_creation_is_disabled_by_default" {
  command = plan

  variables {
    aws_region = "us-east-2"
  }

  assert {
    condition     = length(aws_vpc.smarttraffic) == 0
    error_message = "The default plan must not create a VPC."
  }

  assert {
    condition     = length(aws_subnet.vm) == 0
    error_message = "The default plan must not create subnets."
  }

  assert {
    condition     = length(aws_security_group.vm) == 0
    error_message = "The default plan must not create security groups."
  }

  assert {
    condition     = length(aws_instance.vm) == 0
    error_message = "The default plan must not create EC2 instances."
  }
}

run "resource_creation_requires_cost_review" {
  command = plan

  variables {
    aws_region               = "us-east-2"
    enable_resource_creation = true
  }

  expect_failures = [
    terraform_data.deployment_guard,
  ]
}

run "rejects_all_ipv4_cidr" {
  command = plan

  variables {
    aws_region = "us-east-2"
    network_rules = [
      {
        direction      = "ingress"
        target_role    = "vm-app"
        protocol       = "tcp"
        from_port      = 8080
        to_port        = 8080
        peer_ipv4_cidr = "0.0.0.0/0"
        description    = "Reject all IPv4 sources"
      }
    ]
  }

  expect_failures = [
    var.network_rules,
  ]
}

run "rejects_two_ranges_covering_all_ipv4" {
  command = plan

  variables {
    aws_region = "us-east-2"
    network_rules = [
      {
        direction      = "ingress"
        target_role    = "vm-app"
        protocol       = "tcp"
        from_port      = 8080
        to_port        = 8080
        peer_ipv4_cidr = "0.0.0.0/1"
        description    = "Reject first half of IPv4"
      },
      {
        direction      = "ingress"
        target_role    = "vm-app"
        protocol       = "tcp"
        from_port      = 8080
        to_port        = 8080
        peer_ipv4_cidr = "128.0.0.0/1"
        description    = "Reject second half of IPv4"
      }
    ]
  }

  expect_failures = [
    var.network_rules,
  ]
}

run "rejects_external_subnet_cidr" {
  command = plan

  variables {
    aws_region = "us-east-2"
    network_rules = [
      {
        direction      = "ingress"
        target_role    = "vm-app"
        protocol       = "tcp"
        from_port      = 8080
        to_port        = 8080
        peer_ipv4_cidr = "192.0.2.0/24"
        description    = "External subnets require explicit review"
      }
    ]
  }

  expect_failures = [
    var.network_rules,
  ]
}

run "accepts_vm_peers_and_single_host_cidrs" {
  command = plan

  variables {
    aws_region = "us-east-2"
    network_rules = [
      {
        direction   = "ingress"
        target_role = "vm-data"
        protocol    = "tcp"
        from_port   = 9094
        to_port     = 9094
        peer_role   = "vm-ingest"
        description = "Allow the ingest VM to reach Kafka"
      },
      {
        direction      = "ingress"
        target_role    = "vm-app"
        protocol       = "tcp"
        from_port      = 8080
        to_port        = 8080
        peer_ipv4_cidr = "192.0.2.10/32"
        description    = "Allow a single reviewed client address"
      },
      {
        direction      = "ingress"
        target_role    = "vm-app"
        protocol       = "tcp"
        from_port      = 8080
        to_port        = 8080
        peer_ipv4_cidr = "198.51.100.27/32"
        description    = "Allow another single reviewed client address"
      }
    ]
  }
}

run "creates_five_role_scoped_groups_and_peer_rules" {
  command = plan

  variables {
    aws_region               = "us-east-2"
    enable_resource_creation = true
    cost_review_confirmed    = true
    availability_zone        = "us-east-2a"
    vpc_cidr                 = "10.10.0.0/16"
    subnet_cidrs = {
      vm-auth   = "10.10.1.0/24"
      vm-data   = "10.10.2.0/24"
      vm-ingest = "10.10.3.0/24"
      vm-core   = "10.10.4.0/24"
      vm-app    = "10.10.5.0/24"
    }
    instance_types = {
      vm-auth   = "t3.micro"
      vm-data   = "t3.micro"
      vm-ingest = "t3.micro"
      vm-core   = "t3.micro"
      vm-app    = "t3.micro"
    }
    root_volume_sizes_gib = {
      vm-auth   = 8
      vm-data   = 8
      vm-ingest = 8
      vm-core   = 8
      vm-app    = 8
    }
    allow_private_peer_ipv4_cidrs    = true
    approved_private_peer_ipv4_cidrs = ["172.20.5.10/32"]
    network_rules = [
      {
        direction   = "ingress"
        target_role = "vm-data"
        protocol    = "tcp"
        from_port   = 9094
        to_port     = 9094
        peer_role   = "vm-ingest"
        description = "Ingest to Kafka"
      },
      {
        direction   = "egress"
        target_role = "vm-ingest"
        protocol    = "tcp"
        from_port   = 9094
        to_port     = 9094
        peer_role   = "vm-data"
        description = "Ingest to Kafka"
      },
      {
        direction      = "ingress"
        target_role    = "vm-app"
        protocol       = "tcp"
        from_port      = 8080
        to_port        = 8080
        peer_ipv4_cidr = "172.20.5.10/32"
        description    = "Reviewed VPN client"
      }
    ]
  }

  assert {
    condition     = length(aws_security_group.vm) == 5
    error_message = "The plan must create five role-specific Security Groups."
  }

  assert {
    condition     = toset(keys(aws_security_group.vm)) == local.vm_roles
    error_message = "Every logical VM role must have exactly one Security Group."
  }

  assert {
    condition     = length(aws_instance.vm) == 5
    error_message = "The plan must create one VM for each of the five roles."
  }

  assert {
    condition = alltrue([
      for role in local.vm_roles :
      toset(aws_instance.vm[role].vpc_security_group_ids) == toset([aws_security_group.vm[role].id])
    ])
    error_message = "Each VM must be associated only with its matching role Security Group."
  }

  assert {
    condition = (
      aws_vpc_security_group_ingress_rule.approved["0"].referenced_security_group_id ==
      aws_security_group.vm["vm-ingest"].id
    )
    error_message = "The ingress peer_role must resolve to the referenced VM Security Group."
  }

  assert {
    condition = (
      aws_vpc_security_group_egress_rule.approved["1"].referenced_security_group_id ==
      aws_security_group.vm["vm-data"].id
    )
    error_message = "The egress peer_role must resolve to the referenced VM Security Group."
  }
}

run "rejects_peer_inside_vpc_even_outside_subnets" {
  command = plan

  variables {
    aws_region               = "us-east-2"
    enable_resource_creation = true
    cost_review_confirmed    = true
    availability_zone        = "us-east-2a"
    vpc_cidr                 = "10.10.0.0/16"
    subnet_cidrs = {
      vm-auth   = "10.10.1.0/24"
      vm-data   = "10.10.2.0/24"
      vm-ingest = "10.10.3.0/24"
      vm-core   = "10.10.4.0/24"
      vm-app    = "10.10.5.0/24"
    }
    instance_types = {
      vm-auth   = "t3.micro"
      vm-data   = "t3.micro"
      vm-ingest = "t3.micro"
      vm-core   = "t3.micro"
      vm-app    = "t3.micro"
    }
    root_volume_sizes_gib = {
      vm-auth   = 8
      vm-data   = 8
      vm-ingest = 8
      vm-core   = 8
      vm-app    = 8
    }
    allow_private_peer_ipv4_cidrs    = true
    approved_private_peer_ipv4_cidrs = ["10.10.250.10/32"]
    network_rules = [
      {
        direction      = "ingress"
        target_role    = "vm-app"
        protocol       = "tcp"
        from_port      = 8080
        to_port        = 8080
        peer_ipv4_cidr = "10.10.250.10/32"
        description    = "Must use a VM role reference"
      }
    ]
  }

  expect_failures = [
    terraform_data.deployment_guard,
  ]
}

run "rejects_peer_inside_subnet" {
  command = plan

  variables {
    aws_region               = "us-east-2"
    enable_resource_creation = true
    cost_review_confirmed    = true
    availability_zone        = "us-east-2a"
    vpc_cidr                 = "10.10.0.0/16"
    subnet_cidrs = {
      vm-auth   = "10.10.1.0/24"
      vm-data   = "10.10.2.0/24"
      vm-ingest = "10.10.3.0/24"
      vm-core   = "10.10.4.0/24"
      vm-app    = "10.10.5.0/24"
    }
    instance_types = {
      vm-auth   = "t3.micro"
      vm-data   = "t3.micro"
      vm-ingest = "t3.micro"
      vm-core   = "t3.micro"
      vm-app    = "t3.micro"
    }
    root_volume_sizes_gib = {
      vm-auth   = 8
      vm-data   = 8
      vm-ingest = 8
      vm-core   = 8
      vm-app    = 8
    }
    allow_private_peer_ipv4_cidrs    = true
    approved_private_peer_ipv4_cidrs = ["10.10.1.10/32"]
    network_rules = [
      {
        direction      = "ingress"
        target_role    = "vm-app"
        protocol       = "tcp"
        from_port      = 8080
        to_port        = 8080
        peer_ipv4_cidr = "10.10.1.10/32"
        description    = "Must use a VM role reference"
      }
    ]
  }

  expect_failures = [
    terraform_data.deployment_guard,
  ]
}

run "rejects_private_peer_without_explicit_exception" {
  command = plan

  variables {
    aws_region               = "us-east-2"
    enable_resource_creation = true
    cost_review_confirmed    = true
    availability_zone        = "us-east-2a"
    vpc_cidr                 = "10.10.0.0/16"
    subnet_cidrs = {
      vm-auth   = "10.10.1.0/24"
      vm-data   = "10.10.2.0/24"
      vm-ingest = "10.10.3.0/24"
      vm-core   = "10.10.4.0/24"
      vm-app    = "10.10.5.0/24"
    }
    instance_types = {
      vm-auth   = "t3.micro"
      vm-data   = "t3.micro"
      vm-ingest = "t3.micro"
      vm-core   = "t3.micro"
      vm-app    = "t3.micro"
    }
    root_volume_sizes_gib = {
      vm-auth   = 8
      vm-data   = 8
      vm-ingest = 8
      vm-core   = 8
      vm-app    = 8
    }
    network_rules = [
      {
        direction      = "ingress"
        target_role    = "vm-app"
        protocol       = "tcp"
        from_port      = 8080
        to_port        = 8080
        peer_ipv4_cidr = "172.20.5.10/32"
        description    = "Private peers require explicit review"
      }
    ]
  }

  expect_failures = [
    terraform_data.deployment_guard,
  ]
}

run "does_not_plan_open_egress_without_egress_rules" {
  command = plan

  variables {
    aws_region               = "us-east-2"
    enable_resource_creation = true
    cost_review_confirmed    = true
    availability_zone        = "us-east-2a"
    vpc_cidr                 = "10.10.0.0/16"
    subnet_cidrs = {
      vm-auth   = "10.10.1.0/24"
      vm-data   = "10.10.2.0/24"
      vm-ingest = "10.10.3.0/24"
      vm-core   = "10.10.4.0/24"
      vm-app    = "10.10.5.0/24"
    }
    instance_types = {
      vm-auth   = "t3.micro"
      vm-data   = "t3.micro"
      vm-ingest = "t3.micro"
      vm-core   = "t3.micro"
      vm-app    = "t3.micro"
    }
    root_volume_sizes_gib = {
      vm-auth   = 8
      vm-data   = 8
      vm-ingest = 8
      vm-core   = 8
      vm-app    = 8
    }
    network_rules = [
      {
        direction   = "ingress"
        target_role = "vm-data"
        protocol    = "tcp"
        from_port   = 9094
        to_port     = 9094
        peer_role   = "vm-ingest"
        description = "Ingress does not imply open egress"
      }
    ]
  }

  assert {
    condition     = length(aws_vpc_security_group_egress_rule.approved) == 0
    error_message = "No standalone egress rule should be created when none is configured."
  }

  assert {
    condition = alltrue([
      for group in values(aws_security_group.vm) : length(group.egress) == 0
    ])
    error_message = "No Security Group should retain an allow-all egress rule by default."
  }
}

run "rejects_invalid_vpc_cidr" {
  command = plan

  variables {
    aws_region = "us-east-2"
    vpc_cidr   = "not-a-cidr"
  }

  expect_failures = [
    var.vpc_cidr,
  ]
}

run "rejects_unsupported_vpc_prefix" {
  command = plan

  variables {
    aws_region = "us-east-2"
    vpc_cidr   = "10.10.0.0/15"
  }

  expect_failures = [
    var.vpc_cidr,
  ]
}

run "rejects_noncanonical_vpc_cidr" {
  command = plan

  variables {
    aws_region = "us-east-2"
    vpc_cidr   = "10.10.0.1/16"
  }

  expect_failures = [
    var.vpc_cidr,
  ]
}

run "rejects_invalid_subnet_cidr" {
  command = plan

  variables {
    aws_region = "us-east-2"
    subnet_cidrs = {
      vm-auth = "not-a-cidr"
    }
  }

  expect_failures = [
    var.subnet_cidrs,
  ]
}

run "rejects_unsupported_subnet_prefix" {
  command = plan

  variables {
    aws_region = "us-east-2"
    subnet_cidrs = {
      vm-auth = "10.10.1.0/29"
    }
  }

  expect_failures = [
    var.subnet_cidrs,
  ]
}

run "rejects_noncanonical_subnet_cidr" {
  command = plan

  variables {
    aws_region = "us-east-2"
    subnet_cidrs = {
      vm-auth = "10.10.1.1/24"
    }
  }

  expect_failures = [
    var.subnet_cidrs,
  ]
}

run "rejects_non_private_vpc_cidr" {
  command = plan

  variables {
    aws_region               = "us-east-2"
    enable_resource_creation = true
    cost_review_confirmed    = true
    availability_zone        = "us-east-2a"
    vpc_cidr                 = "198.51.100.0/24"
    subnet_cidrs = {
      vm-auth   = "198.51.100.0/28"
      vm-data   = "198.51.100.16/28"
      vm-ingest = "198.51.100.32/28"
      vm-core   = "198.51.100.48/28"
      vm-app    = "198.51.100.64/28"
    }
    instance_types = {
      vm-auth   = "t3.micro"
      vm-data   = "t3.micro"
      vm-ingest = "t3.micro"
      vm-core   = "t3.micro"
      vm-app    = "t3.micro"
    }
    root_volume_sizes_gib = {
      vm-auth   = 8
      vm-data   = 8
      vm-ingest = 8
      vm-core   = 8
      vm-app    = 8
    }
  }

  expect_failures = [
    terraform_data.deployment_guard,
  ]
}

run "rejects_overlapping_subnet_cidrs" {
  command = plan

  variables {
    aws_region               = "us-east-2"
    enable_resource_creation = true
    cost_review_confirmed    = true
    availability_zone        = "us-east-2a"
    vpc_cidr                 = "10.10.0.0/16"
    subnet_cidrs = {
      vm-auth   = "10.10.1.0/24"
      vm-data   = "10.10.1.128/25"
      vm-ingest = "10.10.2.0/24"
      vm-core   = "10.10.3.0/24"
      vm-app    = "10.10.4.0/24"
    }
    instance_types = {
      vm-auth   = "t3.micro"
      vm-data   = "t3.micro"
      vm-ingest = "t3.micro"
      vm-core   = "t3.micro"
      vm-app    = "t3.micro"
    }
    root_volume_sizes_gib = {
      vm-auth   = 8
      vm-data   = 8
      vm-ingest = 8
      vm-core   = 8
      vm-app    = 8
    }
  }

  expect_failures = [
    terraform_data.deployment_guard,
  ]
}

run "rejects_subnet_outside_vpc" {
  command = plan

  variables {
    aws_region               = "us-east-2"
    enable_resource_creation = true
    cost_review_confirmed    = true
    availability_zone        = "us-east-2a"
    vpc_cidr                 = "10.10.0.0/16"
    subnet_cidrs = {
      vm-auth   = "10.11.1.0/24"
      vm-data   = "10.10.2.0/24"
      vm-ingest = "10.10.3.0/24"
      vm-core   = "10.10.4.0/24"
      vm-app    = "10.10.5.0/24"
    }
    instance_types = {
      vm-auth   = "t3.micro"
      vm-data   = "t3.micro"
      vm-ingest = "t3.micro"
      vm-core   = "t3.micro"
      vm-app    = "t3.micro"
    }
    root_volume_sizes_gib = {
      vm-auth   = 8
      vm-data   = 8
      vm-ingest = 8
      vm-core   = 8
      vm-app    = 8
    }
  }

  expect_failures = [
    terraform_data.deployment_guard,
  ]
}
