## Overrides existing behavior

- The missing-state error names the absolute path; before, it was a `FileNotFoundException` for the relative `state.json`.
- `upload-keys` no longer leaves `authorized_keys_extra` in the current directory.
- Otherwise this change only adds a requirement.

## Conflicts with other in-flight changes

- `issue-1034` (PR #1078): both edit `ServicesModule.kt` on adjacent lines. The conflict is textual only. When #1040 binds `LocalClusterTooling`, it should take `get<Context>().workingDirectory`.
- `issue-746`: `EMRSparkService`'s binding moves to `AwsServicesModule` there. Whichever lands second carries the `logsDir` argument across.
- `issue-1037`: no overlap.
