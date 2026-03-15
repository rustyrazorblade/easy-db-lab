# Grafana Dashboards

easy-db-lab includes a set of Grafana dashboards that are automatically deployed to the cluster's Grafana instance. These same dashboards are also published as a downloadable artifact on every versioned GitHub Release, so you can import them into any external Grafana instance.

## Downloading Dashboards

The dashboard archive is published as `easy-db-lab-dashboards.zip` on each GitHub Release.

**Latest from main (pre-release, updated on every push):**

```
https://github.com/rustyrazorblade/easy-db-lab/releases/download/latest/easy-db-lab-dashboards.zip
```

**Specific version:**

```
https://github.com/rustyrazorblade/easy-db-lab/releases/download/v<VERSION>/easy-db-lab-dashboards.zip
```

## Importing into Grafana

Grafana does not support importing a zip archive directly. Import each dashboard individually:

1. Download and extract the zip archive
2. In Grafana, go to **Dashboards > Import**
3. Click **Upload dashboard JSON file** and select one of the extracted `.json` files
4. Repeat for each dashboard you want to import

## Datasource Configuration

```admonish warning
All dashboards default to a datasource named **VictoriaMetrics**. If your Grafana instance uses a different Prometheus-compatible datasource name, you will need to select it from the datasource dropdown at the top of each dashboard after import.
```

No manual editing of the JSON files is required — Grafana's built-in datasource variable picker lets you switch datasources at runtime.

## Included Dashboards

| Dashboard | Description |
|-----------|-------------|
| `cassandra-overview` | Main Cassandra metrics (throughput, latency, compactions, etc.) |
| `cassandra-condensed` | Compact Cassandra view for at-a-glance monitoring |
| `clickhouse` | ClickHouse server metrics |
| `clickhouse-logs` | ClickHouse log analysis |
| `opensearch` | OpenSearch cluster metrics |
| `emr` | Spark/EMR job metrics |
| `profiling` | Continuous profiling via Pyroscope |
| `stress` | Load test progress and throughput |
| `system-overview` | Host-level metrics (CPU, memory, disk, network) |
| `s3-cloudwatch` | S3 CloudWatch metrics via YACE |
| `log-investigation` | VictoriaLogs log exploration |
