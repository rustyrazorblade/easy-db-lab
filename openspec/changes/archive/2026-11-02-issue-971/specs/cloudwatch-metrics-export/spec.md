## ADDED Requirements

### Requirement: Each cluster's YACE discovers only its own resources

Every cluster runs its own YACE and labels what it finds with its own cluster.  The YACE EC2, EBS and OpenSearch jobs SHALL discover resources by the tag `ClusterId=<this cluster's id>` as well as `easy_cass_lab=1`.  The S3 job SHALL discover by `easy_cass_lab=1` alone, because the account bucket is shared and carries no `ClusterId`.  The volumes that `up` launches with an instance SHALL carry the instance's tags at launch, so the EBS job finds them by `ClusterId`.

#### Scenario: The instance, volume and OpenSearch jobs search on ClusterId

- **WHEN** the YACE config is rendered for a cluster
- **THEN** the `AWS/EC2`, `AWS/EBS` and `AWS/ES` jobs search on `easy_cass_lab=1` and `ClusterId=<this cluster's id>`
- **AND** the `AWS/S3` job searches on `easy_cass_lab=1` alone

#### Scenario: Two clusters run at once

- **WHEN** two clusters run at once in one account
- **THEN** each cluster's YACE reports only its own instances, volumes and OpenSearch domain

#### Scenario: Volumes are tagged at launch

- **WHEN** `up` launches an instance
- **THEN** the launch request tags the instance and its volumes with the same tags, `ClusterId` included

### Requirement: S3 panels count the shared account bucket once

Every running cluster's YACE reports the shared account bucket, labelled with its own cluster.  Every dashboard panel query on the `aws_s3_` metrics SHALL collapse these copies with `max by (...)` over the bucket's own labels, `dimension_BucketName` included and `cluster` excluded, so that each bucket counts once.  A unit test SHALL fail any such query that is not collapsed this way, that keeps `cluster`, or that drops `dimension_BucketName`, and SHALL name the file and the query.

#### Scenario: All clusters count the bucket once

- **WHEN** two clusters run and an operator sets `cluster` to All on an S3 panel
- **THEN** the panel shows the account bucket once, not once per cluster

#### Scenario: One cluster shows its own values

- **WHEN** an operator selects one cluster on an S3 panel
- **THEN** the panel shows that cluster's values for the bucket

#### Scenario: An S3 query that keeps cluster fails the unit test

- **WHEN** a dashboard has an `aws_s3_` query that is not collapsed with `max by (...)`, keeps `cluster`, or drops `dimension_BucketName`
- **THEN** the unit test fails and names the file and the query
