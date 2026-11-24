output "vm_private_addresses" {
  description = "Private IP addresses of the five VMs; available only after an approved deployment."
  value = {
    for role, instance in aws_instance.vm :
    role => {
      instance_id = instance.id
      private_ip  = instance.private_ip
    }
  }
}

output "vm_public_ipv4_addresses" {
  description = "Dynamic public IPv4 addresses of the five VMs; may change after stop or start."
  value = {
    for role, instance in aws_instance.vm :
    role => instance.public_ip
  }
}

output "data_volume_ids" {
  description = "EBS volume IDs for the vm-data service data disks, keyed by service."
  value = {
    for service, volume in aws_ebs_volume.data :
    service => volume.id
  }
}
