# Lab Plan: Multi-DC Spark Bulk Writer (S3/IAM)

## Goal

Provision two Cassandra DCs as a single multi-DC cluster, then use the `IamBulkWriter` Spark job
to bulk-load 1M rows via S3/IAM transport with coordinated multi-DC write. Success: both DCs
report 1,000,000 rows in `bulk_test.bulk_test_data` after the Spark job completes.

## Environment

- 2 DCs: dc1 and dc2, each with 3 DB nodes on `c5d.2xlarge` (local NVMe, no EBS)
- Spark EMR on DC1: 1×`m5.xlarge` master, 2×`m5.xlarge` workers
- Cassandra 5.0, `storage_compatibility_mode: NONE` (required for S3 bulk import)
- DC names after Ec2Snitch + dc_suffix: `us-west-2_dc1`, `us-west-2_dc2`
- SIDECAR_IMAGE and AWS_PROFILE from `.env`

## Steps

### 1. Load environment and create cluster workspace

```bash
source .env

CLUSTER_BASE="clusters/spark-multidc-s3-$(date +%Y%m%d-%H%M%S)"
DC1_DIR="$CLUSTER_BASE/dc1"
DC2_DIR="$CLUSTER_BASE/dc2"
mkdir -p "$DC1_DIR" "$DC2_DIR"
bin/create-easy-db-lab-wrapper "$DC1_DIR"
bin/create-easy-db-lab-wrapper "$DC2_DIR"
DC1="$DC1_DIR/easy-db-lab"
DC2="$DC2_DIR/easy-db-lab"
```

Exports `SIDECAR_IMAGE` and `AWS_PROFILE` from `.env` for all subsequent steps.

### 2. Build and upload the IAM bulk writer JAR

```bash
bin/upload-iam-bulk-writer.sh
ACCOUNT_BUCKET=$(yq '.s3Bucket' ~/.easy-db-lab/profiles/default/settings.yaml)
JAR_S3_PATH="s3://$ACCOUNT_BUCKET/spark/bulk-writer-s3-iam-all.jar"
```

Builds `:spark:bulk-writer-s3-iam:shadowJar` and uploads to the account bucket. `JAR_S3_PATH`
is derived from the same settings the script uses, so it is always consistent.

### 3. Provision DC1 (with Spark) and DC2 in parallel

```bash
$DC1 init dc1 \
  --db 3 --app 0 \
  --instance c5d.2xlarge \
  --cidr 10.0.0.0/16 \
  --spark.enable \
  --spark.master.instance.type m5.xlarge \
  --spark.worker.instance.type m5.xlarge \
  --spark.worker.instance.count 2 \
  --up &
$DC2 init dc2 \
  --db 3 --app 0 \
  --instance c5d.2xlarge \
  --cidr 10.1.0.0/16 \
  --up &
wait
```

DC1 gets the Spark EMR cluster. DC2 is Cassandra-only. Running in parallel cuts provisioning time roughly in half.

### 4. VPC peering: connect DC1 and DC2

```bash
REGION=$(jq -r '.initConfig.region' "$DC1_DIR/state.json")
DC1_VPC=$(jq -r '.infrastructure.vpcId' "$DC1_DIR/state.json")
DC2_VPC=$(jq -r '.infrastructure.vpcId' "$DC2_DIR/state.json")

PEER_ID=$(aws ec2 create-vpc-peering-connection \
  --vpc-id "$DC1_VPC" \
  --peer-vpc-id "$DC2_VPC" \
  --region "$REGION" \
  --query 'VpcPeeringConnection.VpcPeeringConnectionId' \
  --output text)

aws ec2 accept-vpc-peering-connection \
  --vpc-peering-connection-id "$PEER_ID" \
  --region "$REGION"

DC1_RTB=$(aws ec2 describe-route-tables \
  --filters "Name=vpc-id,Values=$DC1_VPC" \
  --region "$REGION" \
  --query 'RouteTables[0].RouteTableId' \
  --output text)

DC2_RTB=$(aws ec2 describe-route-tables \
  --filters "Name=vpc-id,Values=$DC2_VPC" \
  --region "$REGION" \
  --query 'RouteTables[0].RouteTableId' \
  --output text)

aws ec2 create-route \
  --route-table-id "$DC1_RTB" \
  --destination-cidr-block 10.1.0.0/16 \
  --vpc-peering-connection-id "$PEER_ID" \
  --region "$REGION"

aws ec2 create-route \
  --route-table-id "$DC2_RTB" \
  --destination-cidr-block 10.0.0.0/16 \
  --vpc-peering-connection-id "$PEER_ID" \
  --region "$REGION"
```

