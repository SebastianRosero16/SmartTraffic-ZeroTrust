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
