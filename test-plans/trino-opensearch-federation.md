# Lab Plan: Trino + OpenSearch Federation Verification

## Objective

Verify that Trino's OpenSearch catalog works end-to-end against an AWS OpenSearch domain
provisioned by easy-db-lab. The domain uses an open access policy (no SigV4 required).
Success is Trino returning rows from an OpenSearch index via SQL.

## Cluster Name

trino-opensearch

## Datacenters

single

## Environment

- 1 db node: `i4i.xlarge` (local NVMe, no EBS needed)
- 1 app node: `i4i.xlarge` (local NVMe, Trino coordinator + worker)
- AWS OpenSearch: 1 × `t3.small.search`, version 2.11

## Steps

### 1. Provision the cluster with OpenSearch

```bash
$EDB init trino-opensearch -c 1 -s 1 -i i4i.xlarge -si i4i.xlarge \
  --opensearch.enable \
  --opensearch.instance.type t3.small.search \
  --opensearch.version 2.11 \
  --up
```

Note: the cluster nodes come up in ~5 minutes. The OpenSearch domain takes an additional
15–20 minutes to become active. Proceed to the next step to monitor it.

### 2. Wait for OpenSearch domain to become active

Poll until the domain endpoint appears and status is ACTIVE:

```bash
$EDB opensearch status
```

Repeat until the output shows an endpoint hostname. Then verify the endpoint is reachable
from the control node:

```bash
OPENSEARCH_HOST=$(ssh -F sshConfig control0 "curl -s http://169.254.169.254/latest/meta-data/hostname" 2>/dev/null; $EDB status 2>&1 | grep -i opensearch | grep -o '[a-z0-9.-]*\.es\.amazonaws\.com' | head -1)
```

### 3. Install and start Trino

```bash
$EDB kit install trino
$EDB trino start
```

The `update-catalogs.sh` hook fires automatically and wires the `opensearch` catalog
from the `__OPENSEARCH_ENDPOINT__` template variable populated by cluster state.

### 4. Verify Trino sees the OpenSearch catalog

```bash
$EDB trino sql "SHOW CATALOGS;"
```

Confirm `opensearch` appears in the output.

### 5. Index test documents into OpenSearch

Get the OpenSearch endpoint from cluster status, then POST documents via curl from
the control node (which is inside the VPC):

```bash
$EDB status
```

Note the OpenSearch endpoint from the output (e.g. `search-xxx.region.es.amazonaws.com`),
then index three documents:

```bash
OS_ENDPOINT="<endpoint from status output>"

ssh -F sshConfig control0 "curl -s -X POST 'https://${OS_ENDPOINT}/test-index/_doc' \
  -H 'Content-Type: application/json' \
  -d '{\"name\": \"Alice\", \"score\": 95, \"city\": \"Seattle\"}'"

ssh -F sshConfig control0 "curl -s -X POST 'https://${OS_ENDPOINT}/test-index/_doc' \
  -H 'Content-Type: application/json' \
  -d '{\"name\": \"Bob\", \"score\": 87, \"city\": \"Portland\"}'"

ssh -F sshConfig control0 "curl -s -X POST 'https://${OS_ENDPOINT}/test-index/_doc' \
  -H 'Content-Type: application/json' \
  -d '{\"name\": \"Carol\", \"score\": 92, \"city\": \"Seattle\"}'"
```

Verify the index exists:

```bash
ssh -F sshConfig control0 "curl -s 'https://${OS_ENDPOINT}/test-index/_count'"
```

### 6. Query OpenSearch via Trino

```bash
$EDB trino sql "SHOW SCHEMAS IN opensearch;"
$EDB trino sql "SHOW TABLES IN opensearch.default;"
$EDB trino sql "SELECT * FROM opensearch.default.\"test-index\" LIMIT 10;"
$EDB trino sql "SELECT city, COUNT(*) AS cnt FROM opensearch.default.\"test-index\" GROUP BY city;"
```

Success: Trino returns the three indexed documents and the GROUP BY aggregation
shows Seattle=2, Portland=1.

### 7. Tear down

```bash
$EDB down --auto-approve
```

## Notes

- easy-db-lab provisions OpenSearch with `Principal: "*"` / `Action: "es:*"` and fine-grained
  access control disabled by default — no SigV4 signing is required.
- The OpenSearch catalog is wired automatically via `__OPENSEARCH_ENDPOINT__` when Trino starts.
  If the catalog is missing, run `$EDB trino update-catalogs` manually.
- Index names with hyphens must be double-quoted in Trino SQL: `"test-index"`.
- The Trino OpenSearch connector maps each index to a table in the `default` schema.