### 5. Open security groups for cross-DC traffic

```bash
DC1_SG=$(jq -r '.infrastructure.securityGroupId' "$DC1_DIR/state.json")
DC2_SG=$(jq -r '.infrastructure.securityGroupId' "$DC2_DIR/state.json")

aws ec2 authorize-security-group-ingress \
  --group-id "$DC1_SG" \
  --protocol -1 \
  --cidr 10.1.0.0/16 \
  --region "$REGION"

aws ec2 authorize-security-group-ingress \
  --group-id "$DC2_SG" \
  --protocol -1 \
  --cidr 10.0.0.0/16 \
  --region "$REGION"
```

### 6. Select Cassandra 5.0 in each DC

```bash
$DC1 cassandra use 5.0
$DC2 cassandra use 5.0
```

Generates `cassandra.patch.yaml` in each DC directory with correct directory paths and snitch settings.
Do not overwrite these files in subsequent steps — only append new keys.

### 7. Set dc_suffix to distinguish the two DCs

```bash
echo "dc_suffix=_dc1" > "$DC1_DIR/5.0/cassandra-rackdc.properties"
echo "dc_suffix=_dc2" > "$DC2_DIR/5.0/cassandra-rackdc.properties"
```

Ec2Snitch names the DC after the AWS region. The suffix produces `us-west-2_dc1` and `us-west-2_dc2`.

### 8. Merge cluster config into cassandra.patch.yaml

Append cluster-level settings — do NOT overwrite what `cassandra use 5.0` generated:

```bash
DC1_SEED=$(jq -r '.hosts.Cassandra[0].privateIp' "$DC1_DIR/state.json")
DC2_SEED=$(jq -r '.hosts.Cassandra[0].privateIp' "$DC2_DIR/state.json")

cat >> "$DC1_DIR/cassandra.patch.yaml" <<EOF
cluster_name: "spark-multidc-s3-test"
storage_compatibility_mode: NONE
seed_provider:
  - class_name: org.apache.cassandra.locator.SimpleSeedProvider
    parameters:
      - seeds: "${DC1_SEED},${DC2_SEED}"
EOF

cat >> "$DC2_DIR/cassandra.patch.yaml" <<EOF
cluster_name: "spark-multidc-s3-test"
storage_compatibility_mode: NONE
seed_provider:
  - class_name: org.apache.cassandra.locator.SimpleSeedProvider
    parameters:
      - seeds: "${DC1_SEED},${DC2_SEED}"
EOF
```

`storage_compatibility_mode: NONE` is required — without it the sidecar marks restore jobs removed
immediately and no data is imported (symptom: COUNT returns 0 after a successful Spark job).

### 9. Push config and start Cassandra in both DCs

```bash
$DC1 cassandra update-config cassandra.patch.yaml
$DC2 cassandra update-config cassandra.patch.yaml

SIDECAR_ARGS=""
[ -n "${SIDECAR_IMAGE:-}" ] && SIDECAR_ARGS="--sidecar-image $SIDECAR_IMAGE"

$DC1 cassandra start $SIDECAR_ARGS &
$DC2 cassandra start $SIDECAR_ARGS &
wait
```

