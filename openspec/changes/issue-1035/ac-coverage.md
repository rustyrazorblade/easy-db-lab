# Acceptance criteria coverage for issue-1035

| Source | Requirement | Covering scenario(s) | Status |
|---|---|---|---|
| AC | Volume storage backed by D writes all four signals under D; no S3 or AWS request | observability-store: Volume storage writes every signal under D | ✅ Covered |
| AC | Data older than the local hold window is returned for every signal | observability-store: Older data is read from D; local-observability-compaction: Mimir writes the bucket index locally | ✅ Covered |
| AC | A new cluster on the same D can query every earlier signal, including profiles | observability-store: A new cluster reads the earlier data; Backend state lives under D | ✅ Covered |
| AC | Local compaction removes only merged sources; no retention or expiry | local-observability-compaction: Compaction removes only merged sources | ✅ Covered |
| AC | `grafana backup` and `report upload` land under D; the documents view shows reports | grafana-annotations: Backup writes under D; observability-store: Report upload lands under D; The documents view shows local reports | ✅ Covered |
| AC | On AWS: storage, IAM deny and account compactor unchanged | observability-store: AWS behaviour is unchanged; Default storage is S3 | ✅ Covered |
| Risk | macOS bind mount and the Mimir xattr (the PV sits on the bind mount) | design.md gate; tasks 1.1-1.2; the "Data directory mirrors the bucket layout" requirement allows a node-local Mimir state claim | ✅ Covered (gate) |
| Risk | A path that could delete data | local-observability-compaction: Compaction removes only merged sources; observability-store: A key outside D is refused | ✅ Covered |
| Risk | A backend chokes on its unused store block | observability-store: Each backend ignores the store it is not using; task 1.3 | ✅ Covered |
| Risk | AWS and local compactor flags drift | local-observability-compaction: AWS and local compactor flags do not drift | ✅ Covered |
| Risk | The account compactor is called locally | local-observability-compaction: The account compactor is not touched | ✅ Covered |
| Risk | Non-observability callers break on a filesystem binding | observability-store: Non-observability callers keep S3 | ✅ Covered |
| Risk | The documents server exposes more than reports | observability-store: Only GET under reports is served | ✅ Covered |
| AC (owner 2026-11-10) | Backends mount a claim and no hostPath in non-S3 mode | observability-store: Backends mount a claim and no hostPath in Volume storage; Claims are per backend; task 2.5 | ✅ Covered |
| AC (owner 2026-11-10) | A removed cluster leaves the PV data intact; a new cluster's claims bind to the same paths and read every earlier signal | observability-store: A removed cluster leaves the volume data intact; A new cluster's claims bind to the same paths; tasks 6.4-6.5 | ✅ Covered |
| AC (owner 2026-11-10) | The `local-path` provisioner is never used for observability data | observability-store: local-path is never used; task 2.5 | ✅ Covered |
| Risk | The claim path is untestable before #1041 | design.md "Testing before #1041"; tasks 6.4-6.5 (K3s TestContainer with test-created Retain PVs over a temp directory) | ✅ Covered |
| Risk | Builders each hard-code their data path | observability-store: Backend data placement comes from one function; task 2.4 | ✅ Covered |
| Risk | Two stacks on one D | — | ⛔ Excluded: owner decision 5 is one stack per D, documented, with no lock (task 7.1) |
| Risk | Loki filesystem chunk names differ from S3 | — | ⛔ Excluded: that affects #902's export reader (`LokiFilesystemLayout` in PR #1020), not this change's writers |
| Risk | `down`/`clean` deleting D | — | ⛔ Excluded here: this change creates no PV and deletes no claim, PV or file under D (scenario: A removed cluster leaves the volume data intact); #1041 (local `up`/`down`) owns the PV creation and the whole-cluster check, with its "no file under D is deleted" criterion |
| Risk | Linux CI cannot reproduce a macOS bind mount | — | ⛔ Excluded from CI: a manual macOS check is recorded in design.md (task 1) |
| Risk | CLI-side documents and backups on a bring-your-own cluster | — | ⛔ Excluded: the CLI cannot reach the volume there; #1082 handles them through a pod or S3 (proposal Out of scope) |
