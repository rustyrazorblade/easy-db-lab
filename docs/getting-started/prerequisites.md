# Prerequisites

Before using easy-db-lab, ensure you have the following:

## System Requirements

| Requirement | Details |
|-------------|---------|
| **Operating System** | macOS or Linux |
| **Java** | JDK 21 or later |
| **Docker** | Required for building custom AMIs |

## AWS Requirements

- **AWS Account**: A dedicated AWS account is recommended for lab environments
- **AWS Access Key & Secret**: Credentials for programmatic access
- **IAM Permissions**: Permissions to create EC2, IAM, S3, and optionally EMR resources

```admonish tip
Run `easy-db-lab show-iam-policies` to see the exact IAM policies required with your account ID populated. See [Setup](setup.md#getting-iam-policies) for details.
```

## Optional

- **AxonOps Account**: For free Cassandra monitoring. Create an account at [axonops.com](https://axonops.com/)

## Next Steps

Run the interactive setup to configure your profile:

```bash
easy-db-lab setup-profile
```

See the [Setup Guide](setup.md) for detailed instructions.
