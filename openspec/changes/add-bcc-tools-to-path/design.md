## Context

On cluster nodes there are two sources of PATH configuration:

1. **`/etc/profile.d/aliases.sh`** — sourced for interactive login shells. Installed from `packer/base/aliases.sh`. Already includes `/usr/share/bcc/tools`.
2. **`/etc/environment`** — read by PAM at session start, including `sudo` sessions. Installed from `packer/cassandra/environment`. Does **not** include `/usr/share/bcc/tools`.

When a user runs `sudo xfsslower ...`, `sudo` inherits the environment from `/etc/environment` (unless `--preserve-env` is used). Because `/usr/share/bcc/tools` is absent from that file, the tool is not found.

## Goals / Non-Goals

**Goals:**
- Make bcc tools (e.g., `xfsslower`, `biolatency`, `biosnoop`) usable via `sudo` without specifying the full path.
- Keep the change minimal and targeted.

**Non-Goals:**
- Modifying `sudoers` or `sudo` configuration.
- Changes to control or stress node PATH (bcc tools are only needed on database nodes).
- Altering `/etc/profile.d/aliases.sh` (already correct).

## Decisions

### 1. Add `/usr/share/bcc/tools` to `packer/cassandra/environment`

**Rationale:** This file is installed to `/etc/environment`, which is the correct place for system-wide environment variables that apply to all sessions including `sudo`. It already controls the PATH on database nodes. Adding the bcc tools path here is consistent with how other tool directories (async-profiler, cassandra-sidecar) are added.

**Alternative considered:** Modifying `/etc/sudoers` to use `secure_path` that includes bcc tools. Rejected because it's more invasive and `/etc/environment` is the established pattern in this project.

### 2. Append, don't replace

The new path is inserted at the beginning of the PATH string (before `/usr/local/sbin`) so it takes precedence, but all existing paths remain unchanged. This matches the intent: bcc tools are the primary reason for this change.

## Risks / Trade-offs

- **AMI rebuild required**: The change only takes effect after a Packer rebuild. Existing running clusters are unaffected until they are recreated.
- **No naming conflicts**: `/usr/share/bcc/tools` contains purpose-built eBPF tools; the risk of PATH shadowing common system commands is negligible.
