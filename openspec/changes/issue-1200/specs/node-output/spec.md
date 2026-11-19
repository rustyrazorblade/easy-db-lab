## ADDED Requirements

### Requirement: Node output lines are tagged with their node

Every line of output from a command run on a cluster node MUST be printed with the node's alias in brackets in front of it, for example `[db12] Discarding blocks...Done.`  This MUST hold for every command, also when it targets one node.  Each tag MUST be padded with spaces to the length of the longest alias printed so far, so the text of many nodes lines up.  Stdout lines MUST go to stdout and stderr lines to stderr.  A line MUST be printed as soon as it is complete, while the command still runs.

#### Scenario: A setup script's lines carry the node
- **GIVEN** `up` runs `setup_instance.sh` on db12
- **WHEN** the script prints `Discarding blocks...Done.`
- **THEN** the console shows `[db12] Discarding blocks...Done.`

#### Scenario: Stderr is printed and tagged
- **WHEN** a remote command on app0 writes `ERROR: no data disk` to stderr
- **THEN** stderr shows `[app0] ERROR: no data disk`

#### Scenario: Tags are padded to the longest alias
- **GIVEN** `control0` has printed a line
- **WHEN** db0 prints `three`
- **THEN** the console shows `[db0]      three`, its text in the same column as `control0`'s

#### Scenario: Output streams while the command runs
- **GIVEN** a remote command that prints a line, then sleeps, then prints another
- **WHEN** it runs
- **THEN** the first line is printed before the command ends

### Requirement: Parallel nodes never share a line

Each node output line MUST be one whole write to the console.  Lines from different nodes MUST NOT be mixed within one console line, and a node's lines MUST keep their order.  Output split across reads (including a multi-byte character) MUST be joined before it is printed.  A line longer than `Constants.NodeOutput.MAX_LINE_BYTES` MUST be printed in pieces of at most that many bytes, each tagged; a piece MUST end before a character that does not fit whole, and a URL that starts within `Constants.NodeOutput.URL_CARRY_BYTES` of the cut MUST move whole to the next piece, so its credentials are still redacted.  Waiting for the console MUST NOT block the SSH transport's own threads, and MUST NOT deadlock when more callers than processors run commands at once.

#### Scenario: Many nodes print at once
- **GIVEN** 32 nodes each print 500 lines at the same time
- **WHEN** the console output is read
- **THEN** every line is one tag followed by one of that node's lines, and each node's lines are in order

#### Scenario: A line split across two reads
- **WHEN** a remote line arrives in two pieces, split inside a UTF-8 character
- **THEN** the console prints it once, whole and correctly decoded

#### Scenario: A long line is cut before a character that does not fit
- **GIVEN** a line cap of 4 bytes
- **WHEN** a node prints `abcéd`
- **THEN** the console shows `abc` and `éd`, each at most 4 bytes

#### Scenario: More callers than processors
- **GIVEN** more virtual threads than available processors each run a command whose output reaches a briefly blocking console
- **WHEN** they all run at once
- **THEN** every command finishes

### Requirement: A carriage return replaces the current line

A `\r` that is not followed by `\n` MUST start a new segment that replaces the current one, so a progress bar does not fill the console and the tail.  At `\n`, or when the output ends, the last non-empty segment MUST be printed once.  `\r\n` MUST behave as `\n`.

#### Scenario: Progress updates print their last value
- **WHEN** a node prints `50%\r100%\r\n`
- **THEN** the console shows one line, `100%`

#### Scenario: Empty segments are dropped
- **WHEN** a node prints `a\r\rb\n`, then `x\r\r\n`
- **THEN** the console shows `b` and `x`

#### Scenario: Output that ends after a carriage return
- **WHEN** a node prints `50%\r` and its command ends
- **THEN** the console shows `50%`

### Requirement: Node output is plain text, not events

Node output lines and notices MUST be printed as plain console text through a provider-neutral port.  They MUST NOT be emitted on the event bus, and MUST NOT reach Redis.  The port MUST NOT depend on the SSH transport or a cloud provider's types, so another transport feeds the same port.  Output of a command marked secret MUST NOT be printed or kept; the provider-neutral remote operations MUST NOT build an output sink for it.  Every line, pod log lines too, MUST be redacted before it is printed or kept: URL credentials, `authkey=`, `token=`, `secret=`, `password=` and `key=` values, `Authorization:` header values, and AWS access key ids.

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

