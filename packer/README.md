# Packer Testing

This directory contains packer configurations and testing tools for building easy-db-lab AMIs.

## Quick Start - Test Scripts Locally

### Using Gradle (Recommended)

```shell
# Test base provisioning scripts
./gradlew testPackerBase

# Test Cassandra provisioning scripts
./gradlew testPackerCassandra

# Run all packer tests
./gradlew testPacker

# Test a specific script
./gradlew testPackerScript -Pscript=cassandra/install/install_cassandra_easy_stress.sh
```

## Shell Unit Tests (no Docker)

Scripts whose value is in their *decisions* rather than their side effects have unit tests that
stub `sudo`/`curl`/`git`/`dpkg` and assert on what would have run. They need no Docker and no
network:

```shell
# Run them all
./gradlew testCassandraScripts

# Or individually
./gradlew testCassandraInstallScript   # bin/install-cassandra-version
./gradlew testCassandraInstallLoop     # the bake-time version loop in install_cassandra.sh
./gradlew testCassandraUseScript       # bin/use-cassandra
```

Each test lives next to its script as `<script>.test.sh` and also runs in CI
(`.github/workflows/packer-test.yml`). This is the tier to extend when changing argument handling,
already-installed short-circuits, or JDK selection — the Docker tiers above are for scripts that
must actually install something.

## Scripts Invoked At Runtime, Not Just At Bake Time

`cassandra/bin/` holds scripts the AMI build puts on the node's `PATH` and which the CLI then
invokes over SSH against a *running* cluster:

- `install-cassandra-version` — one install code path shared by the bake-time loop and
  `easy-db-lab cassandra install <version>`
- `use-cassandra` — selects the active version, called by `easy-db-lab cassandra use`

Their flags and output are a contract with Kotlin callers in
`src/main/kotlin/com/rustyrazorblade/easydblab/commands/cassandra/`. Changing them without updating
the caller breaks a CLI command, not just a build.

### Using test-script.sh Directly

```shell
cd packer

# Test a single script
./test-script.sh cassandra/install/install_cassandra_easy_stress.sh

# Test another script
./test-script.sh base/install/install_python.sh

# Drop into interactive shell for debugging
./test-script.sh --shell
```

## Using Docker Compose

For interactive testing:

```shell
# Start interactive test environment
docker compose run --rm test

# Inside the container, scripts are mounted at /packer
bash /packer/cassandra/install/install_cassandra_easy_stress.sh
```

Run full provisioning sequences:

```shell
# Test all base provisioning scripts
docker compose up test-base

# Test all cassandra provisioning scripts
docker compose up test-cassandra
```

## Building the Test Image

The test image is built automatically when you run `test-script.sh` or docker-compose commands.

To manually rebuild:

```shell
docker build -t easy-db-lab-packer-test .
```

## Base AMI networking (Cilium ENI mode)

Cilium ENI native-routing requires the OS to leave Cilium's runtime-attached secondary ENIs
alone. `base/install/configure_cilium_eni_networkd.sh` bakes two systemd-networkd drop-ins into
the image: `05-cilium-eni-primary.network` keeps the primary interface (`ens5`) OS-managed via
DHCP, and `06-cilium-eni-unmanaged.network` marks secondary ENIs (`ens6+`) `Unmanaged=yes` so
Cilium owns them. Without this the OS DHCPs `ens6` and adds a competing default route, multi-homing
the host and breaking IMDS/egress/kubelet. The drop-ins are inert on Flannel (no secondary ENIs are
ever attached) and are covered by `./gradlew testPackerBase`.

## Documentation

See [TESTING.md](TESTING.md) for comprehensive testing documentation including:
- Usage examples
- Troubleshooting
- CI integration
- Best practices

## Directory Structure

```
packer/
├── base/                    # Base AMI configuration
│   ├── base.pkr.hcl        # Packer config for base image
│   └── install/            # Base installation scripts
├── cassandra/              # Cassandra AMI configuration
│   ├── cassandra.pkr.hcl  # Packer config for Cassandra image
│   ├── bin/                # Scripts placed on the node's PATH, also called at runtime
│   └── install/            # Cassandra installation scripts
├── Dockerfile              # Test environment (mimics Ubuntu 24.04 AMI)
├── docker-compose.yml      # Test orchestration
├── test-script.sh          # Script testing utility
└── TESTING.md              # Comprehensive testing guide
```
