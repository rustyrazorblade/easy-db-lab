# User Documentation (`docs/`)

This directory contains the user-facing documentation for easy-db-lab, built with [mdbook](https://rust-lang.github.io/mdBook/).

## Purpose

These docs are for **end users** of the tool — people setting up clusters, running stress tests, and using the observability stack. This is NOT developer/architecture documentation (that lives in `CLAUDE.md` files throughout the source tree).

## Organization

```
docs/
├── CLAUDE.md              # This file
├── SUMMARY.md             # Table of contents — mdbook requires this
├── book.toml              # mdbook configuration
├── index.md               # Landing page
├── getting-started/       # Installation, prerequisites, initial setup
├── user-guide/            # Feature documentation (the bulk of the docs)
├── reference/             # Ports, commands, infrastructure details
├── development/           # Developer guides (building, testing, publishing)
├── integrations/          # External tool integrations (MCP server)
└── plans/                 # Implementation plans (not published)
```

## Key Rules

- **`SUMMARY.md` is the table of contents.** Every new page MUST be added here or it won't appear in the built site. mdbook uses this file to generate navigation.
- **Keep docs in sync with code changes.** When a user-facing feature changes (new command, new dashboard, new config option, port change), update the relevant doc page.
- **Write for users, not developers.** Explain what things do and how to use them. Don't explain internal implementation details — that belongs in source-level `CLAUDE.md` files.
- **Use mdbook-admonish for callouts.** The project uses the admonish preprocessor for warning/info/tip blocks.

## When to Update

Update docs when:
- Adding or removing a CLI command → update `reference/commands.md`
- Changing a port number → update `reference/ports.md`
- Adding a new observability component → update `user-guide/monitoring.md` or create a new page
- Changing profiling behavior → update `user-guide/profiling.md`
- Adding a new datasource or dashboard → update `user-guide/monitoring.md`
- Changing cluster setup flow → update `user-guide/cluster-setup.md` and `user-guide/tutorial.md`

## Adding a New Page

1. Create the `.md` file in the appropriate subdirectory
2. Add an entry to `SUMMARY.md` in the correct section
3. Use other pages as a style reference

## Building Locally

```bash
# Install mdbook if needed
cargo install mdbook mdbook-admonish

# Build and serve
cd docs
mdbook serve
```

The site builds to `../site/` (per `book.toml`).
