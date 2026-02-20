# easy-db-lab

**[Documentation](https://rustyrazorblade.github.io/easy-db-lab/)**

A tool for creating database lab environments in AWS. Designed for testing, benchmarking, and learning.

## Features

### Databases

- **Apache Cassandra** - Versions 2.2 through trunk, mixed version clusters, custom branches
- **ClickHouse** - Sharded clusters with configurable replication, distributed tables, S3 storage
- **Any K8s-compatible database** - Deploy via Kubernetes manifests

### Infrastructure

- **AWS EC2** - Automated provisioning with configurable instance types
- **Kubernetes (K3s)** - Lightweight K8s on all nodes for deploying databases and tools
- **Spark EMR** - Provision EMR clusters for analytics workloads
- **S3 Integration** - Object storage for ClickHouse and data pipelines

### Observability

- **Prometheus and Grafana** - Pre-configured dashboards for all supported databases
- **OpenTelemetry** - Distributed tracing and metrics collection
- **Flame Graphs** - CPU and wall-clock profiling with async-profiler
- **Kernel Metrics** - bcc-tools for low-level performance analysis

### Developer Experience

- **MCP Server** - AI assistant integration for cluster management
- **Interactive CLI** - REPL mode for reduced typing
- **Homebrew and Docker** - Multiple installation options

## Custom AMI

We use packer to create a single AMI with the following:

* Multiple versions of Cassandra
* ClickHouse (deployed via K3s)
* [bcc tools](https://github.com/iovisor/bcc), [learn about these tools here](https://rustyrazorblade.com/post/2023/2023-11-14-bcc-tools/)
* [async-profiler](https://github.com/async-profiler/async-profiler), [learn about it here](https://rustyrazorblade.com/post/2023/2023-11-07-async-profiler/)
* [cassandra-easy-stress](https://github.com/apache/cassandra-easy-stress) (Apache project, formerly easy-cass-stress)
* [AxonOps agent](https://axonops.com/) (free monitoring up to six nodes)
* K3s distribution of Kubernetes

## Pre-requisites

The following must be set up before using this project:

* [Setup AWS Account API Credentials](https://docs.aws.amazon.com/cli/latest/userguide/cli-configure-files.html)
* [Install Docker Locally](https://www.docker.com/products/docker-desktop/) for Packer and Terraform

## AWS Account Setup

**IMPORTANT: We strongly recommend using a separate AWS account under an organization for lab environments.**

This tool provisions and destroys AWS infrastructure! Using a dedicated account provides:
- **Resource isolation** - No risk of accidentally affecting production resources
- **Cost isolation** - Lab costs separated from production
- **Clean billing** - Easy to see lab-related costs

### Setting Up Your Profile

This part is a bit clunky still, but it's a one time event.  You will need a user account and credentials.

1. Create a User for easy-db-lab and get the credentials.
2. Create a group and add the user to the group.
3. Create 3 managed policies
4. Attach managed policies to the group

### Viewing Required IAM Policies

To see the IAM policies required for easy-db-lab with your account ID populated:

```shell
easy-db-lab show-iam-policies
```

You can filter by policy type:

```shell
easy-db-lab show-iam-policies ec2   # Show only EC2 policy
easy-db-lab show-iam-policies iam   # Show only IAM policy
easy-db-lab show-iam-policies emr   # Show only EMR policy
```

See `bin/set-policies`.

### Interactive Setup

Run the interactive setup to configure your AWS credentials and create necessary resources:

```shell
easy-db-lab setup-profile
```

This will:
- Prompt for your AWS credentials
- Validate your credentials
- Create an EC2 key pair for SSH access
- Create an IAM role for instance permissions
- Set up Packer VPC infrastructure for building AMIs

## Usage

To install `easy-db-lab`, you can use Homebrew, download a release, or clone the project and build it.

### Install A Release using Homebrew

```shell
brew tap rustyrazorblade/rustyrazorblade
brew install easy-db-lab
```

`easy-db-lab` should now be available for you to use.

Skip ahead to Read The Help.

### Use Container Image

A containerized version is available from GitHub Container Registry. This is useful for CI/CD pipelines or environments where you prefer container-based tools.

#### Container Tags

Available tags:
- `latest` - Most recent build from main branch
- `v12` - Specific version (e.g., v12, v13, etc.)
- `12` - Version without 'v' prefix

```shell
# Pull latest version
docker pull ghcr.io/rustyrazorblade/easy-db-lab:latest

# Pull specific version
docker pull ghcr.io/rustyrazorblade/easy-db-lab:v12
```

#### Running Commands

To run commands using the container:

```shell
docker run --rm \
  -v ~/.aws:/root/.aws:ro \                              # Read-only: AWS credentials
  -v ~/.ssh:/root/.ssh:ro \                              # Read-only: SSH keys
  -v $(pwd):/workspace \                                 # Read-write: Working directory for cluster state
  -v /var/run/docker.sock:/var/run/docker.sock \         # Required for Docker operations
  ghcr.io/rustyrazorblade/easy-db-lab:latest --help
```

**Important notes for container usage:**
- Mount your AWS credentials (`~/.aws`) as read-only for authentication
- Mount your SSH keys (`~/.ssh`) as read-only for instance access
- Mount a working directory (`$(pwd):/workspace`) for storing cluster state and configuration
- Mount the Docker socket to allow the tool to use Docker for Terraform/Packer operations
- For convenience, create a shell alias:

```shell
alias easy-db-lab='docker run --rm \
  -v ~/.aws:/root/.aws:ro \
  -v ~/.ssh:/root/.ssh:ro \
  -v $(pwd):/workspace \
  -v /var/run/docker.sock:/var/run/docker.sock \
  ghcr.io/rustyrazorblade/easy-db-lab:latest'
```

Then use it like the native command:

```shell
easy-db-lab setup-profile
easy-db-lab init my-cluster --cassandra 5.0
```

#### Container Limitations

When using the container version, note the following:
- The `build-image` command requires access to your AWS credentials and Docker socket
- Terraform state is stored in the working directory (mount `/workspace` to persist it)
- SSH keys must be mounted and accessible inside the container for instance access
- Performance may be slightly slower than native installation due to container overhead
- The container runs with root privileges to access the Docker socket (required for Terraform/Packer operations)

#### Security Note

The container requires root privileges to access the Docker socket, which is necessary for Terraform and Packer operations. For improved security:
- Use Docker socket access control (e.g., Docker socket proxy) in production environments
- Run containers only in isolated environments
- Never use with untrusted inputs
- Consider using native installation if Docker socket access is a concern

#### Troubleshooting Container Issues

**Permission errors accessing Docker socket:**
```shell
# Ensure your user has Docker permissions
sudo usermod -aG docker $USER
# Log out and back in for changes to take effect
```

**AWS credential mounting issues:**
```shell
# Verify credentials directory exists and is readable
ls -la ~/.aws
# Ensure AWS credentials are properly configured
aws configure list
```

**SSH key permissions in container:**
```shell
# SSH keys should have correct permissions (600)
chmod 600 ~/.ssh/id_rsa
# If using non-default key, specify it in container command
docker run --rm \
  -v ~/.ssh:/root/.ssh:ro \
  -e SSH_KEY=/root/.ssh/my-custom-key \
  ghcr.io/rustyrazorblade/easy-db-lab:latest ...
```

### Download A Release

Download the latest [release](https://github.com/rustyrazorblade/easy-db-lab/releases) and add the project's bin directory
to your PATH.

```shell
export PATH="$PATH:/Users/username/path/to/easy-db-lab/bin"
```

You can skip ahead to Read The Help.

### Build the Project

The following command should be run from the root directory of the project.
Docker will need to be running for this step.

```bash
git clone https://github.com/rustyrazorblade/easy-db-lab.git
cd easy-db-lab
./gradlew shadowJar
```


#### Build the Universal AMI

Using easy-db-lab requires building an AMI if you're building from source.

You can skip this if you're using us-west-2
This can be done once, and reused many times.
The AMI should be rebuilt when updating easy-db-lab.

If you haven't run `easy-db-lab setup-profile` yet, you'll be prompted to set up your profile before building.

```shell
bin/easy-db-lab build-image
```

At the end, you'll see something like this:

```text
==> Builds finished. The artifacts of successful builds are:
--> cassandra.amazon-ebs.ubuntu: AMIs were created:
us-west-2: ami-abcdeabcde1231231
```

That means you're ready!

### Read the Help

Run `easy-db-lab` without any parameters to view all the commands and all options.

### Create The Environment

Note: If you haven't run `easy-db-lab setup-profile` yet, you'll be prompted to set up your profile.

Important: If you've installed the project via homebrew or downloaded a release,
please use the `us-west-2` region.  This limitation will be lifted soon.

First create a directory for the environment, then initialize it, and start the instances.

This directory is your working space for the cluster.

```bash
mkdir cluster
cd cluster
easy-db-lab init -c 3 -s 1 myclustername # 3 node cluster with 1 stress instance
```

You can start your instances now.

```shell
easy-db-lab up
```

To access the cluster, follow the instructions at the end of the output of the `up` command:

```bash
source env.sh # to setup local environment with commands to access the cluster

# ssh to a node
ssh db0
ssh db1 # number corresponds to an instance
c0 # shortcut to ssh db0
ssh app0 # ssh to app/stress node
s0 # shortcut to ssh app0
```

### Select The Cassandra Version

While the nodes in the cluster are up, a version isn't yet selected.  Since the AMI contains multiple versions, you'll
need to pick one.

To see what versions are supported, you can do the following:

```shell
easy-db-lab list
````

You'll see 3.0, 3.11, 4.0, 4.1, and others.

Choose your cassandra version.

```shell
easy-db-lab use 4.1
```

easy-db-lab will automatically configure the right Python and Java versions on the instances for you.

This will also create a local directory corresponding to the name of the version, and will download most of the files in the conf directory to your local dir.  You can edit them, and upload with:

```shell
easy-db-lab update-config
```

You can override the java version by passing the `-j` flag:

```shell
easy-db-lab use 5.0 -j 17
```

Doing this will update each nodes local copy of `/etc/cassandra_versions.yaml`.

You can switch just one host:

```shell
easy-db-lab use 5.0 -j 17 --hosts db0
```

Unlike production tools, easy-db-lab is designed for testing and breaking things, which I find is the best way to learn.

For deploying other databases, see [ClickHouse](https://rustyrazorblade.github.io/easy-db-lab/user-guide/clickhouse/), [OpenSearch](https://rustyrazorblade.github.io/easy-db-lab/user-guide/opensearch/), and [Spark](https://rustyrazorblade.github.io/easy-db-lab/user-guide/spark/).

### Modify the YAML Configuration

You'll see a file called `cassandra.patch.yaml` in your directory.  You can add any valid cassandra.yaml parameters,
and the changes will be applied to your cluster.  The `listen_address` is handled for you,
you do not need to supply it.  The data directories are set up for you.

You can also edit the JVM options files under the different local version directories. Different versions of Cassandra use different
names for jvm.options.  Edit the ones in the directory that corresponds to the version you're using.

```shell
easy-db-lab update-config # uc for short
```

### Start The Cluster

Start the cluster.  It will take about a minute to go through the startup process

```shell
easy-db-lab start
```

### Log In and Have Fun!

**Important Directories:**

```shell
# The ephemeral or EBS disk is automatically formatted as XFS and mounted here.
/mnt/db1

# Database-specific subdirectories
/mnt/db1/cassandra    # Cassandra data
/mnt/db1/clickhouse   # ClickHouse data
/mnt/db1/otel         # OpenTelemetry logs

# Cassandra data files
/mnt/db1/cassandra/data

# hints
/mnt/db1/cassandra/hints

# commitlogs
/mnt/db1/cassandra/commitlog

# flame graphs and artifacts
/mnt/db1/cassandra/artifacts

# Note: /mnt/cassandra symlinks to /mnt/db1/cassandra for backwards compatibility

# axonops agents for different versions of Cassandra
/usr/local/share/axonops
```

Multiple cassandra versions are installed at /usr/local/cassandra.

The current version is symlinked as /usr/local/cassandra/current:

```shell
ubuntu@db0:~$ readlink /usr/local/cassandra/current
/usr/local/cassandra/4.1
```

This allows us to support updating, mixed version clusters, A/B version testing, etc.


**Profiling with Flame Graphs**

https://rustyrazorblade.com/post/2023/2023-11-07-async-profiler/

Using easy-db-lab `env.sh`, you can run a profile and generate a flamegraph,
which will automatically download after it's complete by doing the following:

```shell
c-flame db0
```

The data will be saved in `artifacts/db0`

Or on a node, generate flame graphs with `flamegraph`.

There are several convenient aliases defined in env.sh.
You may substitute any cassandra host.
You may pass extra parameters, they will be passed along automatically.


| Command                 | Description                                                                              |
|-------------------------|------------------------------------------------------------------------------------------|
| `c-flame`      | CPU Flame graph                                                                          |
| `c-flame-wall` | wall clock profiling, picks up I/O, parked threads filtered out                          |
| `c-flame-compaction` | More specific wall clock profiling to compaction                                         |
| `c-flame-offcpu ` | Just tracks time spent when cpu is unscheduled, mostly I/O                               |
| `c-flame-sepworker`       | Request handling, by default this is CPU time. You can add -e wall to make it wall time. |


**Aliases**

On each node there are several aliases for commonly run commands:

| command | action                                     |
| --------|---------------------------------------------|
 | c | cqlsh (auto use the correct hostname)      |
| ts | tail cassandra system log                  |
| nt | nodetool                                   |
| d | cd to /mnt/db1/cassandra/data directory    |
| l | cd to /mnt/db1/cassandra/logs directory    |
| v | ls -lahG (friendly output)                 |


### Shut it Down

To tear down the entire environment, simply run the following and confirm:

```shell
easy-db-lab down
```

## Tools

bcc-tools is a useful package of tools

https://rustyrazorblade.com/post/2023/2023-11-14-bcc-tools/

## MCP Server Integration

easy-db-lab includes a Model Context Protocol (MCP) server that enables AI assistants like Claude Code to interact directly with your database clusters.

### Starting the MCP Server

To start the MCP server:

```shell
easy-db-lab server --port 8888
```

This starts the MCP server on port 8888 (you can use any available port).

### Integrating with Claude Code

Once the MCP server is running, add it to Claude Code:

```shell
claude mcp add --transport sse easy-db-lab http://127.0.0.1:8888/sse
```

This establishes a Server-Sent Events (SSE) connection between Claude Code and your easy-db-lab MCP server.

### What You Can Do

With MCP integration, Claude Code can:

* Manage and provision clusters directly
* Configure and deploy Cassandra, ClickHouse, and OpenSearch
* Run performance tests and analyze results
* Submit Spark jobs and check their status
* Troubleshoot issues by analyzing logs and metrics
* Automate complex multi-step cluster operations

For detailed documentation, see the [MCP Integration section in the user manual](https://rustyrazorblade.github.io/easy-db-lab/integrations/mcp-server/).

## Sanity Check Test

This initializes then shuts down a cluster.  Useful after major refactors and before a release.

```bash
gw clean test shadowjar installdist  && easy-db-lab init test --up && source env.sh && edl use 5.0 && edl start && edl down --yes
```

## Contributing

Interested in contributing?  Check out the [good first issue tag](https://github.com/rustyrazorblade/easy-db-lab/issues?q=is%3Aissue+is%3Aopen+label%3A%22good+first+issue%22) first!  Please read the [development documentation](https://rustyrazorblade.github.io/easy-db-lab/development/overview/) before getting started.

