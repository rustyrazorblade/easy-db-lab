

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
# and reports them over OTLP to the node's collector on 4317.
#
# It sits above the release and JDK derivation on purpose. The agent needs neither of them, so a
# jar name this file cannot parse costs the node its AxonOps agent but never its metrics.
EDL_OTEL_AGENT_JAR="${EDL_OTEL_AGENT_JAR:-/usr/local/otel/opentelemetry-javaagent.jar}"

# Custom JMX rules, on top of the agent's built-in experimental-cassandra target. Written by the
# CLI (`setup-instances`), not baked into the AMI, so a rule change needs no rebake.
EDL_OTEL_JMX_CONFIG="${EDL_OTEL_JMX_CONFIG:-/etc/easy-db-lab/cassandra-jmx-rules.yaml}"

if [ -f "$EDL_OTEL_AGENT_JAR" ]; then
    # service.instance.id is pinned to the hostname deliberately. The agent's default is a fresh
    # UUID for every JVM start, which mints a new Prometheus `instance` series on every restart.
    #
    # otel.jmx.discovery.delay defaults to 60000ms. That would hold the first metrics back to
    # roughly 61 seconds and delay picking up a newly created table by up to a minute.
    edl_add_jvm_extra_opt \
        "-javaagent:${EDL_OTEL_AGENT_JAR}" \
        "-Dotel.service.name=cassandra" \
        "-Dotel.resource.attributes=service.instance.id=$(hostname),node_role=db" \
        "-Dotel.exporter.otlp.endpoint=http://localhost:4317" \
        "-Dotel.metric.export.interval=5s" \
        "-Dotel.jmx.target.system=experimental-cassandra" \
        "-Dotel.jmx.discovery.delay=5000"

    if [ -f "$EDL_OTEL_JMX_CONFIG" ]; then
        edl_add_jvm_extra_opt "-Dotel.jmx.config=${EDL_OTEL_JMX_CONFIG}"
    else
        echo "WARNING: $EDL_OTEL_JMX_CONFIG is missing; this node reports only the built-in" >&2
        echo "WARNING: experimental-cassandra metrics - no per-table, thread-pool, dropped-message" >&2
        echo "WARNING: or p999 series. Run 'easy-db-lab setup-instances' to write it." >&2
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
