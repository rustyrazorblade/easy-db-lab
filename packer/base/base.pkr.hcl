packer {
  required_plugins {
    amazon = {
      version = ">= 1.2.8"
      source  = "github.com/hashicorp/amazon"
    }
  }
}

variable "arch" {
  type = string
  default = "amd64"
}

variable "region" {
  type = string
  default = "us-west-2"
}

variable "release_version" {
  type    = string
  default = ""
}

locals {
  timestamp = regex_replace(timestamp(), "[- TZ:]", "")
  version = var.release_version != "" ? var.release_version : local.timestamp
  # We need to use a Graviton instance type for arm
  instance_type = var.arch == "amd64" ? "c6i.2xlarge" : "c8g.2xlarge"
}

source "amazon-ebs" "ubuntu" {
  ami_name      = "rustyrazorblade/images/easy-db-lab-base-${var.arch}-${local.version}"
  instance_type = local.instance_type
  region        = "${var.region}"
  source_ami_filter {
    filters = {
      name                = "ubuntu/images/*ubuntu-noble-24.04-${var.arch}-server-*"
      root-device-type    = "ebs"
      virtualization-type = "hvm"
    }
    most_recent = true
    owners      = ["099720109477"]
  }
  ssh_username = "ubuntu"

  # Use permanent VPC infrastructure created by PackerInfrastructureService
  vpc_filter {
    filters = {
      "tag:Name" = "easy-db-lab-packer"
    }
  }

  subnet_filter {
    filters = {
      "tag:Name" = "easy-db-lab-packer-subnet"
    }
    most_free = true
    random    = false
  }

  security_group_filter {
    filters = {
      "tag:Name" = "easy-db-lab-packer-sg"
    }
  }

  run_tags = {
    easy_cass_lab = "1"
  }
  tags = {
    easy_cass_lab = "1"
  }
  launch_block_device_mappings {
    device_name = "/dev/sda1"
    volume_size = 16
    volume_type = "gp2"
    delete_on_termination = true
  }
}

build {
  name    = "easy-db-lab"
  sources = [
    "source.amazon-ebs.ubuntu"
  ]

  provisioner "shell" {
    script = "install/prepare_instance.sh"
  }

  provisioner "shell" {
    inline = [
      # bpftrace was removed b/c it breaks bcc tools, need to build latest from source
      "echo '=== Downloading yq v4.41.1 ==='",
      "sudo wget https://github.com/mikefarah/yq/releases/download/v4.41.1/yq_linux_${var.arch} -O /usr/local/bin/yq || { echo 'ERROR: yq download failed' >&2; exit 1; }",
      "[ -f /usr/local/bin/yq ] || { echo 'ERROR: yq file not found after download' >&2; exit 1; }",
      "sudo chmod +x /usr/local/bin/yq",
      "echo '✓ yq installed successfully'",
    ]
  }

  # install python via deadsnakes PPA
  provisioner "shell" {
    script = "install/install_python.sh"
  }

  provisioner "shell" {
    script = "install/install_fio.sh"
  }

  # install async profiler
  provisioner "shell" {
    script = "install/install_async_profiler.sh"
  }


  provisioner "shell" {
    script = "install/install_bcc.sh"
  }

  # install AWS CLI v2
  provisioner "shell" {
    script = "install/install_awscli.sh"
  }

  # install k3s (disabled, not auto-started)
  provisioner "shell" {
    script = "install/install_k3s.sh"
  }

  # install tailscale (disabled, not auto-started)
  provisioner "shell" {
    script = "install/install_tailscale.sh"
  }

  # install k9s (Kubernetes TUI)
  provisioner "shell" {
    script = "install/install_k9s.sh"
  }

  provisioner "shell" {
    inline = [
      "sudo apt install openjdk-8-jdk openjdk-8-dbg openjdk-11-jdk openjdk-11-dbg openjdk-17-jdk openjdk-17-dbg -y",
      "sudo update-java-alternatives -s /usr/lib/jvm/java-1.11.0-openjdk-${var.arch}",
      "sudo sed -i '/hl jexec.*/d' /usr/lib/jvm/.java-1.8.0-openjdk-${var.arch}.jinfo"
    ]
  }

  # install my extra nice tools, exa, bat, fd, ripgrep
  # wrapper for aprof to output results to a folder content shared by nginx
  # open to what port?

  # plop a file in with all the aliases I like
  provisioner "file" {
    source      = "aliases.sh"
    destination = "aliases.sh"
  }

  provisioner "shell" {
    inline = [
      "sudo mv aliases.sh /etc/profile.d/aliases.sh"
    ]
  }

  provisioner "shell" {
    inline = [
      "echo '=== Downloading sjk.jar v0.21 from Maven Central ==='",
      "wget https://repo1.maven.org/maven2/org/gridkit/jvmtool/sjk/0.21/sjk-0.21.jar -O /tmp/sjk.jar || { echo 'ERROR: sjk.jar download failed' >&2; exit 1; }",
      "[ -f /tmp/sjk.jar ] || { echo 'ERROR: sjk.jar file not found after download' >&2; exit 1; }",
      "sudo mv /tmp/sjk.jar /usr/local/lib/sjk.jar",
      "echo '✓ sjk.jar v0.21 installed successfully'",
      ""
    ]
  }
}

