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

Open <http://127.0.0.1:3000>. You are signed in as an admin. Grafana and the documents web server (<http://127.0.0.1:3080>, which serves experiment reports to the dashboards) are published on `127.0.0.1` only.

The first start takes a minute or two: Mimir loads the store's block index, Loki its log index, and Tempo lists every tenant's blocks. A cluster torn down a few minutes ago shows too: Tempo finds blocks by listing the bucket, not only from its tenant index.

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

## Change the dashboards

To load dashboards you are editing, without a rebuild or a restart, point `local dashboards` at a directory that holds one subdirectory per Grafana folder, each with dashboard JSON files:

```bash
easy-db-lab observability local dashboards ~/lab-local --from ~/work/dashboards
```

Without `--from`, it puts back the dashboards packaged with the tool, and says so:

```bash
easy-db-lab observability local dashboards ~/lab-local
```

The command writes the new tree beside the old one, swaps it into place, and asks Grafana to reload its dashboards. It never calls docker, so no container is recreated and nothing else changes. The stack must be running: when Grafana does not answer on the port the manifest records, the command fails and changes nothing.

A JSON file at the top of the `--from` directory, or nested more than one directory deep, has no Grafana folder. The command names every such file and changes nothing.

## Credentials

The stack reads the bucket as you, with the AWS profile your easy-db-lab profile names. The signer mounts your `~/.aws` read-only, so it can never rewrite your SSO cache.

A store the stack cannot read fails loudly, at three points:

- `local create` fails, writes nothing, and names the profile, the AWS error code and the fix:

  ```
  Cannot read the account bucket with profile edl: ExpiredToken
  Nothing was written. Log in and run the command again: aws sso login --profile edl
  ```

- `docker compose up` runs a one-shot `store-check` first. When it cannot read the bucket it exits non-zero with `cannot read the account bucket ... with profile edl: ...`, and no backend starts. See its message with `docker compose logs store-check`.
- While the stack runs, the signer checks the bucket every minute. Once your credentials expire, `docker compose ps` shows `store-signer` as `unhealthy`.

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
- **A tenant is missing from the pickers.** Run `local create` on the directory again.
- **`docker compose up` fails with `port is already allocated`.** Grafana uses port 3000 and the documents web server port 3080 on `127.0.0.1`. Stop whatever holds them, then run `docker compose up -d` again.

## Export a tenant

`observability export` copies a tenant's data into one zip that opens as this same stack on any machine, with no network, no AWS credentials and no cluster:

```bash
easy-db-lab observability export ~/acme-export.zip --tenant acme
```

The zip holds the tenant's metrics, logs and traces, its annotation backups and its reports, the stack (`compose.yml` and its configuration), the dashboards, and a `README.txt`. Profiles are not included ([#1016](https://github.com/rustyrazorblade/easy-db-lab/issues/1016)); the command and the README both say so. The command works from any directory, not only a cluster workspace.

The export reads the bucket with your AWS profile, like `local create`, and writes only the zip. Nothing in the bucket is written, changed or deleted, and nothing is uploaded.

```
Exporting tenant acme: 4210 objects, 812 MiB (851443712 bytes); 120511 MiB free
Exported tenant acme to /Users/you/acme-export.zip (4231 files, 812 MiB before compression).
Profiles are not included (issue #1016).
Open it with: unzip /Users/you/acme-export.zip, then docker compose up in the directory it makes; see its README.txt.
```

### What the export checks

- The path must be a new `.zip` file in a directory that exists. An existing file is never overwritten.
- The command lists everything it will copy, prints the size, and fails before copying anything when the disk the zip goes to does not have that much free space.
- A tenant with no metrics, logs or traces fails and names the prefixes it looked under.
- The zip is written under a temporary name beside the path (`<path>.partial-<random>`) and moved into place only when it is complete. When anything fails, no zip is left at the path, the temporary file is removed, and the error names what it was copying (metrics, logs, traces, annotations, reports) and the step.

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

The copied data is in `data/` and is mounted read-only into every component, so opening the export never changes it. Nothing compacts, merges or deletes. Tempo logs `failed to write tenant index ... read-only file system` each time it lists its blocks; that is expected and harmless. Grafana installs no plugin at start, since with no network the download would stop it, and no packaged dashboard needs one.

`observability local dashboards` works on an opened export, as on a live stack. `observability local create` refuses an opened export's directory, so it never turns the export into a live stack.

Exporting chosen clusters (`--cluster`) or one experiment (`--experiment`) is not available yet; export their tenant instead.

See [`observability local create`](../reference/commands.md#observability-local-create), [`observability local dashboards`](../reference/commands.md#observability-local-dashboards) and [`observability export`](../reference/commands.md#observability-export) for every option.
