

#####  Begin easy-db-lab customizations ####

### This is automatically appended to the end of every cassandra.in.sh

# Agent selection helpers. Pure functions, unit tested in packer/cassandra/lib.
ECL_AGENTS_LIB="/usr/local/lib/edl-cassandra-agents.sh"
if [ -f "$ECL_AGENTS_LIB" ]; then
    # shellcheck disable=SC1090
    . "$ECL_AGENTS_LIB"
else
    echo "ERROR: $ECL_AGENTS_LIB is missing; Cassandra will start with NO AxonOps agent" >&2
fi

# edl_add_jvm_extra_opt <opt>... - append options to JVM_EXTRA_OPTS without clobbering what is
# already there. Every agent below adds to the same variable, so a plain assignment would silently
# drop whichever agent was configured first.
#
# JVM_EXTRA_OPTS, not JVM_OPTS: bin/nodetool sources this file and puts $JVM_OPTS on its own java
# command line, so an agent on JVM_OPTS starts again for every nodetool, sstableloader and
# cassandra-stress run. nodetool saves and restores JVM_OPTS around that, and discards
# JVM_EXTRA_OPTS entirely, which is exactly what an agent wants.
#
# This helper lives here rather than in edl-cassandra-agents.sh: that file holds pure functions
# with no side effects, and this one exports.
edl_add_jvm_extra_opt() {
    for _edl_opt in "$@"; do
        JVM_EXTRA_OPTS="${JVM_EXTRA_OPTS:+$JVM_EXTRA_OPTS }$_edl_opt"
    done
    export JVM_EXTRA_OPTS
}

# OpenTelemetry Java agent. Its JMX Metric Insight module reads Cassandra's own MBeans in-process
# and reports them over OTLP to the node's collector.
#
# It sits above the release and JDK derivation on purpose. The agent needs neither of them, so a
# jar name this file cannot parse costs the node its AxonOps agent but never its metrics.
EDL_OTEL_AGENT_JAR="${EDL_OTEL_AGENT_JAR:-/usr/local/otel/opentelemetry-javaagent.jar}"

# The complete JMX rule set, and the agent's only rule source: `otel.jmx.target.system` is
# deliberately not set. The built-in experimental-cassandra target is experimental upstream, so its
# metric names can move between agent releases and take every dashboard panel with them. Owning the
# rules pins those names to this repo.
#
# Written by the CLI (`setup-instances`), not baked into the AMI, so a rule change needs no rebake.
EDL_OTEL_JMX_CONFIG="${EDL_OTEL_JMX_CONFIG:-/etc/easy-db-lab/cassandra-jmx-rules.yaml}"

# Which build this node is actually running, as a metric label.
#
# A mixed-version A/B is the normal case on this rig: some nodes on a stock release, others on a
# locally built branch, and not always the same nodes. Without this every comparison dashboard has
# to hardcode host names, which is exactly what breaks when the split changes.
#
# The value is the target of /usr/local/cassandra/current - the directory name `cassandra use`
# selects, e.g. "5.0" or "5.0.9-rrb-j21-20260906-8ba1639-jdk21". It is read here, at JVM start,
# rather than pushed from the CLI, so `cassandra use` on a subset of hosts needs no re-push: the
# next restart of that node picks up its own new value and no other node is touched.
#
# ReleaseVersion off the StorageService MBean is NOT used for this. It reports 5.0.9 against
# 5.0.9-SNAPSHOT here, which separates stock from branch but collapses every branch build into one
# value - two different branches under test would be indistinguishable.
EDL_CASSANDRA_BUILD=$(basename "$(readlink -f /usr/local/cassandra/current 2>/dev/null)" 2>/dev/null)
if [ -z "$EDL_CASSANDRA_BUILD" ]; then
    EDL_CASSANDRA_BUILD="unknown"
    echo "WARNING: could not read the target of /usr/local/cassandra/current;" >&2
    echo "WARNING: this node reports cassandra_build=unknown and will not group with its variant." >&2
fi

