## Overrides existing behavior

None: this change only adds new requirements.

## Conflicts with other in-flight changes

- `issue-971` also touches `cassandra`: no actual conflict.  It modifies the "Cluster Lifecycle" requirement; this change adds "JDK 25 Selection" and "GC Log on JDK 17 and Higher".
- `issue-966` and `issue-970` do not touch `cassandra` or `ami-building`.
