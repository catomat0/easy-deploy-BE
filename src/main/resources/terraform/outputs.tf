output "public_ip" {
  description = "EC2 퍼블릭 IP (EIP)"
  value       = aws_eip.deploy.public_ip
}

output "instance_id" {
  description = "EC2 인스턴스 ID"
  value       = aws_instance.deploy.id
}

output "eip_allocation_id" {
  description = "EIP Allocation ID (종료 시 해제용)"
  value       = aws_eip.deploy.allocation_id
}

output "security_group_id" {
  description = "보안그룹 ID"
  value       = aws_security_group.deploy.id
}

output "ssh_private_key" {
  description = "EC2 SSH 프라이빗 키 (PEM)"
  value       = tls_private_key.deploy.private_key_pem
  sensitive   = true
}
