## ADDED Requirements

### Requirement: kit-ref is a valid arg type in the capabilities arg system
A new `kit-ref` type SHALL be a valid arg type in the `capabilities` arg system
(REQ-KCAP-001), alongside the existing `string`, `int`, `boolean`, and `float` types.
It is usable in the `args:` block of any kit, with the same `flag`, `variable`,
`description`, and `required` fields.

#### Scenario: kit-ref arg parsed alongside other arg types
- **WHEN** a `kit.yaml` declares args including one with `type: kit-ref`
- **THEN** all args including the `kit-ref` entry deserialize without error

#### Scenario: kit-ref arg appears in kit info output
- **WHEN** the user runs `easy-db-lab kit info <bench-kit-name>`
- **THEN** the `kit-ref` arg is listed with its flag, variable, and description
