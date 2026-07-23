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

# Account S3 bucket used as an apt package cache for the JDK install (empty disables caching)
variable "s3_bucket" {
  type    = string
  default = ""
}

# The user's AWS keypair and local private key, so the build instance is reachable via SSH with
# their own key (e.g. when left up by --keep-on-error / -on-error=abort).
variable "ssh_keypair_name" {
  type    = string
  default = ""
}

variable "ssh_private_key_file" {
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
  # Instance profile so the build can read/write the account S3 apt cache.
  # The bucket policy already grants this role s3:* on the account bucket.
  iam_instance_profile = "EasyDBLabEC2Role"
  source_ami_filter {
    filters = {
      name                = "ubuntu/images/*ubuntu-resolute-26.04-${var.arch}-server-*"
      root-device-type    = "ebs"
      virtualization-type = "hvm"
    }
    most_recent = true
    owners      = ["099720109477"]
  }
  ssh_username         = "ubuntu"
  ssh_keypair_name     = var.ssh_keypair_name
  ssh_private_key_file = var.ssh_private_key_file

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

  metadata_options {
    http_endpoint               = "enabled"
    http_tokens                 = "required"
    http_put_response_hop_limit = 2
  }

  run_tags = {
    easy_cass_lab = "1"
  }
  tags = {
    easy_cass_lab = "1"
  }
  launch_block_device_mappings {
    device_name = "/dev/sda1"
    volume_size = 20
    volume_type = "gp3"
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

  # Upload the shared S3 cache library used by the install scripts below
  provisioner "file" {
    source      = "install/edl-cache-lib.sh"
    destination = "/tmp/edl-cache-lib.sh"
  }

  # install AWS CLI v2 early so the S3 cache is usable by everything that follows
  provisioner "shell" {
    script = "install/install_awscli.sh"
  }

  # install the cache library and restore the apt archive cache from S3
  provisioner "shell" {
    environment_vars = [
      "EDL_ARCH=${var.arch}",
      "EDL_S3_BUCKET=${var.s3_bucket}",
    ]
    script = "install/setup_s3_cache.sh"
  }

  # install yq (via the S3 download cache)
  provisioner "shell" {
    environment_vars = ["ARCH=${var.arch}"]
    script           = "install/install_yq.sh"
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

  # install helm, kubectl, and cilium CLI (used by easy-db-lab services via SSH)
  provisioner "shell" {
    script = "install/install_helm.sh"
  }

  provisioner "shell" {
    script = "install/install_kubectl.sh"
  }

  provisioner "shell" {
    script = "install/install_cilium_cli.sh"
  }

  # Leave Cilium's secondary ENIs (ens6+) unmanaged by systemd-networkd so the OS does not
  # DHCP them and hijack host routing (see the script header for the full rationale). Inert on
  # Flannel; must be baked in because the ENIs are attached at runtime.
  provisioner "shell" {
    script = "install/configure_cilium_eni_networkd.sh"
  }

  # install OpenTelemetry Java agent for workload instrumentation
  provisioner "shell" {
    script = "install/install_otel_agent.sh"
  }

  # Installs all supported JDKs (8/11/17/21 + debug symbols) for the Cassandra versions we
  # support. The hundreds of MB of -dbg packages come from the apt archive cache restored above.
  provisioner "shell" {
    environment_vars = [
      "ARCH=${var.arch}",
    ]
    script = "install/install_jdks.sh"
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

  # install sjk.jar (via the S3 download cache)
  provisioner "shell" {
    script = "install/install_sjk.sh"
  }

  # Save the warm apt archive cache back to S3 for the next build
  provisioner "shell" {
    script = "install/save_s3_cache.sh"
  }

  # Final image cleanup: prune build cruft so it is not baked into the AMI and to shrink
  # the EBS snapshot. This MUST run AFTER save_s3_cache.sh above (which calls apt_cache_save),
  # otherwise the warm apt archive cache would be stripped before it is persisted to S3,
  # slowing future builds. First we log a pre-prune inventory, then prune system caches/logs
  # and the surgical set of home-dir build leftovers. Runtime paths (.local/cqlsh,
  # .config/htoprc, .ssh, shell dotfiles) are deliberately left untouched.
  provisioner "shell" {
    inline = [
      "echo '=== image cleanup: pre-prune inventory ==='",
      "ls -la /home/ubuntu || true",
      "sudo du -sh /var/cache/apt/archives /var/lib/apt/lists /tmp /var/tmp /var/log /home/ubuntu/.cache 2>/dev/null || true",
      "df -h / || true",
      "echo '=== image cleanup: pruning ==='",
      # System caches and logs
      "sudo apt-get clean",
      "sudo rm -rf /var/cache/apt/archives/*.deb /var/lib/apt/lists/*",
      "sudo rm -rf /tmp/* /var/tmp/*",
      "sudo find /var/log -type f -exec truncate -s 0 {} +",
      # Home-dir build leftovers (surgical; tolerate absence). The base build uploads aliases.sh
      # to the home dir before moving it to /etc/profile.d, so remove any stray copy here.
      "rm -rf /home/ubuntu/aliases.sh",
      "rm -rf /home/ubuntu/.cache /home/ubuntu/.wget-hsts /home/ubuntu/.bash_history /home/ubuntu/.m2 /home/ubuntu/.sudo_as_admin_successful /home/ubuntu/.lesshst",
      # Discard the now-freed blocks so EBS excludes them from the snapshot (this is what
      # actually shrinks the snapshot and cuts AMI-creation time). -av prints bytes trimmed
      # per filesystem for verification. No `|| true`: a fstrim failure must surface.
      "sudo fstrim -av",
    ]
  }
}

