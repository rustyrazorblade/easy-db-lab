## Why

Local developer settings like `AWS_PROFILE` and `SIDECAR_IMAGE` are currently hardcoded in `bin/end-to-end-test` or must be set as shell exports before every run. There is no single, gitignored file where a developer can store their personal overrides — leading to accidental commits of profile names and no clear place to document which variables are available.

## What Changes

- Add a `.env` file (gitignored) that both `bin/easy-db-lab` and `bin/end-to-end-test` source at startup if it exists.
- Remove the hardcoded `AWS_PROFILE=sandbox-admin` from `bin/end-to-end-test`; developers set it in `.env` instead.
- Add a `.env.example` (committed) documenting all supported variables with safe placeholder values.
- Update the local development section of the docs to explain the `.env` pattern and list available variables.

## Capabilities

### New Capabilities

- `env-file-config`: Source a gitignored `.env` file at project root in `bin/easy-db-lab` and `bin/end-to-end-test` before any other logic, allowing per-developer overrides of `AWS_PROFILE`, `SIDECAR_IMAGE`, and other runtime variables without modifying committed scripts.

### Modified Capabilities

- `end-to-end-testing`: Remove hardcoded `AWS_PROFILE=sandbox-admin`; the value must now come from environment or `.env`.

## Impact

- `bin/easy-db-lab` — add `.env` sourcing near the top
- `bin/end-to-end-test` — add `.env` sourcing; remove hardcoded `AWS_PROFILE=sandbox-admin`
- `.env.example` — new committed file documenting available variables
- `.gitignore` — add `.env` entry
- `docs/development/` — update local development guide with `.env` setup instructions
