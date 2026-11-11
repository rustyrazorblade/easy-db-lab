# Packer Script Testing Guide

This guide explains how to test packer provisioning scripts locally using Docker, without having to run full AWS builds.

## Overview

The test environment uses Docker to create a container that mimics the Ubuntu 24.04 AMI environment used by packer. This allows you to:

- Test individual scripts in seconds instead of minutes
- Iterate quickly on script changes
- Catch errors before pushing to AWS
- Avoid AWS costs during development

## Prerequisites

- Docker installed and running
- Docker Compose (comes with Docker Desktop)

## Quick Start

### Method 1: Using the test-script.sh Runner

The simplest way to test a single script:

```bash
cd packer
./test-script.sh cassandra/install/install_cassandra_easy_stress.sh
```

This will:
1. Build the test Docker image (first time only)
2. Run your script in the container
3. Show output and indicate success/failure

### Method 2: Using Docker Compose

For interactive testing:

```bash
cd packer
docker compose run --rm test
```

This drops you into a bash shell where you can:
- Run scripts manually
- Inspect the environment
- Debug issues interactively

The packer directory is mounted at `/packer` (read-only).

## Detailed Usage

### Testing Individual Scripts

```bash
# Test the cassandra-easy-stress installation
./test-script.sh cassandra/install/install_cassandra_easy_stress.sh

# Test a base installation script
./test-script.sh base/install/install_python.sh

# Test with forced image rebuild
./test-script.sh cassandra/install/install_cassandra.sh --rebuild
```

### Interactive Debugging

```bash
# Drop into shell mode for debugging
./test-script.sh --shell

# Once in the shell:
ubuntu@container:~$ bash /packer/cassandra/install/install_cassandra_easy_stress.sh
ubuntu@container:~$ ls -la /usr/local/cassandra-easy-stress/
ubuntu@container:~$ exit
```

### Testing Multiple Scripts in Sequence

Use docker-compose services to test provisioning sequences:

```bash
# Test base provisioning scripts
docker compose up test-base

# Test cassandra-specific scripts
docker compose up test-cassandra
```

## Test Script Options

The `test-script.sh` script supports several options:

```
./test-script.sh <script-path> [options]

Options:
  --rebuild      Force rebuild of Docker test image
  --shell        Drop into shell instead of running script (for debugging)
  --keep         Keep container after script execution (for inspection)
```

### Examples

```bash
# Basic test
./test-script.sh cassandra/install/install_cassandra_easy_stress.sh

# Force rebuild (after changing Dockerfile)
./test-script.sh cassandra/install/install_cassandra_easy_stress.sh --rebuild

# Debug mode - drop into shell
./test-script.sh --shell

# Keep container for inspection after run
./test-script.sh cassandra/install/install_cassandra_easy_stress.sh --keep
```

## Test Environment Details

### Base Image
- **OS**: Ubuntu 26.04 (resolute)
- **Matches**: Packer source AMI `ubuntu/images/*ubuntu-resolute-26.04-${arch}-server-*`

### Pre-installed Software
- Java 8, 11, 17 (JDK versions)
- sudo (sudo-rs, the 26.04 default, same as the AMI), curl, wget, git
- build-essential and common tools

### Sudoers Validation

`install_axon.sh` is the only thing that writes `/etc/sudoers.d/axonops`. It validates the file
with `visudo -cf` and fails the build if the file is rejected. sudo-rs rejects wildcard command
arguments that classic sudo (macOS, older Ubuntu) accepts, so the check only means something
against the AMI's own sudo. `./gradlew testAxonSudoers` rebuilds this image and runs
`cassandra/install/install_axon.test.sh` in it. No network is needed. `./gradlew testPacker`
includes it.

### Users
- **ubuntu**: Primary user (matches packer SSH user)
- **cassandra**: Service user (for cassandra services)
- Both have passwordless sudo access

### Directory Structure
- Packer directory mounted at: `/packer` (read-only)
- Working directory: `/home/ubuntu`
- Scripts run as: `ubuntu` user

## Troubleshooting

### Docker Image Not Found

If you get "image not found" errors:

```bash
cd packer
docker build -t easy-db-lab-packer-test .
```

### Permission Denied Errors

Scripts expect to run as the `ubuntu` user with sudo access. If you encounter permission issues:

1. Check that the script uses `sudo` for privileged operations
2. Verify the container is running as `ubuntu` user

### Script Fails in Docker But Works in Packer

Some differences between the test environment and actual AMI:

1. **Network**: Container may have different network access
2. **Storage**: Container uses overlay filesystem, not EBS
3. **Services**: Systemd may behave differently in containers
4. **Architecture**: Test environment is amd64 only (ARM not tested)

For systemd-related scripts, you may need to test in a full VM or actual AMI.

### Rebuilding the Test Image

If you modify `Dockerfile`:

```bash
# Rebuild manually
docker build -t easy-db-lab-packer-test .

# Or use --rebuild flag
./test-script.sh cassandra/install/install_cassandra_easy_stress.sh --rebuild
```

## Best Practices

1. **Test Early, Test Often**: Run tests before committing script changes
2. **Use --shell for Debugging**: Interactive mode helps debug complex issues
3. **Test Script Sequences**: Use docker-compose to test multiple scripts together
4. **Verify Side Effects**: Check that files are created in expected locations
5. **Check Logs**: Review output for warnings even if script succeeds

## Updating the Test Environment

To add new dependencies or tools to the test environment:

1. Edit `Dockerfile`
2. Rebuild: `docker build -t easy-db-lab-packer-test .`
3. Test your scripts with the updated environment

## Integration with CI/CD

You can use this test environment in CI/CD pipelines:

```yaml
# Example GitHub Actions workflow
- name: Test packer scripts
  run: |
    cd packer
    docker build -t easy-db-lab-packer-test .
    ./test-script.sh cassandra/install/install_cassandra_easy_stress.sh
```

## Common Test Workflows

### Testing a New Installation Script

```bash
# 1. Create your script
vim cassandra/install/my_new_script.sh

# 2. Make it executable
chmod +x cassandra/install/my_new_script.sh

# 3. Test it
./test-script.sh cassandra/install/my_new_script.sh

# 4. Debug if needed
./test-script.sh --shell
# Then manually run: bash /packer/cassandra/install/my_new_script.sh
```

### Verifying Installation Results

```bash
# Run with --keep to inspect container after script completes
./test-script.sh cassandra/install/install_cassandra_easy_stress.sh --keep

# In another terminal, find the container
docker ps -a

# Execute commands in the container to verify
docker exec -it <container-id> ls -la /usr/local/cassandra-easy-stress/
docker exec -it <container-id> /usr/local/cassandra-easy-stress/bin/cassandra-easy-stress --version
```

## Limitations

- **Architecture**: Tests amd64 only; arm64 not tested locally
- **Systemd**: Full systemd functionality may not work in containers
- **Networking**: Some network configurations may differ from EC2
- **Storage**: Uses overlay filesystem instead of EBS volumes
- **AWS Services**: Cannot test IAM roles, EC2 metadata, etc.

For full end-to-end testing, you still need to run packer builds in AWS.
