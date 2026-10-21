# Local Observability Stack

Every cluster writes its metrics, logs and traces to the shared store in the account bucket, and they stay there after `down`. The local observability stack is a Grafana on your own machine that reads that store directly. It needs no running cluster and no import step, and it shows every tenant, including clusters that were torn down long ago.

The stack never writes to the bucket. It runs Mimir, Loki and Tempo in read-only modes, behind a read gate that passes only `GET` and `HEAD` requests to S3.

Profiles are not part of the local stack yet ([#1016](https://github.com/rustyrazorblade/easy-db-lab/issues/1016)). The Profiles datasource is there, so dashboards load, but it answers nothing.

## What you need

- easy-db-lab, installed from Homebrew or a release. No source checkout is needed: everything the stack uses ships with the tool.
- A profile set up with `easy-db-lab profile setup` that names an AWS profile (an SSO profile or one with keys in `~/.aws`). The stack reads the bucket with that profile. A profile set up with static keys only, and no AWS profile name, cannot be used.
- The account bucket. It exists once any cluster has run `up` with your profile.
- Docker Engine 28 or newer and Docker Compose 2.35 or newer. The signer's healthcheck mounts a small busybox image into the signer container, and image mounts need these versions.

## Create the stack

Log in first if your AWS profile uses SSO:

```bash
aws sso login --profile <your-aws-profile>
```

Then write the stack into a directory of your choice. The directory is required; it is created when it does not exist:

```bash
easy-db-lab observability local create ~/lab-local
```

The command works from any directory, not only a cluster workspace. It:

1. checks that your AWS profile can read the account bucket, and fails if it cannot (see [Credentials](#credentials));
2. lists the tenants in the store, one per directory under `mimir/`;
3. writes `compose.yml`, the configuration files under `config/`, and the manifest `.easy-db-lab-local-stack.json`;
4. writes the packaged dashboards under `dashboards/current`.

It prints the tenants it found and the home tenant:

```
Wrote the local observability stack to /Users/you/lab-local (9 files)
Tenants: acme, default (home: default)
Start it with: cd /Users/you/lab-local && docker compose up -d
Grafana: http://127.0.0.1:3000
Using packaged dashboards: installed 50 dashboards in /Users/you/lab-local. Grafana loads them when the stack starts.
```

The home tenant is the tenant the stable `mimir`, `loki` and `tempo` datasources read, and the one the dashboards open on. It is `default` unless you pass `--tenant`:

```bash
easy-db-lab observability local create ~/lab-local --tenant acme
```

## Start it and open Grafana

```bash
cd ~/lab-local
docker compose up -d
```

Open <http://127.0.0.1:3000>. You are in as an anonymous user with the Admin role; there is no login. Grafana and the documents web server (<http://127.0.0.1:3080>, which serves experiment reports to the dashboards) are published on `127.0.0.1` only. Use that address, not `localhost`: Grafana answers only `127.0.0.1` and redirects a request under any other host name there, so a web page that points its own name at your machine cannot drive Grafana.

The first start takes a minute or two: Mimir loads the store's block index, Loki its log index, and Tempo lists every tenant's blocks. A cluster torn down a few minutes ago shows too: Tempo finds blocks by listing the bucket, not only from its tenant index.

The first query over older data is slow: nothing is cached yet, and Mimir, Loki and Tempo read the blocks and chunks from S3. A panel can take a minute or more the first time and is fast after that, while the caches are warm. Grafana waits up to five minutes for a datasource, instead of its default 30 seconds, and Tempo waits as long for a cold read of its blocks, instead of its own 30 seconds for a search and 10 seconds for a trace lookup. A cold trace search or trace lookup that takes less than five minutes therefore shows its data rather than a 502.

The stack's memcached (4 GB, taking memory only as it fills) holds Mimir's caches and everything Tempo reads from a block. A trace lookup reads every page of the block that holds the trace, whichever trace it is, so the first lookup in a block reads the whole block from S3 and the next lookups and searches in that block read it from memory. The caches only hold copies; `docker compose down` empties them, and nothing is lost.

Grafana installs no plugin at start: no packaged dashboard needs one, and the download from grafana.com could hold its start for minutes. A cluster's Grafana still installs them, since the ClickHouse kit's datasource is one.

The datasources are the ones a cluster's Grafana has, with the same UIDs:

- `mimir`, `loki` and `tempo` read the home tenant;
- `mimir-<tenant>`, `loki-<tenant>` and `tempo-<tenant>` read one tenant each;
- `mimir--all`, `loki--all` and `tempo--all` read every tenant at once, so clusters of different tenants show on one panel.

The dashboards are the cluster's, with no current cluster or experiment: each Experiment dashboard opens on the newest experiment. Pick another experiment, or a tenant view, with the pickers at the top.

Stop the stack with `docker compose down`. Nothing in the bucket changes. The stack's own working state, under `cache/` and `grafana-data/`, can be removed at any time; it is rebuilt from the store on the next start.

## What is in the directory

| Path | Owner | What it is |
|------|-------|------------|
| `compose.yml`, `config/` | the tool | The stack. Re-running `local create` rewrites these. |
| `.easy-db-lab-local-stack.json` | the tool | The manifest: the tool version, the home tenant, the tenants, the Grafana and documents ports, and a checksum of each owned file. |
| `dashboards/current/` | the tool | The dashboards Grafana shows. `local create` and `local dashboards` replace it whole. |
| `cache/`, `grafana-data/` | the stack | Working state of the components and of Grafana. The tool never opens them. |
| `compose.override.yml` | you | Your own changes. Compose merges it into `compose.yml` on its own. The tool never opens, writes or removes it. |

Put your own changes in `compose.override.yml`, not in `compose.yml`: a re-run overwrites an owned file you edited, and names each one it overwrote:

```
These files had been edited; the edits were replaced: config/store-gate.conf
```

`local create` refuses a directory that holds files and no manifest, and writes nothing to it, so it cannot overwrite a directory that is not a stack.

## New tenants

The datasources are built from the tenants present when `local create` ran. When a tenant writes its first data later, run `local create` again on the same directory. It lists the tenants again and rewrites the datasource file. If the stack is running, Grafana reloads its datasources and dashboards, so the new tenant shows with no restart:

```
Grafana at http://127.0.0.1:3000 reloaded its datasources
```

Grafana reloads the datasources first, then the dashboards. If it refuses a reload, the command fails and says so: the stack's files were already rewritten, so `docker compose restart grafana` in the directory loads them.

## Change the dashboards

To load dashboards you are editing, without a rebuild or a restart, point `local dashboards` at a directory that holds one subdirectory per Grafana folder, each with dashboard JSON files:

```bash
easy-db-lab observability local dashboards ~/lab-local --from ~/work/dashboards
```

Without `--from`, it puts back the dashboards packaged with the tool, and says so:

```bash
easy-db-lab observability local dashboards ~/lab-local
```

The command writes each dashboard of the new tree over its old copy in the directory Grafana reads, removes the dashboards the new tree does not have, and asks Grafana to reload its dashboards. It never calls docker, so no container is recreated and nothing else changes. The stack must be running: when Grafana does not answer on the port the manifest records, the command fails and changes nothing.

`--from` replaces the whole tree: Grafana then shows only the dashboards in that directory, and the packaged core and kit dashboards are gone until you run `local dashboards` again without `--from`.

A JSON file at the top of the `--from` directory, or nested more than one directory deep, has no Grafana folder. The command names every such file and changes nothing.

## Credentials

The stack reads the bucket as you, with the AWS profile your easy-db-lab profile names. The signer mounts your `~/.aws` read-only, so it can never rewrite your SSO cache.

The signer would sign any request, so only the read gate and the store check can reach it: it shares a network with those two alone, and reaches S3 over a network of its own. Grafana, the documents view and the backends reach the bucket only through the gate, which passes only GET and HEAD. Grafana's data source proxy is also limited to the stack's own backends, so a datasource added in Grafana cannot be pointed at anything else.

A store the stack cannot read fails loudly, at three points:

- `local create` fails, writes nothing, and names the profile, the AWS error code and the fix:

  ```
  Cannot read the account bucket with profile edl: ExpiredToken
  Nothing was written. Log in and run the command again: aws sso login --profile edl
  ```

- `docker compose up` runs a one-shot `store-check` first. When it cannot read the bucket it exits non-zero with `cannot read the account bucket ... with profile edl: ...`, and no backend starts. See its message with `docker compose logs store-check`. A credentials failure (an expired SSO token, no credentials for the profile) says `credentials: ...` and prints `aws sso login --profile edl`; a signer that could not reach S3 at all (a DNS, TCP or TLS error) says `the signer could not reach S3: ...` instead, since logging in again would not help.
- While the stack runs, the signer checks the bucket every minute. `store-signer` is the only service with a health status: `docker compose ps` shows it `healthy` or `unhealthy`, and every other service just `Up`.

The signer turns `unhealthy` when it can no longer read the bucket, which happens in two ways:

- its credentials end. The signer holds the role session it got from your SSO token in memory, so it keeps working for a while after the SSO token itself expires, and turns unhealthy only when that role session ends, which can be hours later;
- it cannot reach S3, for example when this machine loses its network.

When an SSO session expires, run `aws sso login --profile <profile>` on your machine. A running signer picks up the new token with no restart.

Expired credentials do not always show in Grafana. Mimir and Loki keep answering from what they already loaded, and anything written after the expiry is missing until you log in again. If panels look stale, check `docker compose ps` for an unhealthy signer.

## Nothing writes to the bucket

Every component reaches S3 through `store-gate`, which passes `GET` and `HEAD` and answers 403 to anything else, so no component can write, rewrite or delete an object. Mimir and Loki run read-path components only, and no component compacts, merges or deletes data.

Tempo's single binary tries to write its tenant index, `tempo/<tenant>/index.pb.zst`, each time it lists the bucket (every 5 minutes). The gate refuses it, and Tempo logs `failed to write tenant index ... Access Denied`. That error is expected and harmless. Every refused request is logged a second time on a line that starts with `REFUSED`:

```bash
docker compose logs store-gate | grep REFUSED
```

## Troubleshooting

- **No data at all.** Check `docker compose ps`: a `store-check` that exited 1, or an unhealthy `store-signer`, means the credentials cannot read the bucket. `docker compose logs store-check` says why.
- **`local create` or `export` says the check could not reach S3.** That is a network problem (a timeout, a refused connection, a name that did not resolve, TLS), not your credentials: logging in again will not help. Check the VPN, proxy or DNS and run the command again. Expired or refused credentials are reported apart, with the `aws sso login` command.
- **A tenant is missing from the pickers.** Run `local create` on the directory again.
- **`docker compose up` fails with `port is already allocated`.** Grafana uses port 3000 and the documents web server port 3080 on `127.0.0.1`. Stop whatever holds them, then run `docker compose up -d` again.

## Export a tenant

`observability export` copies a tenant's data into one zip that opens as this same stack on any machine, with no network, no AWS credentials and no cluster:

```bash
easy-db-lab observability export ~/acme-export.zip --tenant acme
```

The zip holds the tenant's metrics, logs and traces, its annotation backups and its reports, the stack (`compose.yml` and its configuration), the dashboards, and a `README.txt`. Profiles are not included ([#1016](https://github.com/rustyrazorblade/easy-db-lab/issues/1016)); the command and the README both say so. The command works from any directory, not only a cluster workspace.

The export reads the bucket with your AWS profile, like `local create`, and writes only the zip and a work directory beside it. Nothing in the bucket is written, changed or deleted, and nothing is uploaded.

```
Exporting tenant acme: 4210 objects, 812 MiB (851443712 bytes); 120511 MiB free
Exported tenant acme to /Users/you/acme-export.zip (4231 files, 812 MiB before compression).
Profiles are not included (issue #1016).
Open it with: unzip /Users/you/acme-export.zip, then docker compose up in the directory it makes; see its README.txt.
The work directory /Users/you/acme-export.zip.work-5c1d9e0a (3 MiB, 3145728 bytes) is kept; remove it yourself when you no longer need it.
```

### Only the tenant's logs index

Loki keeps the index of the logs it receives in shared files, one per upload, that hold every tenant the uploading Loki received. Only Loki's compactor splits them into one file per tenant. So that the zip holds nothing of another tenant, the export copies the shared files to its work directory and runs Loki's own compactor there, in a local docker container, to split them; then it puts only the tenant's files into the zip. This is the one step of a tenant export that needs docker:

- The container is the stack's Loki image, so docker must be running and the image must be on the machine or downloadable.
- It has no network and no AWS credentials, runs as you, and reads and writes only the copy in the work directory. Retention and deletion are off; it only splits and merges index files.
- When no shared file was copied (the account compactor had already split them all), no container runs.
- If the container fails, or a shared file is left after it ran, the export fails with the logs signal, leaves no zip, and names the container's saved log, `loki/split/compose.log` in the work directory.

The work directory `<path>.work-<id>/` holds the copy of the index and the compactor's log. It is kept after the export, and after a failure; nothing removes it.

### What the export checks

- Your credentials must be able to read the account bucket. The export checks this first, the way `local create` does, and writes nothing when they cannot. An expired SSO session looks like this:

  ```
  Cannot read the account bucket with profile lab: ExpiredTokenException: The SSO session associated with this profile has expired or is otherwise invalid. ...
  Nothing was written. Log in and run the command again: aws sso login --profile lab
  ```

  A profile that holds access keys instead of an AWS profile is named as such, with no login hint.
- The path must be a new `.zip` file in a directory that exists. An existing file is never overwritten.
- The command lists everything it will copy, prints the size, and fails before copying anything when the disk the zip goes to does not have that much free space, plus twice the size of the tenant's Loki index for the copy of it the export splits in its work directory.
- A tenant with no metrics, logs or traces fails and names the prefixes it looked under.
- The zip is written under a temporary name beside the path (`<path>.partial-<random>`) and moved into place only when it is complete. When anything fails, no zip is left at the path, the temporary file is removed, the work directory is kept, and the error names what it was copying (metrics, logs, traces, annotations, reports) and the step.

The export is safe to run while clusters write to the store and the account compactor merges blocks: it copies each backend in an order that keeps the copy consistent, and writes the zip's own Mimir and Tempo indexes from the blocks that are in it.

### Open an export

The zip carries no images. The machine that opens it needs the images the stack runs, which the `README.txt` lists as `docker pull` lines. Pull them while you still have a network:

```bash
docker pull grafana/grafana:13.2.2   # and the others the README lists
```

Then, with or without a network:

```bash
unzip acme-export.zip
cd acme-export
docker compose up -d
```

Open <http://127.0.0.1:3000>. The datasources and dashboards are the ones a cluster has, for the exported tenant; the documents view (<http://127.0.0.1:3080>) serves the tenant's reports from the zip.

The copied data is in `data/` and is mounted read-only into every component, so opening the export never changes it. Nothing compacts, merges or deletes. Tempo logs `failed to write tenant index ... read-only file system` each time it lists its blocks, and keeps running; that message is expected and harmless. It is not `failed to create store: mkdir /store/tempo: read-only file system`, which stops Tempo and means `data/tempo/` is missing. Every export holds that directory, even one with no traces. Grafana installs no plugin at start, since with no network the download would stop it, and no packaged dashboard needs one.

Offline, the markers on the dashboards (test runs and other annotations) come from the annotation mirror: every Grafana annotation is also written to Loki, and the export carries those log lines. Grafana's own annotation list is empty in an opened export, so a marker shows only on a dashboard that reads markers from Loki, as the core dashboards do.

#### With and without a network

The exported `compose.yml` needs no network: every backend reads `data/` from disk, nothing uses AWS credentials, and no service waits on anything outside the project. With no network, it starts and shows its data. Grafana logs that it could not install its default apps, and that does not stop it.

The compose file does not cut the stack off from the network. It runs on an ordinary compose project network, so when the machine has a network the containers can reach it. Mimir, Loki and Tempo run with usage reporting off. Grafana runs with its own defaults: it tries to install its default apps, checks grafana.com for updates, and sends anonymous usage statistics.

To prove that the stack opens with no outbound connection at all, put it on an internal network. Write a `compose.override.yml` beside `compose.yml` (the tool never writes or reads that file):

```yaml
networks:
  default:
    internal: true
```

`docker compose up -d` then starts the same stack, and no container can reach anything outside the project. Docker publishes no port from an internal network, so Grafana is not on <http://127.0.0.1:3000> while the override is in place. Check it from inside the stack instead:

```bash
docker compose exec -T grafana wget -q -O - http://127.0.0.1:3000/api/health
docker compose exec -T grafana wget -q -O - 'http://127.0.0.1:3000/api/datasources/proxy/uid/mimir/api/v1/labels'
```

To get the browser view back, remove the override, then run `docker compose down` and `docker compose up -d` (or `docker compose up -d --force-recreate`). A plain `docker compose up -d` after removing the override leaves the containers unable to resolve each other's names, so the datasources fail.

`observability local dashboards` works on an opened export, as on a live stack. `observability local create` refuses an opened export's directory, so it never turns the export into a live stack.

## Export clusters or an experiment

`--cluster` (repeatable) exports only the clusters you name, and `--experiment` every cluster of one experiment. The zip opens the same way as a tenant export:

```bash
easy-db-lab observability export ~/lab-pair.zip --cluster lab-0f8fad5b-d9cb-469f-a165-70867728950e --cluster lab-7c9e6679-7425-40de-944b-e07fc1f90ae7
easy-db-lab observability export ~/e1.zip --experiment e1 --tenant acme
```

`--experiment` reads the experiment of one tenant: `--tenant`, else the tenant of the cluster workspace you run it in, else `default`, and it says which. Two tenants may each have an experiment of the same name; only the one you name is exported. A cluster from before experiments existed is an experiment of its own, named by its `<name>-<id>`. Every cluster found for the experiment must have a `cluster` label that is such a `<name>-<id>`; if one does not, the export stops, naming each such value, before it reads any of the experiment's data.

`--cluster` takes a cluster's full `<name>-<id>`, as its `cluster` label holds it: the cluster's name, `-`, and its UUID. Any other value is refused, naming it, before anything is read.

`--cluster` finds each cluster's tenant: from the workspace when you run it in that cluster's own workspace, otherwise from the store, and it says which for each cluster. Clusters of different tenants may be exported together; the first cluster's tenant is the one the stable datasources read, and the opened stack has datasources for every tenant in the zip.

These exports read by query rather than copying the store. They start two temporary docker compose projects: a read-only reader over the live store, with the same read gate and signer as `local create` (so they need docker and an AWS profile, not access keys), and a writer whose Loki and Tempo keep what is read. The reader's Tempo keeps every block it reads in the reader's memcached, as the local stack does, so fetching the many traces of one block reads that block from S3 once rather than once per trace. Metric blocks are copied and rewritten with thanos, with no network, to keep only the selected clusters' series. The zip holds the selected clusters' metrics, logs and traces, their annotation backups, and every report of each experiment they belong to, and nothing of any other cluster. Nothing in the bucket is written or deleted.

```
Exporting 2 clusters: lab-0f8fad5b-d9cb-469f-a165-70867728950e (tenant acme, found by querying the store), lab-7c9e6679-7425-40de-944b-e07fc1f90ae7 (tenant default, found by querying the store). Home tenant: acme
Exported metrics: 14 blocks, 38 MiB (39845888 bytes)
Exported logs: 120433 lines, 22 MiB (23068672 bytes)
Traces of lab-0f8fad5b-d9cb-469f-a165-70867728950e (acme): fetched 1000 of 2410
Traces of lab-0f8fad5b-d9cb-469f-a165-70867728950e (acme): fetched 2000 of 2410
Traces of lab-0f8fad5b-d9cb-469f-a165-70867728950e (acme): fetched 2410 of 2410
Exported traces: 2410 traces, 31 MiB (32505856 bytes)
Exported lab-0f8fad5b-d9cb-469f-a165-70867728950e, lab-7c9e6679-7425-40de-944b-e07fc1f90ae7 (tenants acme, default) to /Users/you/lab-pair.zip (5512 files, 61 MiB before compression).
Profiles are not included (issue #1016).
Open it with: unzip /Users/you/lab-pair.zip, then docker compose up in the directory it makes; see its README.txt.
The work directory /Users/you/lab-pair.zip.work-1f3e9a2c (412 MiB, 432013312 bytes) is kept; remove it yourself when you no longer need it.
```

A signal with nothing to export is reported as zero. Traces are found by search and then fetched one by one, which can take hours for a cluster with many traces, so the export reports each cluster's fetch progress every 1000 traces or every 30 seconds, whichever comes first, and once when all are fetched. The work directory `<path>.work-<id>/` holds the two projects, their state and the staged blocks. It is kept after the export, and after a failure, so you can look at what happened; nothing removes it. Before each temporary project is removed, its `docker compose logs` are saved as `reader/compose.log` and `writer/compose.log` there, and a failed export names them. A project that `docker compose down` cannot remove is reported with the command that removes it, `docker compose -p <project> down --remove-orphans`. The reader's components run as root, so on Linux you may need `sudo rm -rf` to remove it.

A cluster that no tenant holds fails and names the tenants it searched. An experiment with no clusters fails and names the experiment, the tenant and the query it ran. In both cases nothing is left at the path.

See [`observability local create`](../reference/commands.md#observability-local-create), [`observability local dashboards`](../reference/commands.md#observability-local-dashboards) and [`observability export`](../reference/commands.md#observability-export) for every option.
