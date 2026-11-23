## Context

- The profile is `~/.easy-db-lab/profiles/<name>/settings.yaml`; the name comes from
  `EASY_DB_LAB_PROFILE` (`Context.kt:51`), the root from `EASY_DB_LAB_USER_DIR` (`Constants.kt:457`).
- `User` (`configuration/User.kt:14-33`) is a flat Jackson class with AWS fields only; five fields
  have no default.  `UserConfigProvider` (`configuration/UserConfigProvider.kt`) loads, caches,
  redacts parse errors, and saves without an atomic write.  `isSetup()` is "file exists" (`:45`).
- `di/ContextModule.kt` binds `User` from `getUserConfig()`.  `AwsClientsModule.kt:54-67` builds the
  region and the credentials provider from it.
- `AwsS3BucketService.ensureAccountBucket(user)` (`:68-97`) is idempotent only because
  `createAccountBucket` sets `user.s3Bucket` on the shared singleton that `VersionSources` (`:68`) and
  `CassandraBuildCatalog` also hold.
- `SetupProfile` (`commands/profile/SetupProfile.kt`) collects and saves a `Map`; on a bad saved value
  it recovers through `loadExistingUser` on the raw map (`:93`).
- The multi-cloud plan (`plan.md` section 4) fixes the destination types: `aws`, `k8s`, `aws-eks`,
  `gcp`, `azure`.  This change ships `aws` only.

## Goals / Non-Goals

Goals: one profile, one destination, recorded in the file; a format the later destinations extend
without a second migration; a silent migration that leaves the earlier file untouched; remove `User`
and the Jackson reader; one account bucket per profile.

Non-goals: any destination other than AWS; the gate for unfinished destinations (it lands with the
first such destination, because nothing needs gating yet); recording a target in `state.json`;
changing the AWS prompts, validation or resource steps; CPU architecture and container images (the
plan's Phase 5f runs the observability stack and kits natively on arm64; Cassandra container images
and arm64 Cassandra belong to the later Cassandra-operator feature).

## Decisions

### D1. A new file

```yaml
email: someone@example.com
destination:
  type: aws
  location:
    region: us-west-2
  credentials:
    type: aws-profile        # or: access-key, with accessKeyId and secretAccessKey
    name: my-sso-profile
  keyName: easy-db-lab-...
  accountBucket: easy-db-lab-...
  sshTransport: direct
tailscale:                   # absent when the earlier file never set it
  clientId: ...
  clientSecret: ...
  tag: tag:easy-db-lab
axonOps:
  org: ...
  key: ...
```

