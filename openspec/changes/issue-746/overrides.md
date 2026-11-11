## Overrides existing behavior

- An STS, IAM or S3 403 caused by bad credentials was reported as a permissions error by the dead `Main.kt` handler and by `AWSResourceSetupService`. It is now a credentials error.
- `spark status` shows `UNKNOWN` instead of `"null"` for an EMR state the SDK does not know.
- Profile setup's credential check showed permission guidance for any non-STS service error, including a 5xx or throttling. It now shows permission guidance only for a permission-denied failure, credentials guidance for a credentials failure, and `CredentialValidationFailed` for everything else.
- A failure from a command that is not a `PicoCommand`, reaching `CommandLineParser`'s handler, no longer prints a raw stack trace; it is reported as one event and the trace goes to `info.log`. The same holds for a failure in top-level dispatch outside the lifecycle (state restore, resource cleanup).
- The old `Main` prefixes ("Invalid argument", "Invalid state") and the `RemoteException` "try rerunning" hint are dropped on purpose: the cause chain names the exception type and message.
- The credentials remedy no longer prints a `<profile>` placeholder. The handler takes no dependencies, so it cannot know the profile; the text tells an SSO user to run `aws sso login` and to add `--profile` for a non-default profile.
- Otherwise this change only adds requirements.

## Conflicts with other in-flight changes

- `issue-1034` (ClusterTooling, unmerged branch) touches `services/` and `ServicesModule.kt`, but not the AWS bindings or `EcrPullSecretService`'s binding line. A textual merge conflict in `ServicesModule.kt` is possible; there is no behavioural conflict.
- `issue-1038` (Cassandra kit, spec only): no overlap.
