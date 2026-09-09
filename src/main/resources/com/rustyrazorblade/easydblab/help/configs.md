---
name: configs
description: Edit the database configuration and apply it across the cluster
---
# Working with configuration

This topic explains how to change the database configuration on a running cluster. The workflow
is: generate a patch file, edit it, apply it to the nodes, and restart if needed.

These commands target the database (`cassandra`) node group. Run them from the cluster's
workspace directory.

## Step 1: Generate a configuration patch

```bash
easy-db-lab cassandra write-config
```

This writes a patch file into the workspace. A patch holds only the settings you want to override,
so you do not have to manage a full configuration file. Set the token count with `-t`:

```bash
easy-db-lab cassandra write-config -t 8
```

## Step 2: Edit the patch

Open the generated file in your editor and set the values you want to change. Keep the patch
small: only the keys you override belong in it.

## Step 3: Apply the patch to the nodes

```bash
easy-db-lab cassandra update-config
```

This pushes the merged configuration to every database node. Apply and restart in one step with
`--restart`:

```bash
easy-db-lab cassandra update-config --restart
```

To change only some nodes, filter with `--hosts`:

```bash
easy-db-lab cassandra update-config --hosts db0,db1
```

## Step 4: Restart to pick up the change

If you did not pass `--restart`, restart the database yourself so the new configuration takes
effect:

```bash
easy-db-lab cassandra restart
```

## Inspecting what is on a node

To pull the configuration files that are currently on the nodes back to your workspace:

```bash
easy-db-lab cassandra download-config
```

## Related topics

- `provisioning` — create the cluster these configs apply to.
- `stress-testing` — drive load once the configuration is in place.