- The file is `profiles/<name>/profile.yaml`.  `settings.yaml` is never written or deleted.  Builds
  from earlier releases (other agents' worktrees, a cluster already up) keep reading `settings.yaml`.
- kaml (already a dependency, `build.gradle.kts:236`) with property-style polymorphism on `type`.
  Unknown keys are ignored.  Written with `File.writeTextAtomically`, then mode 0600.
- `sshTransport` keeps `direct` and `ssm`; an unknown value fails the load and names the value and
  the choices.
- Alternative rejected: rewrite `settings.yaml` in place.  Builds on main fail on a file without a
  top-level `region`, and their `isSetup()` passes, so `down` from such a build would fail mid-run.
- Alternative rejected: keep the flat file and add `destination: aws`.  Later destinations would add
  flat fields that a reader could not tell apart.

### D2. The model

- `configuration/profile/`: `Profile(email, destination, tailscale, axonOps)`; `interface Destination`
  (open, `@Serializable` polymorphic) with `AwsDestination(location: AwsLocation, credentials:
  AwsCredentials, keyName, accountBucket, sshTransport)`; `AwsLocation(region)`; sealed
  `AwsCredentials` with `AwsProfile(name)` and `AccessKey(accessKeyId, secretAccessKey)` (sealed is
  fine here: both variants are AWS and stay together).
- `Destination` is open, not sealed, so that each later target module registers its own subclass in a
  `SerializersModule` (a sealed type cannot span Gradle modules).  Phase 1 has one registration,
  `AwsDestination`, in `awsProfileSerializers` (AWS package).  Phase 5 moves registration to each plugin.
- `Tailscale(clientId, clientSecret, tag)`, `AxonOps(org, key)`.  `enabled` is "both values
  non-blank", the same rule as `User.isTailscaleEnabled()` / `isAxonOpsEnabled()` today.
- `ProfileStore` is a concrete class, not a port.  It replaces `UserConfigProvider`.  Its constructor
  takes the profile directory, a `SerializersModule` and a `LegacyProfileReader` (an interface with
  `holdsCredentials(map)` and `migrate(map): Profile?`).  Root wires both from the AWS package, so
  `ProfileStore` names no AWS type and moves to the neutral module unchanged.
  - `isSetup()`: `profile.yaml` exists, or `settings.yaml` exists and `holdsCredentials` is true.
  - `load()`: decodes `profile.yaml`, or migrates the earlier file (D3).  No cross-process cache.
  - `loadForSetup()`: a flat key map that setup prefills from, for either file.  `profile.yaml` is
    flattened to the same keys as the earlier file (`region`, `awsProfile`, `keyName`, `sshTransport`,
    ...), so a bad saved value in either file reaches setup as a value it reports and replaces.
  - `update(change: (Profile) -> Profile)`: a locked read-modify-write.  It takes an exclusive lock on
    `profile.yaml.lock`, re-reads the file, applies the change, and writes atomically with mode 0600
    (the `ProxyEnvFile` pattern).  Two processes that save different fields keep both.
- **No snapshot bindings.**  Koin binds `ProfileStore` and `ProfileAccess` (`fun interface`, `current():
  Profile`, which calls `load()`).  The AWS Koin module binds `AwsDestinationAccess` (`current():
  AwsDestination`, a cast that fails naming the destination type; the cast lives in the AWS module).
  Neither `Profile` nor `AwsDestination` is bound as a value, so a reader never sees a stale field.
  Region and credentials, which do not change within a process, are read once by `AwsClientsModule`.

### D3. Migration

- `LegacyAwsProfileReader.migrate(map): Profile?` is a pure function over the raw YAML map; it
  implements `LegacyProfileReader`.
- It runs in `load()` when `profile.yaml` does not exist and `settings.yaml` does.  Mapping:
  - `region` to `location.region`; `keyName`, `sshTransport` copied; `s3Bucket` to `accountBucket`.
  - A non-blank `awsProfile` gives `AwsProfile`; otherwise non-blank `awsAccessKey` and `awsSecret`
    give `AccessKey`; neither gives `null` (not set up).
  - The Tailscale fields become a `tailscale` block when either the id or the secret is non-blank,
    keeping a blank partner; the same for AxonOps.  A half-set block stays disabled and setup prefills it.
  - `isTailscaleEnabled`, `isAxonOpsEnabled` (getter keys written by Jackson) and `sshKeyPath` are
    ignored.
- `load()` writes `profile.yaml` under the store's lock, then returns.  No output, no event; one debug
  log line.  A second process waiting on the lock finds `profile.yaml` and decodes it.
- The earlier file is read as a raw kaml `YamlMap`.  No Jackson.

### D4. Account bucket

- `AwsS3BucketService.ensureAccountBucket()` and `ensureMarkedAccountBucket()` take no profile
  argument.  They read `AwsDestinationAccess.current()`; when `accountBucket` is blank they create the
  bucket and `update { it with accountBucket }`.  `VersionSources` and `CassandraBuildCatalog` stop
  holding a profile value and call `ensureAccountBucket()`.
- `AwsKeyPairService` saves `keyName` the same way.
- Every other reader of the bucket or the key name reads `AwsDestinationAccess.current()` at the
  moment it runs: `Packer.kt:86` (`s3_bucket`) and `:162` (`keyName`), `AwsServicesModule.kt:188-189`
  (`AwsBuildSites` key name), `CompactorStatus.kt:29`, `ClusterProvisioningService.kt:84` and
  `SparkInit.kt:105`.  A new profile's setup creates the key and bucket, then runs the AMI build in
  the same process; the build sees both.

### D5. Setup

- `SetupProfile` works on `Profile` and `AwsDestination`; its phases and events are kept.
  `Event.Setup.DestinationSelected(destination: String)` is emitted when a new profile's destination
  is fixed; the console prints `Destination: AWS`.
- New profile with one available destination: no prompt.  Existing profile: review that destination
  only.  Choosing another destination means another profile name.
- `isFullySetUp` reads the typed profile: email, region, credentials, keyName, accountBucket.
- Setup prefills and checks the saved transport from `loadForSetup()`'s flat map (`SetupProfile.kt:93-96`
  reads flat keys), so a bad `sshTransport` in `profile.yaml` is reported and asked again, as in the
  earlier file.
