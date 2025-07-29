output "ubuntu_public_ip" {
  value = aws_instance.ubuntu.public_ip
}

output "centos_public_ip" {
  value = aws_instance.centos.public_ip
}

output "windows_public_ip" {
  value = aws_instance.windows.public_ip
}
