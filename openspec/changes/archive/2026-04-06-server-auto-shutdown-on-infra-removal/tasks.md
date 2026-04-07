## 1. Events

- [x] 1.1 Add `Event.Server` sealed interface to `Event.kt` with `InfrastructureGone` data class (includes vpcId and a message field)

## 2. StatusCache Integration

- [x] 2.1 Add `autoShutdown: Boolean` and `vpcName: String?` parameters to `StatusCache`
- [x] 2.2 On each refresh, if `autoShutdown` is true and `vpcName` is non-null, call `findVpcByName()`
- [x] 2.3 If VPC is not found (null result), emit `Event.Server.InfrastructureGone` and call `exitProcess(0)`
- [x] 2.4 Handle AWS API exceptions by logging and continuing (no shutdown on exception)
- [x] 2.5 Skip the check with a logged warning if `vpcName` is null or blank

## 3. Server Command

- [x] 3.1 Add `--auto-shutdown` boolean flag to `Server.kt`
- [x] 3.2 Pass `autoShutdown` and the cluster VPC name through to `StatusCache`

## 4. Console Output

- [x] 4.1 Add a `ConsoleEventListener` handler for `Event.Server.InfrastructureGone` that prints a message before exit

## 5. Tests

- [x] 5.1 Unit test `StatusCache`: verify it emits `InfrastructureGone` and calls `exitProcess` when VPC not found
- [x] 5.2 Unit test: verify no shutdown when `findVpcByName` throws an exception
- [x] 5.3 Unit test: verify check is skipped when `vpcName` is null or blank

## 6. Documentation

- [x] 6.1 Update `docs/` server reference page to document the `--auto-shutdown` option
