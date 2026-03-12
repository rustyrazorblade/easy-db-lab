## ADDED Requirements

### Requirement: S3 JAR validation before job submission

When a user provides an S3 path for the `--jar` option, the system SHALL verify the JAR exists in S3 before submitting the EMR step. If the JAR does not exist, the system SHALL fail immediately with a clear error message.

#### Scenario: S3 jar exists

- **WHEN** the user runs `spark submit --jar s3://bucket/path/app.jar --main-class com.example.Main`
- **AND** the jar file exists at `s3://bucket/path/app.jar`
- **THEN** the system SHALL proceed with job submission normally

#### Scenario: S3 jar does not exist

- **WHEN** the user runs `spark submit --jar s3://bucket/path/nonexistent.jar --main-class com.example.Main`
- **AND** no object exists at `s3://bucket/path/nonexistent.jar`
- **THEN** the system SHALL fail before submitting the EMR step
- **AND** the error message SHALL include the S3 path that was not found

#### Scenario: Local jar path is not affected

- **WHEN** the user runs `spark submit --jar /local/path/app.jar --main-class com.example.Main`
- **THEN** the existing local file validation SHALL apply (file exists, .jar extension)
- **AND** no S3 existence check SHALL be performed before upload
