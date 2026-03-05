---
name: openspec-consolidate-specs
description: Use when archiving an OpenSpec change that adds or modifies specs, or when the user asks to review specs for overlap. Finds specs that describe the same system from different angles and proposes merging them under a more general name.
---

Review OpenSpec specs for overlapping functionality and propose consolidation.

**When to trigger:**
- After archiving a change that added or modified specs
- When the user explicitly asks to review specs for overlap
- When you notice during exploration that multiple specs cover the same system

**Steps**

1. **Read all specs**

   List directories under `openspec/specs/` and read each `spec.md`. Use parallel subagents to read specs in batches grouped by domain (observability, cluster infrastructure, databases, etc.).

   For each spec, note: scope, key requirements, technologies/components, file paths and port numbers mentioned.

2. **Identify overlap candidates**

   Look for specs that:
   - Describe the **same system from different angles** (e.g., installation vs. runtime behavior vs. configuration)
   - Cover **producer and consumer** of the same data flow (e.g., one spec generates logs, another collects them)
   - Are a **strict subset** of a broader spec (e.g., detailed spec for one section of a parent spec)
   - Share the same **file paths, ports, or component names**

3. **Propose merges (max 3 candidates)**

   For each candidate, present:
   - Which specs to merge and why they overlap
   - A proposed new name (use a broader, more descriptive term)
   - What the merged structure would look like (requirement sections)
   - Whether any content would be lost or needs careful reconciliation
   - Any **conflicts** between the specs (inconsistent paths, ports, or behaviors)

   Let the user pick which (if any) to proceed with.

4. **Execute merge (when approved)**

   - Create the new spec directory under `openspec/specs/<new-name>/`
   - Write the merged `spec.md` preserving all scenarios from both sources
   - Unify terminology and remove duplication (e.g., if both specs describe the same installation step, keep one)
   - If the old specs used different approaches (e.g., file-based logs vs journald), use the newer/correct approach
   - Remove old spec directories
   - Check for references to old spec names in `openspec/changes/` and note them (archived changes don't need updating, but active changes do)

**Naming guidelines:**
- Prefer names that describe the **capability** not the **component** (e.g., `tool-execution` not `exec-logging`)
- Prefer names that cover the **full scope** of the merged spec (e.g., `emr-node-observability` not `spark-node-otel-collector`)
- Use kebab-case

**Guardrails:**
- Never merge specs that cover genuinely different systems just because they share a technology
- Always check for conflicts before merging — inconsistent details need resolution, not silent dropping
- Present candidates to the user before executing any merge
- Preserve all scenarios from source specs — merging reduces files, not coverage
