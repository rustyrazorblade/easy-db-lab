## 1. `kit.yaml` CLI help text

- [ ] 1.1 Update the `--rate` flag's `description` in
      `src/main/resources/com/rustyrazorblade/easydblab/kits/sysbench/kit.yaml` to warn
      that rates well above cluster capacity cause sysbench's internal event queue to
      overflow and hard-abort within seconds (rather than sustaining an overload window),
      and to point at the user guide for the recommended approach.

## 2. User guide (`docs/user-guide/sysbench.md`)

- [ ] 2.1 Add `--rate`, `--skip-trx`, and `--rand-type` rows to the Flags table (currently
      missing despite being real `kit.yaml` `start` args).
- [ ] 2.2 Add a "Rate limiting and overload testing" subsection covering: what `--rate`
      does, why over-capacity rates hard-abort (`FATAL: event queue is full`) instead of
      degrading gracefully, and the recommended alternative for overload/latency testing —
      thread-bound runs (`--rate=0` with a chosen `--threads`) or a rate deliberately set
      closer to measured capacity.

## 3. Verification

- [ ] 3.1 Confirm `docs/user-guide/sysbench.md` still builds cleanly with mdbook (per
      `docs/CLAUDE.md`) and that the new section reads consistently with the rest of the
      page's style.
- [ ] 3.2 Confirm `kit.yaml` still parses/validates (e.g. `easy-db-lab kit info sysbench`
      renders the updated `--rate` description correctly).
