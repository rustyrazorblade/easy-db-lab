## 1. Dependencies

- [x] 1.1 Add `awssdk-sso = { module = "software.amazon.awssdk:sso", version.ref = "awssdk" }` to the `[libraries]` section of `gradle/libs.versions.toml` (alongside the other `awssdk-*` entries).
- [x] 1.2 Add `awssdk-ssooidc = { module = "software.amazon.awssdk:ssooidc", version.ref = "awssdk" }` to the same section.
- [x] 1.3 Append `"awssdk-sso"` and `"awssdk-ssooidc"` to the `awssdk` bundle list in `[bundles]`.

## 2. Packer / AMI build credential resolution

- [x] 2a.1 Change `providers/aws/AWSCredentialsManager.kt` to resolve credentials via the injected `AwsCredentialsProvider` and write the resolved access key, secret, and (when the credentials are `AwsSessionCredentials`) a `aws_session_token` line. Always (re)write the file so expired/temporary credentials are refreshed each build.
- [x] 2a.2 Update `containers/Packer.kt` to inject `AwsCredentialsProvider` and pass it into `AWSCredentialsManager` (dropped the now-unused `User` injection).
- [x] 2a.3 Add a unit test verifying the written credentials file includes `aws_session_token` for session credentials and omits it for basic credentials (use real `StaticCredentialsProvider` with `AwsSessionCredentials` / `AwsBasicCredentials`; no mock-echo). — Both cases pass.
- [x] 2a.4 Run `./gradlew ktlintFormat` then `ktlintCheck`/`detekt` on the changed Kotlin. — Clean, no regressions in the changed files.

## 2b. Setup robustness (bugs surfaced during SSO setup)

- [x] 2b.1 Add `RetryUtil.createS3BucketPolicyRetryConfig()` + `withS3BucketPolicyRetry()` that retry on the S3 "Invalid principal"/`MalformedPolicy` error and 5xx, but not on 403.
- [x] 2b.2 Wrap `AWS.putS3BucketPolicy`'s `putBucketPolicy` call in the retry, and replace the raw `S3Exception` on terminal propagation failure with a clear message advising a re-run.
- [x] 2b.3 Add `RetryUtilTest` verifying the predicate retries the invalid-principal case and does not retry 403.
- [x] 2b.4 Reword the setup IAM-policy prompt so it explains when it is useful (requesting access from an admin) and notes `show-iam-policies` is available anytime.

## 2c. Packer SSH scoped to developer IP (CloudCustodian revokes 0.0.0.0/0)

- [x] 2c.1 Add a shared `ExternalIpService` (ipify) interface + default impl; register in `ServicesModule`; add a network-free fake to `TestModules`.
- [x] 2c.2 Change `InfrastructureConfig.forPacker` to take an `sshCidr` instead of hardcoding `0.0.0.0/0`.
- [x] 2c.3 Thread the CIDR through `AwsInfrastructureService.ensurePackerInfrastructure(sshPort, sshCidr)` and its callers (`BuildBaseImage`, `BuildCassandraImage`, `SetupProfile.ensurePackerVpc`), passing the developer's `/32`.
- [x] 2c.4 Refactor `Up` to resolve its external IP via the shared `ExternalIpService` (remove its private duplicate).
- [x] 2c.5 Update `InfrastructureConfigTest` (forPacker takes a CIDR) and `SetupProfileTest` (verify the two-arg call). Compile + targeted tests + ktlint/detekt clean.

## 2d. AMI build resilience and S3 caching (base + cassandra)

- [x] 2d.1 Bump the base image root volume 16→40 GiB (4 JDKs + their -dbg symbols overflow 16 GiB) and bump cassandra image 16→40 GiB (base + multiple staged C* versions).
- [x] 2d.2 Give both Packer build instances `iam_instance_profile = EasyDBLabEC2Role` (bucket policy already grants it s3:*), and pass `-var s3_bucket=<User.s3Bucket>` from `Packer.kt`; declare the `s3_bucket` variable in both HCLs.
- [x] 2d.3 Add a shared cache library (`edl-cache-lib.sh`) providing `cached_fetch` + apt-archive restore/save; restore the apt cache early (after AWS CLI) and save it at the end of the base build. Cassandra reuses the lib baked into the base AMI and restores/saves via inline steps.
- [x] 2d.4 Route the large/pinned direct downloads through `cached_fetch` (S3 download cache, version-keyed): base — async-profiler, k3s binary + airgap images, k9s, cilium CLI, OTel agent; cassandra — versioned C* dist tarballs, MAAC, pyroscope. Each falls back to a direct download when the cache lib/bucket is absent (keeps the local packer Docker tests working). Floating "latest"/nightly artifacts (cassandra-easy-stress, C* nightly/trunk) intentionally left uncached.
- [x] 2d.5 Replace the inline OpenJDK apt block with `install_jdks.sh` (served from the restored apt cache). Syntax-check all scripts; `:compileKotlin` clean.
- [x] 2d.6 Build AMI instances with the user's own AWS keypair (mount `secret.pem`, pass `ssh_keypair_name`/`ssh_private_key_file`) so a kept instance is SSH-able with their key.
- [x] 2d.7 Add `build-image --keep-on-error` (packer `-on-error=abort`) to leave a failed instance running, and print debug guidance on failure (how to SSH in if kept, or to use `--keep-on-error` if not).

