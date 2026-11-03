## ADDED Requirements

### Requirement: Metric samples go to Redis only

`Metrics.Sample` events SHALL be published to the Redis channel directly, not through the event bus.  They SHALL never reach the console.  Each one SHALL use the same JSON event envelope as every other event on the channel.  A sample that cannot be published SHALL be dropped, not queued.

#### Scenario: Sample is not printed
- **WHEN** `metrics stream` publishes a `Metrics.Sample`
- **THEN** a Redis subscriber on the channel receives it as an event envelope
- **AND** the terminal does not show it

#### Scenario: Health events still use the bus
- **WHEN** a streamed metric starts failing
- **THEN** the `Metrics.MetricFailing` event reaches the console and the Redis channel

## MODIFIED Requirements

### Requirement: Multiple Output Destinations

The system MUST support multiple simultaneous output destinations via fan-out. Adding a new destination requires no changes to event-emitting code.  The destinations are the console and, when it is configured, Redis.

#### Scenario: All configured destinations receive the event

- **GIVEN** console and Redis destinations are both configured
- **WHEN** an event is emitted
- **THEN** both destinations receive the event.

#### Scenario: Only configured destination receives the event

- **GIVEN** only console is configured
- **WHEN** an event is emitted
- **THEN** only console output is produced.

### Requirement: Redis Pub/Sub Destination

The system MUST support publishing events to a Redis pub/sub channel using the Jedis client library, configured via environment variable.  The tool MUST check Redis at startup and fail fast when it does not answer.  After startup, Redis publishing is best effort for every command: a failed publish is logged and the event dropped, and the command keeps running.

#### Scenario: Publish events to configured Redis channel

- **GIVEN** `EASY_DB_LAB_REDIS_URL` is set (format: `redis://host:port/channel`)
- **WHEN** the tool starts
- **THEN** the tool connects using Jedis and publishes events to the specified Redis channel.

#### Scenario: Subscriber receives JSON envelopes

- **GIVEN** a Redis subscriber listening on the channel
- **WHEN** commands run
- **THEN** the subscriber receives structured event envelopes as JSON.

#### Scenario: Fail fast when Redis unavailable

- **GIVEN** Redis is unavailable at startup
- **WHEN** the environment variable is set
- **THEN** the tool exits non-zero before any command runs
- **AND** the message names `EASY_DB_LAB_REDIS_URL` and the URL that failed.

#### Scenario: Redis lost while a command runs

- **GIVEN** `EASY_DB_LAB_REDIS_URL` is set and Redis answered at startup
- **WHEN** Redis is lost while any command runs
- **THEN** the command keeps running
- **AND** each event it could not publish is logged and dropped.

#### Scenario: Default channel when URL has no path

- **GIVEN** the Redis URL has no path component
- **WHEN** the tool starts
- **THEN** the system uses the default channel from Constants.
