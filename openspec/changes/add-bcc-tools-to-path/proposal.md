## Why

BCC (BPF Compiler Collection) tools like `xfsslower`, `biolatency`, and `opensnoop` are installed to `/usr/share/bcc/tools/` on cluster nodes. When troubleshooting database performance, users must frequently run these tools with `sudo`. However, `sudo` uses the PATH from `/etc/environment` (not the login-shell PATH from `/etc/profile.d/`), and `/usr/share/bcc/tools/` is absent from `/etc/environment`. This forces users to type the full path every time:

```
sudo /usr/share/bcc/tools/xfsslower 0 -p $(cassandra-pid) | awk '$4 == "R" { print $0 }'
```

Adding `/usr/share/bcc/tools/` to `/etc/environment` lets users run bcc tools naturally:

```
sudo xfsslower 0 -p $(cassandra-pid) | awk '$4 == "R" { print $0 }'
```

## What Changes

- Add `/usr/share/bcc/tools` to the PATH in `packer/cassandra/environment` (which is installed to `/etc/environment` on database nodes).

## Capabilities

### Modified Capabilities

- `bcc-tools-path`: `/usr/share/bcc/tools` will be accessible to `sudo` sessions on database nodes, matching its current availability in interactive login shells.

### New Capabilities

_(none)_

## Impact

- **Database nodes**: `sudo <bcc-tool>` works without specifying the full path.
- **No behavior change for login shells**: `/etc/profile.d/aliases.sh` already includes the path; this change brings `sudo` sessions into parity.
- **Packer AMI rebuild required**: The change takes effect only after rebuilding and redeploying AMIs.