## 2. Verification

- [x] 2.1 Run `./gradlew dependencies --configuration runtimeClasspath` (or `./gradlew build`) and confirm `software.amazon.awssdk:sso` and `software.amazon.awssdk:ssooidc` resolve onto the runtime classpath with no version conflicts. — Verified: both resolve to 2.42.33, no conflicts.
- [x] 2.2 Confirm the existing test suite still passes (`./gradlew check`), since no credential code changed. — Root project check (compile + test + ktlint + detekt) passed. `:spark:bulk-writer-*` modules skipped due to the local cassandra-analytics/JDK 17 precondition (unrelated to this change; those modules don't consume the awssdk bundle).
- [ ] 2.3 Manual SSO smoke test: configure an SSO-backed profile, run `aws sso login --profile <name>`, set that profile name via `setup-profile`, and confirm an AWS-touching command (e.g. listing key pairs / `up`) authenticates without static keys. Record the result in the change. — **Left for the user** (requires a real IdP browser login).

## 3. Documentation

- [x] 3.1 Add an "AWS SSO (IAM Identity Center)" section to `docs/getting-started/setup.md` describing: configuring an SSO profile in `~/.aws/config`, running `aws sso login --profile <name>`, entering that profile name during `setup-profile`, and that an active SSO session is required before running commands. Also corrected the prompt-order table (profile asked first; keys only if blank).
- [x] 3.2 Verify no other setup docs (e.g. credential prerequisites) contradict the new SSO flow; update cross-references if needed. — Updated `installation.md` AWS Requirements to list profile/SSO as a credential option; `commands.md` is generic and fine.

## Build resilience & optimization (implemented + verified in this change)

- [x] F.1 Capture-then-teardown for failed AMI builds: instead of relying on the manual
  `--keep-on-error` flag / `EASY_DB_LAB_BUILD_KEEP_ON_FAILURE` env var, default packer to
  `-on-error=abort` and have easy-db-lab itself, on build failure, SSH into the kept instance,
  pull the relevant diagnostic logs (`/tmp/jdk-install.log`, `/var/log/apt/term.log`, `dmesg`,
  `df -h`), surface them to the user, and then terminate the instance. This gives the user
  insight into every failure automatically and guarantees cleanup, removing the manual flag.
- [x] F.2 Quiet provisioner output across **every** packer step (base + cassandra), not just the
  JDK step. Heavy SSH output flood is what disrupts packer's connection and produces the spurious
  123; quieting the JDK step fixed it there, so make it the standard everywhere. Concretely:
  redirect each script's verbose output (apt, `tar` — drop `v`, downloads, builds, `ant`, etc.)
  to a per-step log file on the instance, print only brief progress lines to stdout, and surface
  the log tail on failure (via an `ERR` trap). Known-noisy steps to cover first: `install_cassandra.sh`
  (`tar zxvf` of multiple C* dists + ant builds), `install_bcc.sh`, `install_python.sh`, and the
  base inline blocks. Prefer a shared helper (e.g. a `run_quiet` wrapper in `edl-cache-lib.sh`) so
  every script quiets output consistently rather than each reinventing it.
- [x] F.3 Self-healing account S3 bucket (migrate without re-running setup-profile): have the
  build path (and `up`) idempotently ensure the account bucket exists and is saved to the user
  config — create + persist it if `s3Bucket` is empty — instead of only creating it in
  setup-profile. This lets existing profiles (empty `s3Bucket`, e.g. when setup-profile failed
  before saving it) activate the S3 cache automatically. Note: `Up.execute()` already calls
  `configureAccountS3Bucket()` / `reapplyS3Policy()`; factor out that ensure-or-create logic and
  reuse it from the AMI build path so `build-image` also benefits.
- [x] F.4 Route the remaining base direct downloads through `cached_fetch` and quiet them: yq and
  sjk.jar (currently inline `wget` in base.pkr.hcl, no `-q`, not cached) and async-profiler's
  `tar zxvf` (verbose). Consider helm (`get-helm-3` pipe) and uv (astral.sh pipe) — harder to
  cache, at least quiet them. (awscli is the cache bootstrap and can't cache itself.)
