#!/bin/sh
# shellcheck shell=sh
#
# Agent selection for Cassandra nodes, sourced by cassandra.in.sh at every Cassandra startup.
#
# It answers three questions, and nothing else:
#   - which Cassandra release (X.Y) is this node about to start?
#   - which AxonOps agent, if any, belongs to that release?
#   - which MCAC/MAAC metrics agent, if any, belongs to that release?
#
# It lives in its own file, holding pure functions with no side effects, because the version
# derivation used to be an inline sed in cassandra.in.sh that only matched `X.Y.Z` jar names.
# Anything else - `6.0-alpha3-SNAPSHOT`, `5.1-rc1`, `7.0-SNAPSHOT`, every branch build that
# `cassandra install --branch` produces - fell through the sed unchanged, matched no agent case,
# and started Cassandra with no metrics agent and not one line of output saying so. Both the
# parsing and the version -> agent mapping are unit tested (edl-cassandra-agents.test.sh).
#
# POSIX sh only: Cassandra's bin/cassandra is a /bin/sh script, so on the nodes this is sourced by
# dash. No [[ ]], no =~, no BASH_REMATCH, no local. Variables are _edl_-prefixed instead of local
# so a caller's names are never clobbered.
#
# Installed to /usr/local/lib/edl-cassandra-agents.sh by install_cassandra.sh.

# Where install_maac.sh puts the per-release MCAC/MAAC agent jars. Overridable for tests.
EDL_MAAC_BASE="${EDL_MAAC_BASE:-/opt/management-api}"

# Cassandra releases we install an MCAC/MAAC agent for. A release outside this list gets a warning
# naming it, never silence - see edl_maac_agent_jar_for.
EDL_MAAC_VERSIONS="${EDL_MAAC_VERSIONS:-4.0 4.1 5.0 6.0 7.0}"

# Where install_axon.sh puts the per-release AxonOps agents. Overridable for tests.
EDL_AXONOPS_BASE="${EDL_AXONOPS_BASE:-/usr/share/axonops}"

# edl_is_digits <string> - true when the string is one or more digits and nothing else.
edl_is_digits() {
    case "$1" in
        ''|*[!0-9]*) return 1 ;;
        *) return 0 ;;
    esac
}

# edl_cassandra_version_from_jar <path-or-name-of-apache-cassandra-jar>
#
# Prints the release as X.Y. Returns 1 without printing anything when the name is not an
# apache-cassandra jar with a major and a minor - the caller must treat that as an error rather
# than carry the unparsed text forward.
#
# Handles every shape the node actually holds:
#   apache-cassandra-3.11.19.jar             -> 3.11
#   apache-cassandra-5.0.4.jar               -> 5.0
#   apache-cassandra-6.0.jar                 -> 6.0
#   apache-cassandra-5.1-rc1.jar             -> 5.1
#   apache-cassandra-6.0-beta1.jar           -> 6.0
#   apache-cassandra-6.0-alpha3-SNAPSHOT.jar -> 6.0
#   apache-cassandra-7.0-SNAPSHOT.jar        -> 7.0
edl_cassandra_version_from_jar() {
    _edl_jar="${1##*/}"

    # Reject the sibling jars in the same lib directory (apache-cassandra-thrift-*,
    # apache-cassandra-clientutil-*) and anything not named for a release at all.
    case "$_edl_jar" in
        apache-cassandra-[0-9]*.jar) ;;
        *) return 1 ;;
    esac

    _edl_rest="${_edl_jar#apache-cassandra-}"
    _edl_rest="${_edl_rest%.jar}"

    _edl_major="${_edl_rest%%.*}"
    edl_is_digits "$_edl_major" || return 1

    # A major with no minor (apache-cassandra-6.jar) is not a release this can map to an agent.
    case "$_edl_rest" in
        *.*) ;;
        *) return 1 ;;
    esac

    # The minor runs to the next '.' (5.0.4), the next '-' (6.0-alpha3-SNAPSHOT), or the end (6.0).
    _edl_rest="${_edl_rest#*.}"
    _edl_minor="${_edl_rest%%.*}"
    _edl_minor="${_edl_minor%%-*}"
    edl_is_digits "$_edl_minor" || return 1

    printf '%s.%s\n' "$_edl_major" "$_edl_minor"
}

# edl_maac_agent_jar_for <X.Y>
#
# Prints the MCAC/MAAC agent jar path for that release. Returns 1 without printing when no agent
# is installed for it, so the caller can say which release it is skipping.
edl_maac_agent_jar_for() {
    for _edl_supported in $EDL_MAAC_VERSIONS; do
        if [ "$1" = "$_edl_supported" ]; then
            printf '%s/%s/datastax-mgmtapi-agent.jar\n' "$EDL_MAAC_BASE" "$1"
            return 0
        fi
    done

    return 1
}

# edl_axonops_agent_for <X.Y> <java-major-version>
#
# Prints the AxonOps agent install directory name for that release and JDK. Returns 1 without
# printing when AxonOps ships no agent for the combination.
edl_axonops_agent_for() {
    case "$1" in
        "3.0"|"3.11")
            printf '%s-agent\n' "$1"
            ;;
        "4.0"|"4.1")
            if [ "$2" = "8" ] || [ "$2" = "1.8" ]; then
                printf '%s-agent-jdk8\n' "$1"
            else
                printf '%s-agent\n' "$1"
            fi
            ;;
        "5.0")
            case "$2" in
                11|17) printf '5.0-agent-jdk%s\n' "$2" ;;
                *) return 1 ;;
            esac
            ;;
        *)
            return 1
            ;;
    esac

    return 0
}
