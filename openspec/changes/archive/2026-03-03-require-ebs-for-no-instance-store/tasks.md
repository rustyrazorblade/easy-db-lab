## 1. Instance Store Detection

- [x] 1.1 Add a method to `EC2InstanceService` (or a new service) that calls `DescribeInstanceTypes` and returns whether the instance type has instance store
- [x] 1.2 Write a unit test for the instance store detection method using a mocked EC2 client

## 2. Storage Validation in InstanceSpecFactory

- [x] 2.1 Add instance store check to `DefaultInstanceSpecFactory.createInstanceSpecs()` — if the Cassandra instance type has no instance store and `ebsType` is `NONE`, throw an exception with a clear error message
- [x] 2.2 Update `InstanceSpecFactory` interface to accept the EC2 dependency (or accept instance store info as a parameter)
- [x] 2.3 Write unit tests for the three scenarios: instance store present (pass), no instance store + EBS (pass), no instance store + no EBS (fail)

## 3. Wiring and Integration

- [x] 3.1 Update Koin module registrations if the factory gains new dependencies
- [x] 3.2 Update any tests that use `DefaultInstanceSpecFactory` to account for the new dependency/parameter
- [x] 3.3 Add a domain-specific event for the storage validation error (following event bus patterns) — skipped: IllegalArgumentException with clear message is the standard pattern for validation errors that terminate the command

## 4. Documentation

- [x] 4.1 Update user-facing docs to mention the `--ebs.type` requirement for instance types without instance store
