# Test environment for packer scripts
# Mimics Ubuntu 26.04 (Resolute) AMI environment used by packer
FROM ubuntu:26.04

ENV DEBIAN_FRONTEND=noninteractive

# Install basic dependencies
RUN apt-get update && apt-get install -y \
    sudo \
    curl \
    wget \
    git \
    build-essential \
    ca-certificates \
    gnupg \
    lsb-release \
    software-properties-common \
    && rm -rf /var/lib/apt/lists/*

# Create ubuntu user (matches packer SSH user) and add to docker group
# Ubuntu 26.04 may already have ubuntu user, so check first
# Ensure docker group exists immediately before adding user to it to avoid caching issues
RUN id -u ubuntu &>/dev/null || useradd -m -s /bin/bash ubuntu && \
    echo "ubuntu ALL=(ALL) NOPASSWD:ALL" >> /etc/sudoers && \
    (getent group docker || groupadd docker) && \
    usermod -aG docker ubuntu

# Create cassandra user (used by cassandra services)
RUN id -u cassandra &>/dev/null || useradd -m -s /bin/bash cassandra && \
    echo "cassandra ALL=(ALL) NOPASSWD:ALL" >> /etc/sudoers

# Install Java versions (matches base packer setup)
RUN apt-get update && apt-get install -y \
    openjdk-8-jdk \
    openjdk-11-jdk \
    openjdk-17-jdk \
    && rm -rf /var/lib/apt/lists/*

# Set default Java version to 11 (matches packer base)
RUN update-java-alternatives -s java-1.11.0-openjdk-amd64 || true

# Switch to ubuntu user
USER ubuntu
WORKDIR /home/ubuntu

# Default command
CMD ["/bin/bash"]
