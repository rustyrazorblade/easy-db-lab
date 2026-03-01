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
  base_version = var.release_version != "" ? var.release_version : "*"
  version = var.release_version != "" ? var.release_version : local.timestamp
  ami_groups = var.release_version != "" ? ["all"] : []
  instance_type = var.arch == "amd64" ? "c6i.2xlarge" : "c8g.2xlarge"


}

source "amazon-ebs" "ubuntu" {
  ami_name      = "rustyrazorblade/images/easy-db-lab-cassandra-${var.arch}-${local.version}"
  ami_groups    = local.ami_groups
  instance_type = local.instance_type
  region        = "${var.region}"
  source_ami_filter {
    filters = {
      name                = "rustyrazorblade/images/easy-db-lab-base-${var.arch}-${local.base_version}"
      root-device-type    = "ebs"
      virtualization-type = "hvm"
    }
    most_recent = true
    owners      = ["self"]
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

  # set up environment with PATH and aliases
  provisioner "file" {
    source = "environment"
    destination = "environment"
  }

  provisioner "shell" {
    inline = ["sudo mv environment /etc/environment"]
  }

  # catch all bin upload
  # just drop stuff you need in bin and the next 2 provisioners will take care of it
  provisioner "file" {
    source = "bin"
    destination = "/home/ubuntu/bin-cassandra"
  }
  provisioner "shell" {
    inline = [
      "sudo mv -v bin-cassandra/* /usr/local/bin/",
      "sudo chmod +x /usr/local/bin/*",
      "ls /usr/local/bin",
      "rmdir bin-cassandra"
    ]
  }


  provisioner "shell" {
    script = "install/install_cassandra_easy_stress.sh"
  }

  # the cassandra_versions.yaml file is used to define all the version of cassandra we want
  # and it's matching java version.  The use command will set the symlink of /usr/local/cassandra
  # to point to the version of cassandra we want to use, and set the java version using update-java-alternatives

  provisioner "file" {
    source = "cassandra_versions.yaml"
    destination = "cassandra_versions.yaml"
  }

  provisioner "shell" {
    script = "install/install_sidecar.sh"
  }

  provisioner "shell" {
    inline = [
      "sudo mkdir -p /etc/cassandra-sidecar",
      "sudo mkdir -p /mnt/db1/cassandra/import",
      "sudo chown ubuntu:ubuntu /mnt/db1/cassandra/import"
    ]
  }

  provisioner "shell" {
    inline = [
        "sudo mv cassandra_versions.yaml /etc/cassandra_versions.yaml"
    ]
  }

  # needs to be in place before install cassandra is run.  At the end of the install,
  # the installer will append cassandra.in.sh to the end of the included cassandra.in.sh

  provisioner "file" {
    source = "cassandra.in.sh"
    destination = "/tmp/cassandra.in.sh"
  }

  provisioner "shell" {
    environment_vars = [
      # we need this to be set because install_cassandra checks for it and exits if it's not there
      # this is so we can source the file and test the functions outside of packer
      "INSTALL_CASSANDRA=1",
    ]
    script = "install/install_cassandra.sh"
  }

  # install Pyroscope Java agent for continuous profiling
  provisioner "shell" {
    script = "install/install_pyroscope_agent.sh"
  }

  # install OpenTelemetry Java agent for sidecar instrumentation
  provisioner "shell" {
    script = "install/install_otel_agent.sh"
  }

  # install MAAC (Management API for Apache Cassandra) metrics agent
  provisioner "shell" {
    script = "install/install_maac.sh"
  }

  # instal axonops
  provisioner "shell" {
    script = "install/install_axon.sh"
  }

  provisioner "file" {
    source = "axonops-sudoers"
    destination = "axonops-sudoers"
  }

  provisioner "shell" {
    inline = [
      "sudo chown root:root axonops-sudoers",
      "sudo mv axonops-sudoers /etc/sudoers.d/axonops",
    ]
  }

  provisioner "file" {
    source = "services"
    destination = "services"
  }

  provisioner "shell" {
    inline = [
       "sudo mv services/* /etc/systemd/system/"
    ]
  }

  provisioner "file" {
    source = "patch-jvm-options.py"
    destination = "patch-jvm-options.py"
  }

  provisioner "shell" {
    inline = [
       "sudo mv patch-jvm-options.py /usr/local/bin/patch-jvm-options.py",
       "sudo chmod +x /usr/local/bin/patch-jvm-options.py"
    ]
  }

  provisioner "shell" {
    inline = [
      "mkdir -p /home/ubuntu/.config/htop/"
    ]
  }

  provisioner "file" {
    source = "htoprc"
    destination = "/home/ubuntu/.config/htop/htoprc"
  }
}


