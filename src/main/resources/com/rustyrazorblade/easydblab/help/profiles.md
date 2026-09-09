---
name: profiles
description: Configure and inspect AWS cluster profiles
---
# Profiles

A profile bundles AWS region, credentials, instance preferences, and local working directory. One active profile per session.

Setup (once):
1. `easy-db-lab profile setup` — interactive prompt: name, region, AWS credentials source. Writes profile dir under `~/.easy-db-lab/profiles/<name>/`.
2. Configure: profile dir holds install templates, kit scaffolds, custom dashboards. Classpath templates serve as defaults when profile has none.

Inspect:
- `easy-db-lab profile show` — active profile name, directory path, AWS region, credentials source.

Notes:
- First run: `profile setup` or most commands prompt to set one up.
- Only one profile per invocation. Switch by setting `$EASY_DB_LAB_PROFILE` before running a command (defaults to `default`).

Related: `provisioning`, `kits`.
