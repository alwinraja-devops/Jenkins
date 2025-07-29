variable "aws_region" {
  default = "us-east-1"
}

variable "instance_type" {
  default = "t3.micro"
}

variable "windows_instance_type" {
  default = "t3.medium"
}

variable "key_name" {
  description = "SSH Key Pair name from AWS EC2 > Key Pairs"
  type        = string
}
