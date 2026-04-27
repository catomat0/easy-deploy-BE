variable "aws_access_key_id" {
  description = "AWS Access Key ID"
  type        = string
  sensitive   = true
}

variable "aws_secret_access_key" {
  description = "AWS Secret Access Key"
  type        = string
  sensitive   = true
}

variable "aws_region" {
  description = "AWS 리전 (예: ap-northeast-2)"
  type        = string
}

variable "instance_type" {
  description = "EC2 인스턴스 타입 (예: t3.micro)"
  type        = string
  default     = "t3.micro"
}

variable "job_id" {
  description = "배포 Job ID (리소스 이름 중복 방지용)"
  type        = string
}

variable "user_data" {
  description = "EC2 초기화 스크립트 (bash)"
  type        = string
}

variable "vpc_id" {
  description = "사용할 VPC ID (비워두면 기본 VPC 사용)"
  type        = string
  default     = ""
}

variable "subnet_id" {
  description = "사용할 Subnet ID (비워두면 AWS가 자동 선택)"
  type        = string
  default     = ""
}

variable "storage_size" {
  description = "루트 볼륨 크기 (GiB)"
  type        = number
  default     = 20
}

variable "server_name" {
  description = "AWS 콘솔에 표시될 EC2 인스턴스 이름 (비워두면 자동 생성)"
  type        = string
  default     = ""
}
