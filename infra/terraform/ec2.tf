# =====================================================================
#  EC2 t2.micro (free tier) + Caddy (TLS auto vía Let's Encrypt + nip.io)
#  + Elastic IP (gratis si associated)
# =====================================================================

resource "tls_private_key" "ec2" {
  count     = var.ec2_key_name == "" ? 1 : 0
  algorithm = "RSA"
  rsa_bits  = 4096
}

resource "aws_key_pair" "ec2" {
  count      = var.ec2_key_name == "" ? 1 : 0
  key_name   = "${var.project}-ec2-key"
  public_key = tls_private_key.ec2[0].public_key_openssh
}

resource "local_sensitive_file" "ec2_priv_key" {
  count           = var.ec2_key_name == "" ? 1 : 0
  filename        = "${path.module}/.secrets/ec2-key.pem"
  content         = tls_private_key.ec2[0].private_key_pem
  file_permission = "0600"
}

locals {
  ec2_key_name = var.ec2_key_name != "" ? var.ec2_key_name : aws_key_pair.ec2[0].key_name
}

resource "aws_security_group" "ec2" {
  name        = "${var.project}-ec2-sg"
  description = "HTTPS public and SSH admin"
  vpc_id      = data.aws_vpc.default.id

  ingress {
    description = "SSH admin"
    from_port   = 22
    to_port     = 22
    protocol    = "tcp"
    cidr_blocks = [var.admin_ip]
  }
  ingress {
    description = "HTTP Caddy ACME challenge"
    from_port   = 80
    to_port     = 80
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }
  ingress {
    description = "HTTPS"
    from_port   = 443
    to_port     = 443
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }
  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }
}

# Amazon Linux 2023 ARM64 — free tier compatible con t4g, pero usamos t3.micro x86 por compat con Java 21 simple
data "aws_ami" "al2023" {
  most_recent = true
  owners      = ["amazon"]
  filter {
    name   = "name"
    values = ["al2023-ami-*-x86_64"]
  }
}

resource "aws_eip" "backend" {
  domain = "vpc"

  # IP fija — NUNCA borrar accidentalmente. El frontend referencia esta IP
  # vía nip.io. Si se libera, todos los DNS apuntando aquí se rompen.
  lifecycle {
    prevent_destroy = true
  }

  tags = {
    Name      = "prueba-aws-backend-eip"
    Permanent = "true"
  }
}

# Elastic IP atada después para usar su valor en user_data (nip.io)
locals {
  eip_dashed   = replace(aws_eip.backend.public_ip, ".", "-")
  backend_host = "${var.project}-${local.eip_dashed}.nip.io"
}

data "aws_subnet" "filtered" {
  for_each = toset(data.aws_subnets.default.ids)
  id       = each.value
}

locals {
  # Subnets que soportan t3.micro (us-east-1e no lo soporta)
  ec2_supported_azs = ["us-east-1a", "us-east-1b", "us-east-1c", "us-east-1d", "us-east-1f"]
  ec2_subnet_id = [
    for s in data.aws_subnet.filtered :
    s.id if contains(local.ec2_supported_azs, s.availability_zone)
  ][0]
}

resource "aws_instance" "backend" {
  ami                         = data.aws_ami.al2023.id
  instance_type               = "t3.micro"          # FREE TIER (750h/mes 12m)
  iam_instance_profile        = aws_iam_instance_profile.ec2_backend.name
  key_name                    = local.ec2_key_name
  vpc_security_group_ids      = [aws_security_group.ec2.id]
  subnet_id                   = local.ec2_subnet_id
  associate_public_ip_address = true

  user_data = <<-USERDATA
    #!/bin/bash
    set -euxo pipefail
    dnf -y update
    dnf -y install java-21-amazon-corretto wget tar gzip awscli jq

    # Caddy desde Cloudsmith (binarios oficiales)
    dnf -y install yum-utils
    rpm --import https://dl.cloudsmith.io/public/caddy/stable/gpg.key
    cat <<EOF >/etc/yum.repos.d/caddy.repo
    [caddy]
    name=Caddy
    baseurl=https://dl.cloudsmith.io/public/caddy/stable/rpm/el/9/$basearch
    enabled=1
    gpgcheck=1
    gpgkey=https://dl.cloudsmith.io/public/caddy/stable/gpg.key
    EOF
    dnf -y install caddy

    mkdir -p /opt/prueba-aws
    cat <<CADDY >/etc/caddy/Caddyfile
    ${local.backend_host} {
        reverse_proxy localhost:8080
    }
    CADDY
    systemctl enable --now caddy

    cat <<UNIT >/etc/systemd/system/prueba-backend.service
    [Unit]
    Description=Prueba AWS backend (Spring Boot + Jasper)
    After=network.target

    [Service]
    Type=simple
    User=ec2-user
    WorkingDirectory=/opt/prueba-aws
    EnvironmentFile=/opt/prueba-aws/app.env
    ExecStart=/usr/bin/java -jar /opt/prueba-aws/backend.jar
    Restart=on-failure
    RestartSec=5

    [Install]
    WantedBy=multi-user.target
    UNIT

    chown -R ec2-user:ec2-user /opt/prueba-aws
    systemctl daemon-reload
    systemctl enable prueba-backend.service
    # No iniciamos: el backend lo subimos vía scp después
  USERDATA

  tags = {
    Name = "${var.project}-backend"
  }
}

resource "aws_eip_association" "backend" {
  instance_id   = aws_instance.backend.id
  allocation_id = aws_eip.backend.id
}

output "backend_public_ip"   { value = aws_eip.backend.public_ip }
output "backend_public_dns"  { value = local.backend_host }
output "backend_url"         { value = "https://${local.backend_host}" }
output "ec2_ssh_command"     { value = "ssh -i infra/terraform/.secrets/ec2-key.pem ec2-user@${aws_eip.backend.public_ip}" }
