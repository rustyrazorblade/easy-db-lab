## 1. Gitignore

- [x] 1.1 Add `.env` to `.gitignore`

## 2. .env.example

- [x] 2.1 Create `.env.example` at project root documenting `AWS_PROFILE`, `SIDECAR_IMAGE`, `EASY_DB_LAB_INSTANCE_TYPE`, and any other environment variables read by `bin/easy-db-lab` or `bin/end-to-end-test`, with a comment and safe placeholder for each

## 3. bin/easy-db-lab

- [x] 3.1 Add `.env` sourcing block near the top of `bin/easy-db-lab` (after `PROJECT_ROOT` is defined, using `set -a` / `source` / `set +a`, only if file exists)

## 4. bin/end-to-end-test

- [x] 4.1 Add `.env` sourcing block near the top of `bin/end-to-end-test` (same pattern as `bin/easy-db-lab`, after `PROJECT_ROOT` is defined)
- [x] 4.2 Remove the hardcoded `export AWS_PROFILE=sandbox-admin` line from `bin/end-to-end-test`
- [x] 4.3 Update the usage/help comment block in `bin/end-to-end-test` to mention that `AWS_PROFILE` must be set via environment or `.env`

## 5. Documentation

- [ ] 5.1 Update `docs/development/` local development guide to explain the `.env` pattern, list all supported variables, and provide a copy-paste setup example
