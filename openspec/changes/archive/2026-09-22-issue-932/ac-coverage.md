# AC → scenario coverage — issue-932

| Source | Requirement | Covering scenario(s) | Status |
|--------|-------------|----------------------|--------|
| AC | `help <topic>` prints the topic's markdown body to stdout, exits 0 | `cli-help-topics: Known topic prints its body` | ✅ Covered |
| AC | `help` no-arg explains `help <topic>` and lists every topic with description, exits 0 | `cli-help-topics: No-argument help lists all topics` | ✅ Covered |
| AC | `help <unknown>` prints an error naming the invalid topic AND lists valid topics, exits non-zero | `cli-help-topics: Unknown topic names the bad topic and lists valid ones` | ✅ Covered |
| AC | Topic content loaded from a packaged classpath resource (works with no source tree), never a repo path | `cli-help-topics: Content resolves from a distribution with no source tree` | ✅ Covered |
| AC | Topic matching is case-insensitive (`help Provisioning` == `help provisioning`) | `cli-help-topics: Topic matching is case-insensitive` | ✅ Covered |
| AC | A new `.md` file with valid frontmatter appears in the listing and is retrievable, no code change | `cli-help-topics: A new topic file is discovered with no code change` | ✅ Covered |
| AC | A file with missing/malformed frontmatter is skipped (logged, not crashing); remaining topics still work | `cli-help-topics: A malformed file is skipped and the rest still work` | ✅ Covered |
| AC | The distribution ships nine seed topics (`provisioning`, `kits`, `stress-testing`, `profiles`, `connecting`, `querying`, `observability`, `spark`, `cassandra`), each task-oriented, not a flag reference | `cli-help-topics: Each seed topic describes how to perform its operation` | ✅ Covered |
| AC | The `cassandra` topic is an umbrella database-management guide (lifecycle + version select/install + Cassandra config patch-file workflow) that points to `stress-testing` for load; no standalone `configs` topic | `cli-help-topics: Each seed topic describes how to perform its operation` (content review during implementation) | ✅ Covered |
| AC | `easy-db-lab -h` root usage carries a footer directing the user to run `help` for task guides | `cli-help-topics: Root usage footer points to the help topics` | ✅ Covered |
| AC | A topic-mapped subcommand's `-h` usage carries a footer naming the related `help <topic>` | `cli-help-topics: A command's usage points to its related topic` | ✅ Covered |
| AC | Footer pointer text is derived from the discovered topic set, not a second hardcoded list | `cli-help-topics: Pointer text tracks the discovered topics` | ✅ Covered |
| Risk (critic 1) | Unknown-topic error must place the valid-topics list AND avoid the `IllegalArgumentException:` terminal prefix | `cli-help-topics: Unknown topic names the bad topic and lists valid ones` (asserts named topic + valid list + no exception-class prefix + non-zero exit) | ✅ Covered |
| Risk (critic 3) | Malformed-file handling must skip-one, never skip-all | `cli-help-topics: A malformed file is skipped and the rest still work`; enforced by tasks 1.2 and 2.4 | ✅ Covered |
| Risk (architect) | A2 refactor must keep MCP prompt behavior intact | Not a spec scenario (internal refactor, no behavior change); guarded by task 1.4 keeping the existing MCP prompt tests green | ⚠️ Excluded — internal implementation change with no spec-level behavior delta; covered by preserved tests, not a new scenario |
| Risk (architect) | No `-h`/`--help` or kit-name collision | Not a spec scenario | ⚠️ Excluded — verified structurally by the design-critic against the actual registration code; no behavior to assert in a scenario |
| Risk (architect/critic) | Duplicate `name` frontmatter across two files is undefined (first match wins) | — | ⚠️ Excluded — out of scope for a curated seed set; documented in design.md Risks, not a required behavior |
