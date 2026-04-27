terraform {
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.0"
    }
    tls = {
      source  = "hashicorp/tls"
      version = "~> 4.0"
    }
  }
}

provider "aws" {
  access_key = var.aws_access_key_id
  secret_key = var.aws_secret_access_key
  region     = var.aws_region
}

# ── SSH 키 페어 ──────────────────────────────────────────────────────────────

resource "tls_private_key" "deploy" {
  algorithm = "RSA"
  rsa_bits  = 2048
}

resource "aws_key_pair" "deploy" {
  key_name   = "easydeploy-keypair-${var.job_id}"
  public_key = tls_private_key.deploy.public_key_openssh
}

# ── 네트워크 ─────────────────────────────────────────────────────────────────

data "aws_vpc" "default" {
  default = true
}

locals {
  # vpc_id가 입력되면 사용, 아니면 기본 VPC 사용
  resolved_vpc_id = var.vpc_id != "" ? var.vpc_id : data.aws_vpc.default.id
}

resource "aws_security_group" "deploy" {
  name        = "easydeploy-sg-${var.job_id}"
  description = "EasyDeploy auto-generated security group"
  vpc_id      = local.resolved_vpc_id

  ingress {
    from_port   = 80
    to_port     = 80
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
    description = "HTTP"
  }

  ingress {
    from_port   = 443
    to_port     = 443
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
    description = "HTTPS"
  }

  ingress {
    from_port   = 8080
    to_port     = 8080
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
    description = "Spring Boot"
  }

  ingress {
    from_port   = 22
    to_port     = 22
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
    description = "SSH"
  }

  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = {
    Name      = "easydeploy-sg-${var.job_id}"
    ManagedBy = "easydeploy"
  }
}

# ── EC2 인스턴스 ──────────────────────────────────────────────────────────────

data "aws_ami" "ubuntu" {
  most_recent = true
  owners      = ["099720109477"]

  filter {
    name   = "name"
    values = ["ubuntu/images/hvm-ssd/ubuntu-jammy-22.04-amd64-server-*"]
  }

  filter {
    name   = "state"
    values = ["available"]
  }
}

resource "aws_instance" "deploy" {
  ami                    = data.aws_ami.ubuntu.id
  instance_type          = var.instance_type
  key_name               = aws_key_pair.deploy.key_name
  vpc_security_group_ids = [aws_security_group.deploy.id]
  subnet_id              = var.subnet_id != "" ? var.subnet_id : null
  user_data              = var.user_data

  root_block_device {
    volume_size = var.storage_size
    volume_type = "gp3"
  }

  tags = {
    Name      = "easydeploy-instance-${var.job_id}"
    ManagedBy = "easydeploy"
  }
}

# ── Elastic IP ────────────────────────────────────────────────────────────────

resource "aws_eip" "deploy" {
  instance = aws_instance.deploy.id
  domain   = "vpc"

  tags = {
    Name      = "easydeploy-eip-${var.job_id}"
    ManagedBy = "easydeploy"
  }

  depends_on = [aws_instance.deploy]
}
