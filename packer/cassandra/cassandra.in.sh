

#####  Begin easy-db-lab customizations ####

### This is automatically appended to the end of every cassandra.in.sh

# Agent selection helpers. Pure functions, unit tested in packer/cassandra/lib.
ECL_AGENTS_LIB="/usr/local/lib/edl-cassandra-agents.sh"
if [ -f "$ECL_AGENTS_LIB" ]; then
    # shellcheck disable=SC1090
    . "$ECL_AGENTS_LIB"
else
    echo "ERROR: $ECL_AGENTS_LIB is missing; Cassandra will start with NO metrics agent" >&2
fi

# Extract Cassandra version from jar filename
ECL_CASSANDRA_JAR=$(find /usr/local/cassandra/current/ -name "apache-cassandra-[0-9]*.jar" | head -n 1)
if [ -z "$ECL_CASSANDRA_JAR" ]; then
    echo "ERROR: Could not determine Cassandra version" >&2
    exit 1
fi

# An unrecognised jar name leaves ECL_CASSANDRA_VERSION empty and says so. It must never carry the
# raw filename forward: that is what used to match no agent case and start Cassandra silently
# without a metrics agent.
ECL_CASSANDRA_VERSION=""
if command -v edl_cassandra_version_from_jar >/dev/null 2>&1; then
    ECL_CASSANDRA_VERSION=$(edl_cassandra_version_from_jar "$ECL_CASSANDRA_JAR") || ECL_CASSANDRA_VERSION=""
fi
if [ -z "$ECL_CASSANDRA_VERSION" ]; then
    echo "ERROR: could not read a Cassandra X.Y release from $(basename "$ECL_CASSANDRA_JAR")." >&2
    echo "ERROR: no agent can be selected, so this node will report NO Cassandra metrics." >&2
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
        export JVM_EXTRA_OPTS="-javaagent:${ECL_AGENT_JAR}=/etc/axonops/axon-agent.yml"
    else
        echo "WARNING: AxonOps agent jar not found for $AXONOPS_AGENT" >&2
    fi
fi

# MCAC/MAAC (Management API for Apache Cassandra) metrics agent.
# Exposes Cassandra metrics as a Prometheus endpoint on port 9000, which the node's OTel collector
# scrapes. Every path below reports what it did: a node with no metrics agent is a node whose
# Cassandra metrics never reach VictoriaMetrics, and that must not happen quietly.
MAAC_AGENT_JAR=""
if [ -n "$ECL_CASSANDRA_VERSION" ]; then
    MAAC_AGENT_JAR=$(edl_maac_agent_jar_for "$ECL_CASSANDRA_VERSION") || MAAC_AGENT_JAR=""
    if [ -z "$MAAC_AGENT_JAR" ]; then
        echo "WARNING: no MCAC metrics agent is installed for Cassandra ${ECL_CASSANDRA_VERSION};" >&2
        echo "WARNING: this node will report NO Cassandra metrics. Add it in packer/cassandra/install/install_maac.sh." >&2
    fi
fi

if [ -n "$MAAC_AGENT_JAR" ]; then
    if [ -f "$MAAC_AGENT_JAR" ]; then
        export MAAC_PATH="${EDL_MAAC_BASE:-/opt/management-api}"
        POD_NAME=$(hostname)
        export POD_NAME
        export JVM_OPTS="$JVM_OPTS -javaagent:${MAAC_AGENT_JAR}"

        # The agent's Prometheus endpoint is a Netty HTTP server, but netty-codec-http is
        # `provided` in the agent build and is not bundled in the agent jar. Cassandra 4.x shipped
        # a fat netty-all that happened to contain it; 5.0 and later ship granular netty modules
        # plus an empty netty-all aggregator that does not, so the endpoint dies with
        # NoClassDefFoundError io/netty/handler/codec/http/HttpServerCodec and the node reports
        # nothing. install-cassandra-version puts the matching jar in the release's lib directory;
        # check it is there rather than discovering it in a stack trace.
        ECL_CASSANDRA_LIB="${CASSANDRA_HOME:-/usr/local/cassandra/current}/lib"
        if ! ls "${ECL_CASSANDRA_LIB}"/netty-codec-http-*.jar >/dev/null 2>&1 \
           && [ -z "$(find "$ECL_CASSANDRA_LIB" -maxdepth 1 -name 'netty-all-*.jar' -size +1M 2>/dev/null)" ]; then
            echo "WARNING: no netty-codec-http jar in ${ECL_CASSANDRA_LIB};" >&2
            echo "WARNING: the MCAC metrics endpoint cannot start, so this node will report NO Cassandra metrics." >&2
        fi
    else
        echo "WARNING: MCAC metrics agent jar not found at $MAAC_AGENT_JAR;" >&2
        echo "WARNING: this node will report NO Cassandra metrics." >&2
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
