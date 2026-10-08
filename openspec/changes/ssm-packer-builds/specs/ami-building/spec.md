## ADDED Requirements

### Requirement: AMI builds honor the SSH transport

AMI builds MUST reach the temporary builder instance using the profile's SSH transport. Under
`direct`, Packer SHALL connect to the builder instance's public IP as before. Under `ssm`, Packer
SHALL connect through an SSM Session Manager session, so the build needs no inbound port-22
reachability from the operator's network.

The Session Manager plugin Packer needs under `ssm` MUST be provided by the tool's own Packer
container image, built locally from a definition packaged with the distribution. The operator
SHALL NOT need to install anything beyond what the `ssm` transport already requires.

#### Scenario: Direct transport builds are unchanged

- **GIVEN** a profile whose SSH transport is `direct`
- **WHEN** the user builds an AMI
- **THEN** Packer runs in the stock Packer image and connects to the builder instance's public IP

#### Scenario: SSM transport builds go through Session Manager

- **GIVEN** a profile whose SSH transport is `ssm`
- **WHEN** the user builds an AMI
- **THEN** Packer connects to the builder instance through an SSM Session Manager session
- **AND** Packer runs in an image that includes the Session Manager plugin

#### Scenario: The SSM-capable Packer image is built once and reused

- **GIVEN** a profile whose SSH transport is `ssm`
- **WHEN** the user builds an AMI and the SSM-capable Packer image for the current image definition already exists locally
- **THEN** the existing image is reused without rebuilding it

#### Scenario: A changed image definition produces a new image

- **GIVEN** an SSM-capable Packer image built from an earlier image definition
- **WHEN** the image definition changes and the user builds an AMI under `ssm`
- **THEN** a new image is built for the new definition

#### Scenario: An unusable plugin fails before the build starts

- **GIVEN** a profile whose SSH transport is `ssm`
- **WHEN** the SSM-capable Packer image is built and its Session Manager plugin cannot run
- **THEN** the image build fails with the plugin's error before any builder instance is launched
