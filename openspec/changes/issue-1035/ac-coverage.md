# Acceptance criteria coverage for issue-1035

| Source | Requirement | Covering scenario(s) | Status |
|---|---|---|---|
| AC | Filesystem storage at D writes all four signals under D; no S3 or AWS request | observability-store: All four signals land under D with no AWS request | Covered |
| AC | Data older than the local hold window is returned for every signal | observability-store: Older data is read from D; local-observability-compaction: Mimir writes the bucket index locally | Covered |
| AC | A new cluster on the same D queries every earlier signal, profiles included | observability-store: A new cluster reads the earlier data; Signals land in the bucket's paths (state under D) | Covered |
| AC | Local compaction removes only merged sources; no retention | local-observability-compaction: Compaction removes only merged sources | Covered |
| AC | `grafana backup` and `report upload` land under D; the documents view shows reports | grafana-annotations: Backup writes under D; observability-store: Report upload lands under D; The documents view shows local reports | Covered |
| AC | AWS unchanged | observability-store: No record means the account bucket; AWS rendering is unchanged | Covered (Loki `object_store` line now reads `store_kind`, same value) |
| Amendment 2026-11-11 | Host path vs claim chosen by `HOST_PATH`, not store type | observability-store: Local cluster mounts D; Your cluster mounts the claim | Covered |
| Amendment / K3 | Non-Retain StorageClass refused, naming the class | observability-store: A deleting class is refused | Covered (existing `RetainedClaimCheck`) |
| Amendment / K3 | Retained volume labelled cluster-scoped | observability-store: Retained volume is labelled | Covered |
| K5 | Writer cannot delete; compactor deletes only what it merged | observability-store: The writer cannot delete merged roots; Backends get the writer, the compactor gets the compactor | Covered off AWS; on AWS deferred to the D7 child issue (both identities stay the instance role; the AWS criterion is "unchanged") |
| Lead | Store credentials off AWS populate `StoreCredentials.FromSecret` | observability-store: Backends get the writer...; Missing Secret; Missing key | Covered |
| Lead | CompactorIam prefixed allow is last-writer-wins | account-compactor: Two prefixes do not narrow each other; The compactor follows its store prefix | Covered |
| Risk R1 | AWS calls leak in local mode | observability-store: All four signals land under D with no AWS request | Covered |
| Risk R2 | #1071 merge renders a placeholder or old key | account-compactor: The compactor follows its store prefix | Covered |
| Risk R3 | Pyroscope Raft identity on a new cluster | observability-store: A new cluster reads the earlier data (task 3.6, 8.2) | Covered |
| Risk R4 | WAL replay duplicates on reuse | observability-store: A new cluster reads the earlier data ("no log line is returned twice") | Covered |
| Risk | Filesystem object store escapes D | observability-store: A key outside D is refused | Covered |
| Risk | `TenantDirectory` depends on S3 common prefixes | observability-store: Tenants are listed from D | Covered |
| Risk | Non-observability callers break | observability-store: Non-observability callers keep S3 | Covered |
| Risk | Documents server exposes more than reports | observability-store: Only reads under reports are served | Covered |
| Risk | Local and account compactor flags drift | local-observability-compaction: Local and account compactor flags do not drift | Covered |
| Risk R5 | K5 is not structural on a filesystem | — | Excluded: inherent (a writing process can unlink); known limit, accepted by the owner on 2026-11-16 and documented (task 9.1) |
| Risk R7 | Writer delete widens beyond `pyroscope/` | observability-store: The writer cannot delete merged roots | Covered |
| Risk R8 | Uncompacted Tempo slows long-lived D | — | Excluded: reads stay correct; Tempo compaction stays off by existing decision |
| Risk R9 | Two stacks on one D | — | Excluded: owner decision 5, one stack per D, documented (task 9.1) |
| Risk R10 | Linux host uid on bind mount | observability-store: Volume hosting... (init container where `NODE_SSH` absent, task 3.5) | Covered |
| Risk R11 | macOS bind-mount xattr | — | Excluded from CI: manual gate (task 1, D13) |
| Risk | `down`/`clean` deleting D | — | Excluded here: this change adds no deletion path; #1041 owns the "no file under D is deleted" criterion |
| Risk | CLI objects on a user's retained volume | — | Excluded: fail with a #1082 message (observability-store requirement text); #1082 builds it |