#### Scenario: Other credentials are redacted
- **WHEN** a node or a pod prints `TOKEN=xyz`, `Authorization: Bearer abc.def` or an AWS access key id
- **THEN** the printed line shows `TOKEN=***`, `Authorization: ***` or `***` in its place

### Requirement: Pod log lines use the same tagged path

Lines of a pod's logs that a command prints (`cassandra stress logs`) MUST go through the same tagged-line path, tagged with the pod name, each line one whole write.  They MUST NOT be kept in a per-source tail, so a long-lived process keeps no buffer per pod.

#### Scenario: A pod's log text
- **WHEN** a command prints the three-line log of pod `stress-job-1-abc12`
- **THEN** the console shows three lines, each starting with `[stress-job-1-abc12] `, and no tail is kept for that pod

### Requirement: An upload prints one notice

Each call that uploads a file or a directory to a node MUST print one notice tagged with the node.  A directory upload MUST print one notice, not one per file.  A file installed through a staging path MUST print one notice naming its final path and mode, and no notice for the staging upload.  A notice MUST NOT be kept in the node's tail.

#### Scenario: A directory upload
- **WHEN** a directory of 40 files is uploaded to control0
- **THEN** the console shows one line starting with `[control0] uploading`

#### Scenario: An installed file
- **WHEN** a file is installed at `/etc/x` with mode `0644` on db0
- **THEN** the console shows `[db0] installing /etc/x (mode 0644)` and no line naming the staging path, and db0's tail does not hold it

### Requirement: Each node's recent output is kept

The system MUST keep the last `Constants.NodeOutput.TAIL_LINES` output lines of each node, stdout and stderr in arrival order, in quiet mode and in normal mode.  It MUST NOT keep more than that per node.  A stderr entry MUST start with `stderr: `.  An entry longer than `Constants.NodeOutput.TAIL_ENTRY_CHARS` MUST be cut to that length and end with `…`; the console MUST still get the whole line.  A notice MUST NOT be kept; a mark (a retry, an attempt after the first) MUST be printed and kept, so a failure report shows which attempt a line came from.

#### Scenario: Only the last lines are kept
- **GIVEN** a node prints 100 lines
- **WHEN** its tail is read
- **THEN** it holds the last `Constants.NodeOutput.TAIL_LINES` lines

#### Scenario: A stderr line is marked in the tail
- **WHEN** a node writes `err` to stderr
- **THEN** its tail holds `stderr: err`

#### Scenario: A notice is not kept, a mark is
- **WHEN** a node's output gets the notice `installing /etc/x (mode 0644)`, then the mark `setup attempt 2`, then a script line
- **THEN** all three are printed, and the tail holds the mark and the script line

### Requirement: A tail belongs to one instance

Starting a node's tail MUST name the instance it belongs to.  A line from another instance of the same alias (an attempt left behind on an instance that ended) MUST be printed, but MUST NOT be kept in the tail, and MUST NOT be printed in quiet mode.  A line or a tail with no instance id MUST keep every line.

#### Scenario: A late line of a replaced instance
- **GIVEN** db0's tail belongs to its replacement instance
- **WHEN** an attempt still running on db0's old instance prints a line
- **THEN** the line is printed, and db0's tail does not hold it

### Requirement: Quiet mode hides node output

The system MUST offer a quiet mode in which node output lines, notices and marks are not printed.  In quiet mode every event MUST still be printed, and each node's tail MUST still be kept.  Quiet mode MUST last while any block that asked for it runs, on any thread, and MUST end when the last one returns or throws.  Quiet mode MUST end when the command that set it ends, also when it fails.

#### Scenario: Quiet hides lines and keeps events
- **GIVEN** quiet mode
- **WHEN** a node prints lines and then a node-ready event is emitted
- **THEN** the console shows the node-ready line and none of the node's lines

#### Scenario: Two overlapping quiet blocks
- **GIVEN** two threads enter quiet mode, and the first to enter leaves first
- **WHEN** a node prints a line while the second is still quiet
- **THEN** the line is not printed

#### Scenario: Quiet ends with its command
- **GIVEN** a command in quiet mode fails
- **WHEN** another command runs in the same process (the REPL)
- **THEN** its node output is printed

#### Scenario: A stalled console is reported
- **WHEN** a remote command ends and its output has not finished printing within the drain timeout
- **THEN** the node's output gets the notice `output still printing; the tail may be incomplete`
