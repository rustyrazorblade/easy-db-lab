#!/bin/bash

set -e

# Install Docker CE
sudo DEBIAN_FRONTEND=noninteractive apt-get install -y docker.io

# Enable Docker to start on boot
sudo systemctl enable docker

# Pre-pull the sidecar image to avoid download time at cluster startup
sudo docker pull ghcr.io/apache/cassandra-sidecar:latest