- Text that names `settings.yaml` as the file to edit now names `profile.yaml`:
  `AwsKeyPairService.kt:101` ("Clear keyName in settings.yaml", asserted at
  `AwsKeyPairServiceTest:151`), `LazySshRoute.kt:9`, and the `ProfileShow` KDoc.

### D6. `profile show`

- Prints the profile name, directory and file (`profile.yaml`), `email`, `destination`, then
  `region`, `credentials` (`aws-profile <name>` or `access-key`, never the key or secret), `keyName`,
  `accountBucket`, `sshTransport`, then AxonOps and Tailscale as `ENABLED`/`DISABLED`.
- When only `settings.yaml` exists, `profile show` loads it like any command, which migrates it.

## Provider neutrality

- Neutral: `Profile`, `Destination`, `Tailscale`, `AxonOps`, the `destination`/`location` keys,
  `ProfileStore`, `ProfileAccess`, and the `LegacyProfileReader` interface.  `ProfileStore` names no
  AWS type: it gets the `SerializersModule` and the reader from its constructor.
- AWS: `AwsDestination`, `AwsLocation`, `AwsCredentials`, `AwsDestinationAccess`,
  `awsProfileSerializers`, and `LegacyAwsProfileReader` (the D3 migration, the `awsProfile`,
  `awsAccessKey` and `awsSecret` keys).  They live in an AWS package now and move to `:target-aws` in
  plan Phase 3 with no change to `ProfileStore`.  Root's composition wires them.
- No AWS SDK type enters the profile model; `PackageBoundaryTest` is unchanged.

## Risks / Trade-offs

- After the migration, a write by an older build (a new key pair or bucket) goes to `settings.yaml`
  and new builds do not see it.  Accepted: it needs an old build to create account resources after a
  new build migrated the profile; re-running `profile setup` on the new build repairs it.
- About 150 call sites change.  The change is mechanical; the renames are listed in tasks.md.

## Test plan

Unit tier (`src/test`, CI).  Each test has a control: break the named check, see it fail, revert.
Setup and store tests use a real `ProfileStore` on `@TempDir` and assert on the decoded file, never a
`verify(...)` of a saved object (that replaces the mock-echo at `SetupProfileTest.kt:410,422`).

- `ProfileStoreTest.round trip keeps every field` (both credential forms, with and without the blocks).
  Control: drop `accountBucket` from the save.
- `ProfileStoreTest.save writes owner-only permissions`.  Control: remove the chmod.
- `ProfileStoreTest.unknown keys are ignored`.  Control: `ignoreUnknownKeys = false`.
- `ProfileStoreTest.unknown destination type fails naming the type`.  Control: default to AWS.
- `ProfileStoreTest.unknown ssh transport fails naming value and choices`.  Control: default it.
- `ProfileStoreTest.parse error message holds no file text` (a secret in a broken file).  Control:
  include the decoder message.
- `LegacyAwsProfileReaderTest`: `aws profile name maps to AwsProfile`, `access key maps to AccessKey`,
  `profile name wins over keys`, `no credentials returns null`, `half tailscale pair is kept and
  disabled`, `half axonops pair is kept and disabled`, `isTailscaleEnabled true does not enable
  tailscale`, `s3Bucket becomes accountBucket`.  Controls: swap the precedence; drop a half pair; read
  the getter key; skip the rename.  Fixtures are written by main's `UserConfigProvider.saveUserConfig`
  from `User` fixtures (generated once on main and checked in), not by hand.
