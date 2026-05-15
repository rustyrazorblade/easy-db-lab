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

## Documentation

See [TESTING.md](TESTING.md) for comprehensive testing documentation including:
- Usage examples
- Troubleshooting
- CI integration
- Best practices

## S3 Build Cache

Packer scripts support an optional S3-backed binary cache to skip expensive compilation steps on repeat builds.

Two scripts benefit from caching:
- `base/install/install_bcc.sh` — caches compiled BCC artifacts (~15–20 min saved per cache hit)
- `cassandra/install/install_cassandra.sh` — caches git-branch Cassandra builds (~20 min saved per cache hit)

### Environment Variables

| Variable | Description |
|---|---|
| `PACKER_CACHE_BUCKET` | S3 bucket name for the build cache. When unset or empty, cache operations are skipped and builds proceed normally. |
| `PACKER_CACHE_SKIP` | Set to `1` to bypass both cache reads and writes. Useful for forced rebuilds or debugging. |

### S3 Key Structure

All cache entries are stored under the `packer-build-cache/` prefix within the bucket:

| Artifact | Key Pattern | Example |
|---|---|---|
| BCC | `packer-build-cache/bcc/bcc-v{VERSION}-{ARCH}.tar.gz` | `packer-build-cache/bcc/bcc-v0.35.0-x86_64.tar.gz` |
| Cassandra source build | `packer-build-cache/cassandra/{VERSION}-{GIT_SHA}.tar.gz` | `packer-build-cache/cassandra/trunk-a1b2c3d4e5f6.tar.gz` |

### Required IAM Permissions

The packer instance profile needs the following permissions on the cache bucket:

```json
{
  "Effect": "Allow",
  "Action": ["s3:GetObject", "s3:PutObject", "s3:HeadObject"],
  "Resource": "arn:aws:s3:::{PACKER_CACHE_BUCKET}/packer-build-cache/*"
}
```

### Cache Invalidation

Cache entries are content-addressed (version + architecture for BCC; version + git SHA for Cassandra source builds), so they are automatically invalidated when the underlying inputs change.

To manually invalidate a cache entry, delete the corresponding S3 object:

```shell
# Invalidate BCC cache for a specific version and architecture
aws s3 rm "s3://${PACKER_CACHE_BUCKET}/packer-build-cache/bcc/bcc-v0.35.0-x86_64.tar.gz"

# Invalidate a Cassandra source build cache entry
aws s3 rm "s3://${PACKER_CACHE_BUCKET}/packer-build-cache/cassandra/trunk-a1b2c3d4e5f6.tar.gz"

# List all cache entries
aws s3 ls "s3://${PACKER_CACHE_BUCKET}/packer-build-cache/" --recursive
```

### Cache Behavior

Cache operations are best-effort. If the bucket is unavailable, permissions are missing, or a download fails, the build falls back to compiling from scratch. A failed cache write never fails the build.

## Directory Structure

```
packer/
├── base/                    # Base AMI configuration
│   ├── base.pkr.hcl        # Packer config for base image
│   └── install/            # Base installation scripts
├── cassandra/              # Cassandra AMI configuration
│   ├── cassandra.pkr.hcl  # Packer config for Cassandra image
│   └── install/            # Cassandra installation scripts
├── Dockerfile              # Test environment (mimics Ubuntu 24.04 AMI)
├── docker-compose.yml      # Test orchestration
├── test-script.sh          # Script testing utility
└── TESTING.md              # Comprehensive testing guide
```
