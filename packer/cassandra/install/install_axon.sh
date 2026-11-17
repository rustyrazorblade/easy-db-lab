#!/bin/bash

# The sudoers rules the axonops agent needs to drive the Cassandra service. The axon-agent
# package ships /etc/sudoers.d/axonops with wildcard arguments (`/sbin/service cassandra *`),
# which Ubuntu 26.04's sudo (sudo-rs) rejects, printing a parse error on every sudo call. This
# is the only definition of that file in the image.
axonops_sudoers_rules() {
  cat << 'EOF'
axonops ALL=NOPASSWD: /sbin/service cassandra start, /sbin/service cassandra stop, /sbin/service cassandra restart, /sbin/service cassandra status, /usr/bin/systemctl start cassandra, /usr/bin/systemctl stop cassandra, /usr/bin/systemctl restart cassandra, /usr/bin/systemctl status cassandra, /usr/bin/systemctl is-active cassandra, /usr/bin/systemctl enable cassandra, /usr/bin/systemctl disable cassandra
EOF
}

# Install sudoers rules read from stdin at <dest>, 0440 root:root. visudo -cf checks the rules
# first; if they fail, nothing is installed and this returns non-zero.
install_sudoers_file() {
  local dest="$1" staged
  staged="$(mktemp)"
  cat > "$staged"
  if ! sudo visudo -cf "$staged"; then
    rm -f "$staged"
    return 1
  fi
  sudo install -o root -g root -m 0440 "$staged" "$dest"
  rm -f "$staged"
}

# install_axon.test.sh sources this file for the functions above; install nothing then.
if [[ "${BASH_SOURCE[0]}" != "$0" ]]; then
  return 0
fi

# setup the axon repo
sudo apt-get update
sudo apt-get install -y curl gnupg ca-certificates
curl -L https://packages.axonops.com/apt/repo-signing-key.gpg | sudo gpg --dearmor -o /usr/share/keyrings/axonops.gpg
echo "deb [arch=arm64,amd64 signed-by=/usr/share/keyrings/axonops.gpg] https://packages.axonops.com/apt axonops-apt main" | sudo tee /etc/apt/sources.list.d/axonops-apt.list
sudo apt-get update

sudo apt-get install -y axon-agent

# No per-release agent is baked. `cassandra start` runs install-axonops-agent (on the PATH from
# packer/cassandra/bin) for the release line and JDK each node runs, and it writes under
# /usr/share/axonops as the cassandra user owns it here.
sudo mkdir -p /usr/share/axonops
sudo chown -R cassandra:cassandra /usr/share/axonops/
sudo chmod 0644 /etc/axonops/axon-agent.yml
sudo usermod -aG cassandra axonops
sudo usermod -aG axonops cassandra

# Disable axon-agent from auto-starting on boot
# The service should be manually started via the StartAxonOps command
sudo systemctl disable axon-agent

# Replace the package's invalid /etc/sudoers.d/axonops. A file visudo rejects fails the build.
axonops_sudoers_rules | install_sudoers_file /etc/sudoers.d/axonops || {
  echo -e "\e[31mERROR: /etc/sudoers.d/axonops failed visudo -cf\e[0m" >&2
  exit 1
}
