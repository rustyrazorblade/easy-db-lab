## ADDED Requirements

### Requirement: Node output lines are tagged with their node

Every line of output from a command run on a cluster node MUST be printed with the node's alias in brackets in front of it, for example `[db12] Discarding blocks...Done.`  This MUST hold for every command, also when it targets one node.  Stdout lines MUST go to stdout and stderr lines to stderr.  A line MUST be printed as soon as it is complete, while the command still runs.

#### Scenario: A setup script's lines carry the node
- **GIVEN** `up` runs `setup_instance.sh` on db12
- **WHEN** the script prints `Discarding blocks...Done.`
- **THEN** the console shows `[db12] Discarding blocks...Done.`

#### Scenario: Stderr is printed and tagged
- **WHEN** a remote command on app0 writes `ERROR: no data disk` to stderr
- **THEN** stderr shows `[app0] ERROR: no data disk`

#### Scenario: Output streams while the command runs
- **GIVEN** a remote command that prints a line, then sleeps, then prints another
- **WHEN** it runs
- **THEN** the first line is printed before the command ends

### Requirement: Parallel nodes never share a line

Each node output line MUST be one whole write to the console.  Lines from different nodes MUST NOT be mixed within one console line, and a node's lines MUST keep their order.  Output split across reads (including a multi-byte character) MUST be joined before it is printed.  A line longer than `Constants.NodeOutput.MAX_LINE_BYTES` MUST be printed in pieces of at most that size, each tagged.

#### Scenario: Many nodes print at once
- **GIVEN** 32 nodes each print 500 lines at the same time
- **WHEN** the console output is read
- **THEN** every line is one tag followed by one of that node's lines, and each node's lines are in order

#### Scenario: A line split across two reads
- **WHEN** a remote line arrives in two pieces, split inside a UTF-8 character
- **THEN** the console prints it once, whole and correctly decoded

### Requirement: Node output is plain text, not events

Node output lines and upload lines MUST be printed as plain console text through a provider-neutral port.  They MUST NOT be emitted on the event bus, and MUST NOT reach Redis.  The port MUST NOT depend on the SSH transport or a cloud provider's types, so another transport feeds the same port.  Output of a command marked secret MUST NOT be printed or kept.  Every line MUST have URL credentials redacted before it is printed or kept.

#### Scenario: Redis sees no node output
- **GIVEN** `EASY_DB_LAB_REDIS_URL` is set
- **WHEN** `up` runs the setup scripts on every node
- **THEN** the Redis channel receives no message holding script output, and still receives `Provision.NodeReady` for each node

#### Scenario: A secret command prints nothing
- **WHEN** a command marked secret succeeds and prints its arguments
- **THEN** none of its output is printed or kept in the node's tail

#### Scenario: A credential in the output is redacted
- **WHEN** a remote command prints `https://ghp_token@github.com/acme/cassandra.git`
- **THEN** the printed line shows `https://***@github.com/acme/cassandra.git`

### Requirement: An upload prints one line

Each call that uploads a file or a directory to a node MUST print one line tagged with the node, naming the local and the remote path.  A directory upload MUST print one line, not one per file.

#### Scenario: A directory upload
- **WHEN** a directory of 40 files is uploaded to control0
- **THEN** the console shows one line starting with `[control0] uploading`

### Requirement: Each node's recent output is kept

The system MUST keep the last `Constants.NodeOutput.TAIL_LINES` output lines of each node, stdout and stderr in arrival order, in quiet mode and in normal mode.  It MUST NOT keep more than that per node.

#### Scenario: Only the last lines are kept
- **GIVEN** a node prints 100 lines
- **WHEN** its tail is read
- **THEN** it holds the last `Constants.NodeOutput.TAIL_LINES` lines

### Requirement: Quiet mode hides node output

The system MUST offer a quiet mode in which node output lines and upload lines are not printed.  In quiet mode every event MUST still be printed, and each node's tail MUST still be kept.  Quiet mode MUST end when the command that set it ends, also when it fails.

#### Scenario: Quiet hides lines and keeps events
- **GIVEN** quiet mode
- **WHEN** a node prints lines and then a node-ready event is emitted
- **THEN** the console shows the node-ready line and none of the node's lines

#### Scenario: Quiet ends with its command
- **GIVEN** a command in quiet mode fails
- **WHEN** another command runs in the same process (the REPL)
- **THEN** its node output is printed