if [ -f "$EDL_OTEL_AGENT_JAR" ]; then
    # service.instance.id is pinned to the hostname deliberately. The agent's default is a fresh
    # UUID for every JVM start, which mints a new Prometheus `instance` series on every restart.
    #
    # otel.jmx.discovery.delay defaults to 60000ms. That would hold the first metrics back to
    # roughly 61 seconds and delay picking up a newly created table by up to a minute.
    # Port 4318, not 4317. The agent's default OTLP protocol is http/protobuf, and the collector
    # serves that on 4318; 4317 is its gRPC port, where every export failed with "HttpExporter -
    # Failed to export". edl-profiling-reconcile posts to 4318 for the same reason.
    edl_add_jvm_extra_opt \
        "-javaagent:${EDL_OTEL_AGENT_JAR}" \
        "-Dotel.service.name=cassandra" \
        "-Dotel.resource.attributes=service.instance.id=$(hostname),node_role=db,cassandra_build=${EDL_CASSANDRA_BUILD}" \
        "-Dotel.exporter.otlp.endpoint=http://localhost:4318" \
        "-Dotel.metric.export.interval=5s" \
        "-Dotel.jmx.discovery.delay=5000"

    # JVM runtime telemetry beyond the default set. Both flags were established by running the
    # v2.31.1 agent against a throwaway JVM and reading what it emitted; none of the following is
    # guessable from the property names, so it is written down here.
    #
    # - emit-experimental-jfr-metrics is what produces an allocation metric. The obvious-looking
    #   `otel.instrumentation.runtime-telemetry-java17.enable-all` is a LEGACY name at 2.31.1 and
    #   does nothing at all: the java8 and java17 modules are merged into one
    #   io.opentelemetry.runtime-telemetry. Setting it looks right and ships inert.
    # - The JFR flag needs JDK 17+. Below that it is silently inert rather than an error, so a node
    #   on an older JDK loses these metrics without saying so. Every db node runs 17 or 21.
    # - jvm.memory.allocation is a HISTOGRAM, not a counter, with attribute arena=TLAB|Main. An
    #   allocation rate comes from its _sum, never a _total that does not exist.
    # - JFR recording costs the measured JVM a little continuously. This is a benchmarking rig, so
    #   that is a real if small cost, taken deliberately.
    #
    # emit-experimental-telemetry is the non-JFR half: file descriptors, buffer pools, system CPU
    # load. It works on any JDK and carries no allocation metric of its own. The two combine.
    edl_add_jvm_extra_opt \
        "-Dotel.instrumentation.runtime-telemetry.emit-experimental-jfr-metrics=true" \
        "-Dotel.instrumentation.runtime-telemetry.emit-experimental-telemetry=true"

    if [ -f "$EDL_OTEL_JMX_CONFIG" ]; then
        edl_add_jvm_extra_opt "-Dotel.jmx.config=${EDL_OTEL_JMX_CONFIG}"
    else
        echo "ERROR: $EDL_OTEL_JMX_CONFIG is missing. It holds every JMX rule, so this node will" >&2
        echo "ERROR: report NO Cassandra metrics - only JVM and host telemetry." >&2
        echo "ERROR: Run 'easy-db-lab setup-instances' to write it, then restart Cassandra." >&2
    fi
else
    echo "ERROR: $EDL_OTEL_AGENT_JAR is missing; this node will report NO Cassandra metrics." >&2
fi

# Extract Cassandra version from jar filename
ECL_CASSANDRA_JAR=$(find /usr/local/cassandra/current/ -name "apache-cassandra-[0-9]*.jar" | head -n 1)
if [ -z "$ECL_CASSANDRA_JAR" ]; then
    echo "ERROR: Could not determine Cassandra version" >&2
    exit 1
fi

# An unrecognised jar name leaves ECL_CASSANDRA_VERSION empty and says so. It must never carry the
# raw filename forward: that is what used to match no agent case and start Cassandra silently
# without an agent.
ECL_CASSANDRA_VERSION=""
if command -v edl_cassandra_version_from_jar >/dev/null 2>&1; then
    ECL_CASSANDRA_VERSION=$(edl_cassandra_version_from_jar "$ECL_CASSANDRA_JAR") || ECL_CASSANDRA_VERSION=""
