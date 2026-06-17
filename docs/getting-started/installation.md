# Installation

## Prerequisites

### System Requirements

| Requirement | Details |
|-------------|---------|
| **Operating System** | macOS or Linux |
| **Java** | JDK 21 or later |
| **Docker** | Required for building custom AMIs |

### AWS Requirements

- **AWS Account**: A dedicated AWS account is recommended for lab environments
- **AWS Credentials**: Either static access key & secret, or a named profile — including an [AWS SSO (IAM Identity Center)](setup.md#using-aws-sso-iam-identity-center) profile
- **IAM Permissions**: Permissions to create EC2, IAM, S3, and optionally EMR resources

```admonish tip
Run `easy-db-lab show-iam-policies` to see the exact IAM policies required with your account ID populated. See [Setup](setup.md#getting-iam-policies) for details.
```

### Optional

- **AxonOps Account**: For free Cassandra monitoring. Create an account at [axonops.com](https://axonops.com/)

## Install from Release

Download a tarball from the [releases page](https://github.com/rustyrazorblade/easy-db-lab/releases) and add the `bin` directory to your `$PATH`:

```bash
export PATH="$PATH:/path/to/easy-db-lab/bin"
```

## Build from Source

```bash
git clone https://github.com/rustyrazorblade/easy-db-lab.git
cd easy-db-lab
./gradlew assemble
```

The built distribution will be in `build/distributions/`.

## Next Steps

Run the interactive setup to configure your profile:

```bash
easy-db-lab setup-profile
```

See the [Setup Guide](setup.md) for detailed instructions.
