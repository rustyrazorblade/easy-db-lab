# Overrides for issue-1022

## Overrides existing behavior

### kit-cross-targeting: kit-ref arg type for cross-kit targeting

**Currently:** "A kit arg with `type: kit-ref` SHALL declare a dependency on a running kit's SQL endpoints. The `capability` field is advisory metadata documenting intent; the `variable` field names the env var that stores the target kit name (conventionally `TARGET`)."  Scenarios: "kit-ref arg declared in kit.yaml" and "Unknown arg types are ignored gracefully".

**This change:** A kit-ref arg declares a dependency on another installed kit.  `capability` is enforced at install (the code already does this; the spec stops calling it advisory), with the message `Target kit '<target>' does not have the '<capability>' capability required by <kit>`.  A new `endpoints` list (for example `[native, http]`, typed as `KitEndpoint.EndpointType`, so an unknown type fails at parse) names endpoint types the target must declare; install fails with one message that names every missing type ("the 'native' endpoint", or "the 'native' and 'http' endpoints").  The two fields are independent; with neither set there is no target check.  A target that is not installed keeps the "is not installed" message.  The checks live in a pure `KitRefTargetValidator` and run before any file is written.  The two existing scenarios are kept, and eight scenarios are added.

### kit-cross-targeting: TARGET_* env vars injected at start time

**Currently:** "When a bench kit with a `kit-ref` arg is started, the framework SHALL read the target kit's `kit.yaml` from its directory in the workspace and inject `TARGET_*` environment variables derived from each declared endpoint into the start script's environment."  Scenarios cover JDBC, PostgreSQL, MySQL, multiple types, and a missing target directory.  In code, a `native` endpoint yields no variables (`else -> emptyMap()`).

**This change:** The variables are injected for any phase.  `KitEndpoint.toTargetVars` is an exhaustive `when`.  A `native` endpoint yields `TARGET_NATIVE_HOST`, `TARGET_NATIVE_PORT`, `TARGET_NATIVE_USER` (from the `sql` capability user, empty with none) and `TARGET_NATIVE_DATABASE`.  `https` and `cql` yield no variables.  The existing scenarios are kept, and three scenarios are added (native vars, native with no `sql` capability, HTTPS and CQL give none).  Side effect: neo4j and memcached native endpoints now yield `TARGET_NATIVE_*`.

### kit-command-args: Kit command args declaration

**Currently:** "A kit SHALL be able to declare named CLI options scoped to individual commands via a `commands:` map in `kit.yaml`. Each entry maps a command name to a description and list of arg specs using the same `KitArgSpec` structure already used for install-time args."  Scenarios: "Command with args appears in help", "Arg value passed to script as env var", "Default value used when flag omitted", "Runtime arg overrides install-time resolved arg".  In code, an unset arg with no default reaches the script as the string `null`, and a boolean arg cannot take a value (`--flag false` fails).

**This change:** Every declared arg reaches the script.  An unset arg with no default is recorded as an empty string, never `null`, and overwrites an exported shell variable of the same name.  A `boolean` arg takes arity `0..1`: a bare `--flag` means `true`, and `--flag true`, `--flag=true`, `--flag false` and `--flag=false` set the stated value; help shows `--flag[=<value>]`.  The four existing scenarios are kept, and four scenarios are added.

### kit-install-command (ADDED requirements that change existing behaviour)

**Currently:** No requirement covers install args with no value or boolean install args.  In code, the install factory skips an unset arg, so it is absent from `resolved-args.env`, and `kit install clickhouse --s3-cache-on-write false` fails, though `docs/user-guide/clickhouse.md` documents it.

**This change:** "Install args with no value are recorded as empty strings" (filled after the default loop, so an unresolved `${VAR}` default is kept) and "Boolean install args accept an optional value" (arity `0..1`).

### clickhouse (ADDED requirement that changes existing behaviour)

**Currently:** The `clickhouse` kit's `Native` endpoint declares no `database`.

**This change:** "Native endpoint declares the default database": `database: "default"`; `formatUrl` is unchanged for `native`.

## Conflicts with other in-flight changes

Checked: issue-1000, issue-1018, issue-966, issue-970, issue-971, issue-992, ssm-packer-builds, ssm-ssh-transport.

- No other in-flight change touches `kit-cross-targeting`, `kit-command-args`, `kit-install-command` or `clickhouse`, or adds `clickhouse-benchmark-kit`, `kit-shell-library` or `sysbench-app-node-placement`.  No requirement is modified incompatibly.
- issue-1000 overlaps without conflict.  Its code is merged (PR 1005) but its change is not archived.
  - `networking`: "Workspace tool wrappers" lists the contents of `<workspace>/bin/` and the names the foreign-file check covers, and says writing "MUST replace a wrapper whose content changed".  This change adds `edl-kit-lib.sh` to that directory and to the foreign-file check, written once and never rewritten, in its own requirement in `kit-shell-library`.  The library is not a wrapper, so the replace rule does not apply to it, but the networking requirement's list of `bin/` contents will not name the library.  Whichever change is archived second should add `edl-kit-lib.sh` to that list.
  - `workload-runner`: "Workload scripts are executed with cluster state as environment variables" holds a table of injected variables.  This change adds `EDL_KIT_LIB` for every kit process (in `kit-shell-library`).  The table will not list it; whichever change is archived second should add it.
  - `typed-install-steps` and `kit-lifecycle-hooks`: shell steps and hooks get the tool wrappers and an absolute kubeconfig.  This change adds `EDL_KIT_LIB` to the same processes through `KitProcessEnvironment`.  Additive; no conflict.
- issue-971 modifies `tool-execution`.  This change does not modify `tool-execution` (that capability covers `exec run` and journald, not the workspace `bin/`), so there is no conflict.
- issue-1018 and ssm-ssh-transport modify `networking`, which this change does not modify.
- issue-966, issue-970, issue-992 and ssm-packer-builds touch unrelated capabilities.
