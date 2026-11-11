## ADDED Requirements

### Requirement: Instance role supports SSM Session Manager

The cluster instance role (`EasyDBLabEC2Role`) MUST carry an inline Session Manager policy
granting `ssm:UpdateInstanceInformation`, `ssmmessages:CreateControlChannel`,
`ssmmessages:CreateDataChannel`, `ssmmessages:OpenControlChannel` and
`ssmmessages:OpenDataChannel`, regardless of the profile's SSH transport, so that any cluster node
or AMI builder can be reached over SSM Session Manager. The role MUST NOT be given the managed
`AmazonSSMManagedInstanceCore` policy, which also grants Parameter Store reads on every parameter.

#### Scenario: New role receives the SSM policy

- **GIVEN** a profile whose instance role does not yet exist
- **WHEN** setup creates the role
- **THEN** the Session Manager inline policy is put on it

#### Scenario: Existing role receives the SSM policy on up

- **GIVEN** an instance role created before this policy was required
- **WHEN** the user runs `up`
- **THEN** the Session Manager inline policy is put on the role before instances are launched
- **AND** putting it again when already present succeeds

#### Scenario: Existing role receives the SSM policy before an AMI build

- **GIVEN** an instance role that is otherwise valid but lacks the Session Manager inline policy
- **WHEN** the user runs `build-base` or `build-cassandra`
- **THEN** the Session Manager inline policy is put on the role before the builder instance launches

### Requirement: Profile setup offers the SSH transport

The profile setup workflow MUST let the user choose the SSH transport (`direct` or `ssm`), default
`direct`, and the profile display MUST show the configured transport.

#### Scenario: User selects SSM during setup

- **GIVEN** a user running profile setup
- **WHEN** they answer `ssm` to the SSH transport prompt
- **THEN** the profile's SSH transport is saved as `ssm`

#### Scenario: Unrecognized transport is rejected

- **GIVEN** a user running profile setup
- **WHEN** they answer the SSH transport prompt with a value other than `direct` or `ssm`
- **THEN** the value is rejected and the user is asked again

#### Scenario: Unrecognized saved transport

- **GIVEN** a profile file whose saved SSH transport is neither `direct` nor `ssm`
- **WHEN** any command loads the profile
- **THEN** loading fails with an error naming the bad value and the valid choices
- **AND** profile setup reports the bad value, keeps the profile's other values, and asks for the transport again

#### Scenario: Profile display shows the transport

- **GIVEN** a configured profile
- **WHEN** the user displays the profile
- **THEN** the SSH transport is shown

## MODIFIED Requirements

### Requirement: IAM Policy Visibility

The system MUST allow users to view the IAM policies required for operation. The displayed
policies MUST include the permissions needed to open SSM Session Manager sessions to cluster
instances.

#### Scenario: User views required IAM policies

- **GIVEN** a user troubleshooting permissions
- **WHEN** they request IAM policy display
- **THEN** the required policies are shown with account-specific values substituted.

#### Scenario: Displayed policies cover SSM sessions

- **GIVEN** a user requesting IAM policies from an administrator
- **WHEN** they display the required policies
- **THEN** the policies grant starting SSM sessions, using the SSH and port-forwarding session documents, only to the account's instances tagged `easy_cass_lab=1`
- **AND** they grant terminating and resuming only the user's own sessions