fi
if [ -z "$ECL_CASSANDRA_VERSION" ]; then
    echo "ERROR: could not read a Cassandra X.Y release from $(basename "$ECL_CASSANDRA_JAR")." >&2
    echo "ERROR: no AxonOps agent can be selected. Metrics are unaffected: the OTel agent above" >&2
    echo "ERROR: needs neither the release nor the JDK." >&2
fi
export ECL_CASSANDRA_VERSION

# Extract Java version
ECL_JAVA_VERSION_OUTPUT=$(java -version 2>&1 | head -n 1)
if [ -n "$ECL_JAVA_VERSION_OUTPUT" ]; then
    # Extract version like "17" from the output string
    ECL_JAVA_VERSION=$(echo "$ECL_JAVA_VERSION_OUTPUT" | sed -E 's/.*version "([0-9]+)\..*".*/\1/')
    export ECL_JAVA_VERSION
else
    echo "ERROR: Could not determine Java version" >&2
    exit 1
fi

# AxonOps agent. AxonOps ships no agent for 5.1 and later, so most of the time this deliberately
# selects nothing - but it says which release it skipped rather than going quiet.
AXONOPS_AGENT=""
if [ -n "$ECL_CASSANDRA_VERSION" ]; then
    AXONOPS_AGENT=$(edl_axonops_agent_for "$ECL_CASSANDRA_VERSION" "$ECL_JAVA_VERSION") || AXONOPS_AGENT=""
    if [ -z "$AXONOPS_AGENT" ]; then
        echo "NOTE: no AxonOps agent is published for Cassandra ${ECL_CASSANDRA_VERSION} on JDK ${ECL_JAVA_VERSION}; skipping it" >&2
    fi
fi

# Configure JVM_EXTRA_OPTS with agent if applicable.
# The install dir is named after the full agent version (e.g. 5.0-agent-jdk11),
# but the jar inside is always axon-cassandra<X.Y>-agent.jar (no jdk suffix),
# so resolve it by glob rather than assuming the name matches the directory.
if [ -n "$AXONOPS_AGENT" ]; then
    ECL_AGENT_JAR=$(find "${EDL_AXONOPS_BASE:-/usr/share/axonops}/${AXONOPS_AGENT}/lib" -maxdepth 1 -name 'axon-cassandra*.jar' 2>/dev/null | head -n 1)
    if [ -f "$ECL_AGENT_JAR" ]; then
        edl_add_jvm_extra_opt "-javaagent:${ECL_AGENT_JAR}=/etc/axonops/axon-agent.yml"
    else
        echo "WARNING: AxonOps agent jar not found for $AXONOPS_AGENT" >&2
    fi
fi

# Set log directory based on user
if [ "$(whoami)" = "cassandra" ]; then
    CASSANDRA_LOG_DIR="/mnt/db1/cassandra/logs"
else
    CASSANDRA_LOG_DIR="$HOME/logs"
fi

mkdir -p "$CASSANDRA_LOG_DIR"

# set logging depending on JVM version
if [ "$ECL_JAVA_VERSION" = "17" ] || [ "$ECL_JAVA_VERSION" = "21" ]; then
    export JVM_OPTS="$JVM_OPTS -Xlog:gc=info:file=${CASSANDRA_LOG_DIR}/gc.log:time,uptime,pid,tid,level,tags:filecount=10,filesize=1M"
fi

# Reduce ring delay since we control the startup sequence
export JVM_OPTS="$JVM_OPTS -Dcassandra.ring_delay_ms=1"

# NOTE: nothing profiling-related belongs in this file any more.
#
# Cassandra used to load the Pyroscope Java agent here via -javaagent. It was removed because the
# agent takes one primary profiler.event fixed for the JVM's lifetime, so changing what was being
# profiled meant restarting Cassandra — which on a benchmarking rig discards exactly the page cache
# and compaction state the operator is trying to measure. It also meant a bad profiler string could
# abort JVM startup.
#
# Profiling is now attach-based and controlled at runtime by edl-profiling-reconcile. Do not
# reintroduce an agent here.
