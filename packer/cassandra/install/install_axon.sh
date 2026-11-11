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

# need to add a new start script for C*

# some versions have JVM specific agents
declare -a agent_versions=("3.0-agent" "3.11-agent"  "4.0-agent-jdk8" "4.0-agent" "4.1-agent-jdk8" "4.1-agent" "5.0-agent-jdk11" "5.0-agent-jdk17")

# Function to install an axon agent version
install_axon_agent() {
  local agent_version=$1
  local package="axon-cassandra${agent_version}"

  echo -e "\e[32minstalling axonops-agent $package\e[0m"

  local tempdir=/tmp/axonops/
  local targetdir=/usr/share/axonops/$agent_version/lib

  local jar="${package}.jar"
  echo "package=$package, tempdir=$tempdir, targetdir=$targetdir, jar=$jar"

  mkdir -p "$tempdir"
  
  # Try to install with architecture 'all' first
  echo "Trying to install ${package}:all..."
  if sudo apt install --download-only -y "${package}:all" 2>/dev/null; then
    local file=$(sudo find /var/cache/apt/archives/ -name "${package}*")
    if [ -n "$file" ]; then
      echo -e "\e[32mSuccessfully downloaded ${package}:all as $file\e[0m"
    else
      # If we couldn't find the file, fall back to the builder's native arch
      echo -e "\e[31mPackage downloaded but file not found, falling back to native arch...\e[0m"
      sudo apt install --download-only -y "${package}:$(dpkg --print-architecture)"
      file=$(sudo find /var/cache/apt/archives/ -name "${package}*")
    fi
  else
    # Fall back to the builder's native arch if 'all' is not available
    echo "Package not available for 'all' architecture, falling back to native arch..."
    sudo apt install --download-only -y "${package}:$(dpkg --print-architecture)"
    file=$(sudo find /var/cache/apt/archives/ -name "${package}*")
  fi

  if [ -z "$file" ]; then
    echo -e "\e[31mERROR: Could not find package file for ${package}\e[0m"
    return 1
  fi

  echo "unpacking $file to $tempdir"
  if ! dpkg-deb -xv "$file" "$tempdir"; then
    echo -e "\e[31mERROR: Failed to unpack $file\e[0m"
    return 1
  fi

  sudo mkdir -p "$targetdir"
  if ! sudo cp "$tempdir"/usr/share/axonops/*.jar "$targetdir"; then
    echo -e "\e[31mERROR: Failed to copy jar files to $targetdir\e[0m"
    return 1
  fi

  # clean up after ourselves
  sudo rm -rf $file
  sudo rm -rf $tempdir
}

# download each version of the axon agent
for agent_version in "${agent_versions[@]}"
do
  install_axon_agent "$agent_version" || {
    echo -e "\e[31mERROR: failed to install axon agent $agent_version\e[0m" >&2
    exit 1
  }
done

# install agent_service file included with every version since its expected
# to be in a specific place
#sudo cp /tmp/axonops/"$v"/var/lib/axonops/agent_service /var/lib/axonops
#sudo chown -R cassandra:cassandra /var/lib/axonops

# permissions setup
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
