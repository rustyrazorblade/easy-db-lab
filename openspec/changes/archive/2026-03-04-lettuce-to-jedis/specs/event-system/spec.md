## MODIFIED Requirements

### Requirement: Redis Pub/Sub Destination

The system MUST support publishing events to a Redis pub/sub channel using the Jedis client library, configured via environment variable.

#### Scenario: Successful connection and publishing
- **WHEN** `EASY_DB_LAB_REDIS_URL` is set (format: `redis://host:port/channel`)
- **THEN** the tool connects using Jedis and publishes events to the specified Redis channel

#### Scenario: Subscriber receives events
- **WHEN** a Redis subscriber is listening on the channel and commands run
- **THEN** the subscriber receives structured event envelopes as JSON (identical wire format)

#### Scenario: Fail fast on unavailable Redis
- **WHEN** Redis is unavailable at startup and the environment variable is set
- **THEN** the tool fails fast with a connection error

#### Scenario: Default channel when no path specified
- **WHEN** the Redis URL has no path component
- **THEN** the system uses the default channel from Constants
