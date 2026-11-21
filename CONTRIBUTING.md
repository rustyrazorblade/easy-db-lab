Contributions are welcome and encouraged, although it is a good idea to check before writing any code for a new feature to make sure it lines up with the project roadmap.

## Before you mark a pull request ready

Open a pull request as a draft.  Mark it ready for review only after you have tested it on a real cluster with the [easy-db-lab Claude Code plugin](https://github.com/rustyrazorblade/skills/tree/main/plugins/easy-db-lab).  Unit tests and CI alone are not enough: a mocked cloud client accepts a wrong parameter, a wrong region, or a missing permission.

1. Install the plugin from the `rustyrazorblade/skills` marketplace:

   ```
   /plugin marketplace add rustyrazorblade/skills
   /plugin install easy-db-lab@rustyrazorblade-plugins
   ```

2. Write a test plan for your change with the [`plan` skill](https://github.com/rustyrazorblade/skills/tree/main/plugins/easy-db-lab/skills/plan) (`/easy-db-lab:plan`).
3. Run it with the [`run` skill](https://github.com/rustyrazorblade/skills/tree/main/plugins/easy-db-lab/skills/run) (`/easy-db-lab:run`).  The skill provisions the cluster, runs each step, writes a lab report, and tears the cluster down.
4. Post a comment on the pull request that says what you tested:
   - the cluster shape (node count, instance types, database and version);
   - the commands you ran;
   - what you expected, and what you saw;
   - anything you did not test, and why.

A pull request that only changes documentation does not need a cluster test.
