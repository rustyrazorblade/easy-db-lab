# Tasks — issue-908

1. `dashboards/cassandra-overview.json` — replace the four `"unit": "\u00b5s"` declarations
   (panel ids 4, 5, 28, 29) with `"unit": "ms"`. Byte-preserving edit only.
2. `dashboards/cluster-comparison.json` — replace all eight raw-UTF-8 `µs` occurrences with `ms`.
   Seven are unit declarations; the eighth is the `(µs)` inside `panels[14]`'s description prose.
3. `dashboards/cluster-comparison.json` — rescale the latency thresholds by 1000: `5000` to `5`
   and `20000` to `20`. Five pairs, all latency: `panels[1]` defaults plus its `Write p99` and
   `Read p99` byName overrides, `panels[11]` defaults, and `panels[19]` defaults.
4. Verify the diff: only these two files touched, both still parse (`jq empty`), no
   `targets[].expr` changed, changed-line count as enumerated with no whole-file reindent, and
   every non-ASCII byte outside the edits unchanged.
5. Deploy to the shared `dashboard-dev` cluster — `./gradlew installDist`, then
   `grafana update-config` from inside `clusters/dashboard-dev/` — and read the unit back from the
   Grafana API to prove it landed. Never take that cluster down or recreate it.
6. Capture the read-back output as PR evidence, as the `requires-aws` label requires.

## Editing hazard

`dashboards/CLAUDE.md` prescribes `perl -0pi -e` with `\Q...\E`. **That idiom silently no-ops on
`cassandra-overview.json`** — Perl reads the `\u` in `\u00b5` as its titlecase escape during
interpolation, before `\Q` applies, so the pattern degrades and matches nothing. Exit code 0, no
error, file unchanged. Reproduced during activation. Use a literal byte replacement (the `Edit`
tool), or escape the backslash: `s/"unit": "\\u00b5s"/"unit": "ms"/g`.

Never edit these files with `jq` — it round-trips the whole document, which previously produced a
1,644-line reindent and mangled `µ` and `—`.

`git diff --stat` reports 20 changed lines as `40 +-` (insertions and deletions counted
separately). Use `git diff --numstat` and expect `20 20`.