- `ProfileStoreTest.earlier file is converted into profile yaml and left byte-identical`.  Control:
  write the result to `settings.yaml`.
- `ProfileStoreTest.migration prints nothing` (captures stdout and the event bus).  Control: add a print.
- `ProfileStoreTest.earlier file without credentials is not set up`.  Control: `isSetup` = exists.
- `ProfileStoreTest.earlier file with a bad transport is set up, fails load naming the value, and
  loadForSetup returns its map`.  Control: fail `isSetup` instead.
- `ProfileStoreTest.concurrent first loads agree` (two threads, one earlier file; both results equal
  the migrated profile and the file parses).  Control: write without the lock and the atomic rename.
- `ProfileStoreTest.bad transport in profile yaml fails load and loadForSetup returns flat keys`
  (the fixture is a generated `profile.yaml` with the transport edited by hand).  Control: return the
  nested map.
- `ProfileStoreTest.two stores saving different fields keep both` (two `ProfileStore` instances on one
  directory, as two processes would be; one updates `keyName`, the other `accountBucket` from a stale
  read).  Control: write without re-reading under the lock.
- `PackageBoundaryTest` gains a rule: classes in `configuration.profile` outside its AWS package
  depend on no class in that AWS package.  Control: import `AwsDestination` in `ProfileStore`.
- `CommandExecutorTest.profile required command runs setup for an earlier file without credentials`
  (real store on `@TempDir`).  Control: `isSetup` = exists.
- `AwsS3BucketServiceTest.two ensures with one stale profile create one bucket`.  Control: read the
  bucket from an injected `AwsDestination`.
- `PackerTest.build command reads the bucket and key saved after the packer was created` (real store;
  the `Packer` is resolved first, then the bucket and key are saved, then `buildCommand` runs).
  Control: capture the destination in the constructor.
- `AwsKeyPairServiceTest` (updated): the missing-key message names `profile.yaml`.  Control: the old text.
- `SetupProfileTest.new profile with one destination does not ask for it and emits
  DestinationSelected`.  Control: prompt anyway.
- `SetupProfileTest.existing profile is reviewed without a destination prompt`.  Control: prompt.
- `SetupProfileTest.interrupted earlier profile prefills setup`.  Control: prefill from an empty map.
- `ProfileShowTest` (updated): destination and fields shown; no secret appears for either credential
  form; flags follow the block rules.  Control: print the access key id.
- `AwsClientsModuleTest.credentials provider follows the credential form`.  Control: always static.

Integration tier (CI): `SetupProfileIntegrationTest` (LocalStack) drops its `UserConfigProvider` mock
(lines 51, 86), uses a real `ProfileStore` on `@TempDir`, and asserts that bucket creation writes
`accountBucket` to `profile.yaml`.  Control: skip the save.  Two more LocalStack cases:
- key pair creation writes `keyName` to the decoded `profile.yaml`.  Control: skip the key save.
- a new profile's setup with the AMI step replaced by a recording build-image step (the only fake):
  the recorded build sees the bucket and key that setup created in the same run, with the AWS clients
  resolved before the bucket existed.  Control: bind `AwsDestination` as a snapshot value.

Real AWS (before ready, `requires-aws`).  Copy `~/.easy-db-lab` to a temporary directory and run the
branch build with `EASY_DB_LAB_USER_DIR` set to it.  This is extra isolation; the safety comes from D1
(the earlier file is never written).  The default profile name is used; no profile is created.
- `profile show`: `profile.yaml` appears, values match `settings.yaml`.
- `profile setup` on the review path: STS validation and marker tags run; the saved file keeps every value.
- 3-node `init`/`up`/`down` with `embark_offhours=off`.
- `settings.yaml` in the copy is byte-identical before and after (`cmp`).
- Then main's build runs `profile show` on the same copy: it succeeds and its values match the
  branch's `profile show`, which proves earlier builds keep working.
- The PR body lists each control above with the failing test name and assertion line it produced.
