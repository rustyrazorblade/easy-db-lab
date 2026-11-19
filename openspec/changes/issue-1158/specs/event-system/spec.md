## ADDED Requirements

### Requirement: An event can stay off the console

An event type MUST be able to declare that the console does not print it.  Every other destination MUST still receive it.  This is for events that come in high volume and exist for subscribers, such as a node's step start and completion.  An error event MUST always be printed.

#### Scenario: A console-hidden event reaches Redis only
- **GIVEN** console and Redis destinations are both configured
- **WHEN** an event that declares it stays off the console is emitted
- **THEN** Redis receives it and the console prints nothing for it

#### Scenario: Events print by default
- **WHEN** an event that declares nothing about the console is emitted
- **THEN** the console prints it, as before
