# MkDocs to mdbook Migration Design

## Overview

Migrate documentation from MkDocs (Python) to mdbook (Rust) for simpler tooling and better alignment with Rust ecosystem preferences.

## Scope

- 20 documentation files across 4 sections
- 7 admonitions to convert
- 1 GitHub Actions workflow to update
- 2 MkDocs files to remove

## Changes

### 1. Create `docs/book.toml`

```toml
[book]
title = "easy-db-lab"
description = "A tool for building lab environments for database testing"
authors = ["Jon Haddad"]
language = "en"
src = "."

[build]
build-dir = "../site"

[output.html]
git-repository-url = "https://github.com/rustyrazorblade/easy-db-lab"
edit-url-template = "https://github.com/rustyrazorblade/easy-db-lab/edit/main/docs/{path}"

[preprocessor.admonish]
command = "mdbook-admonish"
assets_version = "3.0.2"
```

### 2. Create `docs/SUMMARY.md`

```markdown
# Summary

[Introduction](index.md)

# Getting Started

- [Prerequisites](getting-started/prerequisites.md)
- [Installation](getting-started/installation.md)
- [Setup](getting-started/setup.md)

# User Guide

- [Cluster Setup](user-guide/cluster-setup.md)
- [Tutorial](user-guide/tutorial.md)
- [Cassandra](user-guide/installing-cassandra.md)
- [Kubernetes](user-guide/kubernetes.md)
- [SSH Proxying](user-guide/ssh-proxying.md)
- [Victoria Logs](user-guide/victoria-logs.md)
- [Spark](user-guide/spark.md)
- [ClickHouse](user-guide/clickhouse.md)
- [Shell Aliases](user-guide/shell-aliases.md)
- [MCP Server](integrations/mcp-server.md)

# Reference

- [Commands](reference/commands.md)
- [Ports](reference/ports.md)
- [Log Infrastructure](reference/log-infrastructure.md)

# Development

- [Overview](development/overview.md)
- [Docker](development/docker.md)
- [Publishing](development/publishing.md)
- [Testing](development/testing.md)
- [End-to-End Testing](development/end-to-end-testing.md)
- [Spark](development/spark.md)
- [SOCKS Proxy Architecture](development/socks-proxy.md)
- [Fabric8 Server-Side Apply](development/fabric8-server-side-apply.md)
```

### 3. Convert Admonitions

Convert MkDocs `!!!` syntax to mdbook-admonish fenced blocks in:
- `docs/user-guide/tutorial.md` (2)
- `docs/user-guide/installing-cassandra.md` (1)
- `docs/getting-started/setup.md` (3)
- `docs/getting-started/prerequisites.md` (1)

**Before:**
```markdown
!!! note "Title"
    Content here
```

**After:**
````markdown
```admonish note title="Title"
Content here
```
````

### 4. Update GitHub Actions Workflow

Replace `.github/workflows/docs.yml` with mdbook-based workflow:
- Download pre-built mdbook and mdbook-admonish binaries
- Run `mdbook build docs`
- Deploy to GitHub Pages (same as before)

### 5. Delete MkDocs Files

- `config/mkdocs.yml`
- `docs/requirements.txt`

## Implementation Order

1. Create `docs/book.toml`
2. Create `docs/SUMMARY.md`
3. Convert admonitions in 4 files
4. Update `.github/workflows/docs.yml`
5. Delete `config/mkdocs.yml` and `docs/requirements.txt`
6. Test locally with `mdbook build docs`
