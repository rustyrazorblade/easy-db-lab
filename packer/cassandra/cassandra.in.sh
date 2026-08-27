

#####  Begin easy-db-lab customizations ####

### This is automatically appended to the end of every cassandra.in.sh

# Extract Cassandra version from jar filename
ECL_CASSANDRA_JAR=$(find /usr/local/cassandra/current/ -name "apache-cassandra-[0-9]*.jar" | head -n 1)
if [ -n "$ECL_CASSANDRA_JAR" ]; then
    # Extract X.Y.Z from filename and then get X.Y
    ECL_CASSANDRA_VERSION=$(basename "$ECL_CASSANDRA_JAR" | sed -E 's/apache-cassandra-([0-9]+\.[0-9]+)\.[0-9]+\.jar/\1/')
    export ECL_CASSANDRA_VERSION
else
    echo "ERROR: Could not determine Cassandra version" >&2
    exit 1
fi

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

# Set AXONOPS_AGENT based on Cassandra and Java versions
AXONOPS_AGENT=""
case "$ECL_CASSANDRA_VERSION" in
    "3.0")
        AXONOPS_AGENT="3.0-agent"
        ;;
    "3.11")
        AXONOPS_AGENT="3.11-agent"
        ;;
    "4.0")
        if [ "$ECL_JAVA_VERSION" = "8" ] || [ "$ECL_JAVA_VERSION" = "1.8" ]; then
            AXONOPS_AGENT="4.0-agent-jdk8"
        else
            AXONOPS_AGENT="4.0-agent"
        fi
        ;;
    "4.1")
        if [ "$ECL_JAVA_VERSION" = "8" ] || [ "$ECL_JAVA_VERSION" = "1.8" ]; then
            AXONOPS_AGENT="4.1-agent-jdk8"
        else
            AXONOPS_AGENT="4.1-agent"
        fi
        ;;
    "5.0")
        if [ "$ECL_JAVA_VERSION" = "11" ]; then
            AXONOPS_AGENT="5.0-agent-jdk11"
        elif [ "$ECL_JAVA_VERSION" = "17" ]; then
            AXONOPS_AGENT="5.0-agent-jdk17"
        fi
        ;;
    "5.1"|"6.0")
        # No agent for these versions
        AXONOPS_AGENT=""
        ;;
esac

# Configure JVM_EXTRA_OPTS with agent if applicable.
# The install dir is named after the full agent version (e.g. 5.0-agent-jdk11),
# but the jar inside is always axon-cassandra<X.Y>-agent.jar (no jdk suffix),
# so resolve it by glob rather than assuming the name matches the directory.
if [ -n "$AXONOPS_AGENT" ]; then
    ECL_AGENT_JAR=$(find "/usr/share/axonops/${AXONOPS_AGENT}/lib" -maxdepth 1 -name 'axon-cassandra*.jar' 2>/dev/null | head -n 1)
    if [ -f "$ECL_AGENT_JAR" ]; then
        export JVM_EXTRA_OPTS="-javaagent:${ECL_AGENT_JAR}=/etc/axonops/axon-agent.yml"
    else
        echo "WARNING: AxonOps agent jar not found for $AXONOPS_AGENT" >&2
    fi
fi

# MAAC (Management API for Apache Cassandra) metrics agent
# Exposes Cassandra metrics as Prometheus endpoint on port 9000
MAAC_AGENT_JAR=""
case "$ECL_CASSANDRA_VERSION" in
    "4.0")
        MAAC_AGENT_JAR="/opt/management-api/4.0/datastax-mgmtapi-agent.jar"
        ;;
    "4.1")
        MAAC_AGENT_JAR="/opt/management-api/4.1/datastax-mgmtapi-agent.jar"
        ;;
    "5.0")
        MAAC_AGENT_JAR="/opt/management-api/5.0/datastax-mgmtapi-agent.jar"
        ;;
esac

if [ -n "$MAAC_AGENT_JAR" ] && [ -f "$MAAC_AGENT_JAR" ]; then
    export MAAC_PATH="/opt/management-api"
    export POD_NAME=$(hostname)
    export JVM_OPTS="$JVM_OPTS -javaagent:${MAAC_AGENT_JAR}"
elif [ -n "$MAAC_AGENT_JAR" ]; then
    echo "WARNING: MAAC agent jar not found at $MAAC_AGENT_JAR" >&2
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