### 10. Verify all 6 nodes are UP/Normal

```bash
$DC1 cassandra nt status
```

All 6 nodes should appear: 3 in `us-west-2_dc1` and 3 in `us-west-2_dc2`. All status `UN`.

### 11. Configure S3 bucket replication (DC1 → DC2)

The Spark job writes SSTables to DC1's data bucket. DC2's sidecar reads from DC2's bucket,
populated via S3 replication.

```bash
DC1_BUCKET=$($DC1 aws s3-bucket)
DC2_BUCKET=$($DC2 aws s3-bucket)

aws s3api put-bucket-versioning --bucket "$DC1_BUCKET" \
  --versioning-configuration Status=Enabled
aws s3api put-bucket-versioning --bucket "$DC2_BUCKET" \
  --versioning-configuration Status=Enabled

REPLICATION_ROLE_ARN=$(aws iam list-roles \
  --query "Roles[?contains(RoleName, 'replication')].Arn" \
  --output text)

aws s3api put-bucket-replication \
  --bucket "$DC1_BUCKET" \
  --replication-configuration "{
    \"Role\": \"$REPLICATION_ROLE_ARN\",
    \"Rules\": [{
      \"Status\": \"Enabled\",
      \"Filter\": {\"Prefix\": \"\"},
      \"Destination\": {\"Bucket\": \"arn:aws:s3:::$DC2_BUCKET\"}
    }]
  }"
```

### 12. Submit the multi-DC Spark bulk write job

```bash
DC1_IPS=$(jq -r '[.hosts.Cassandra[].privateIp] | join(",")' "$DC1_DIR/state.json")

$DC1 spark submit \
  --name multidc-bulk-write \
  --jar "$JAR_S3_PATH" \
  --main-class com.rustyrazorblade.easydblab.spark.IamBulkWriter \
  --conf "spark.easydblab.contactPoints=$DC1_IPS" \
  --conf "spark.easydblab.keyspace=bulk_test" \
  --conf "spark.easydblab.table=bulk_test_data" \
  --conf "spark.easydblab.localDc=us-west-2_dc1" \
  --conf "spark.easydblab.s3.bucket=$DC1_BUCKET" \
  --conf "spark.easydblab.dc.us-west-2_dc2.s3.readBucket=$DC2_BUCKET" \
  --conf "spark.easydblab.dc.us-west-2_dc2.s3.region=$REGION" \
  --wait
```

The job auto-discovers both DCs from the Cassandra Java driver, creates `bulk_test` with
NetworkTopologyStrategy (RF=3 per DC), and bulk-writes 1,000,000 rows via S3 transport.

### 13. Verify data in DC1

```bash
$DC1 cassandra cql "SELECT COUNT(*) FROM bulk_test.bulk_test_data"
```

Expected: `1000000`

### 14. Verify data in DC2

```bash
$DC2 cassandra cql "SELECT COUNT(*) FROM bulk_test.bulk_test_data"
```

Expected: `1000000`. If 0, S3 replication may still be in progress — wait 1-2 minutes and retry.

### 15. Tear down

```bash
$DC1 down --auto-approve
$DC2 down --auto-approve
```

## Notes

- All `spark submit`, `spark status`, and `spark logs` commands must use `$DC1` — that is where the EMR cluster is registered.
- `IamBulkWriter` auto-discovers both DCs from the driver. No manual `coordinated_write_config` JSON is needed.
- S3 replication is asynchronous. DC2 may return COUNT=0 immediately after the Spark job — this is expected. Retry after 1-2 minutes.
- If the Spark job fails with "Failed to connect to Cassandra", verify that `contactPoints` are DC1 private IPs and that ports 9042 (CQL) and 9043 (sidecar) are open in DC2's security group to DC1's CIDR.
- If COUNT=0 in both DCs after the job succeeds, confirm `storage_compatibility_mode: NONE` is in each `cassandra.patch.yaml` and was pushed before start.
