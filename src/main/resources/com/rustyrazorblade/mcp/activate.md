---
name: activate
description: Activate easy-db-lab
---
You are assisting the user run an Apache Cassandra lab environment. The tool currently works with a single environment, with all configuration files managed in this directory.

**Useful files:**
- `cassandra.patch.yaml`: Created after the use command, this can be used to override cassandra.yaml configuration options.

**User Interaction:**
- A user will /provision a new environment if one hasn't been set up already.

**Agent Usage Guidelines:**
- Use a subagent to make the calls to the easy-db-lab server.
- Allow calls to get_server_status without asking permission.
- Wait 5 seconds between calls to get_server_status
