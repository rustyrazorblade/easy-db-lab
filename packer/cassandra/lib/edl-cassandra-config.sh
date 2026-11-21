# edl-cassandra-config.sh - the one cassandra.yaml merge, shared by the VM path and the
# cassandra-pods kit.
#
# patch-config (on a db node) and the kit's render step (in a pod's cassandra-render init container)
# both build cassandra.yaml with edl_merge_cassandra_yaml and nothing else, so a version's config
# differs between the two paths only in what their patches set: addresses, ports, paths and the
# snitch. Neither path may merge cassandra.yaml any other way.
#
# POSIX sh: the render step runs it under /bin/sh, which is dash on Debian-based images. The test,
# edl-cassandra-config.test.sh, sources it through a real /bin/sh for that reason.
#
# Installed on the AMI as /usr/local/lib/edl-cassandra-config.sh; shipped to pods in the kit's
# cassandra-pods-config ConfigMap, copied from this file at build time.

# edl_merge_cassandra_yaml <orig> <out> <patch>... - merge each patch, in order, onto <orig> with
# yq's `. *= load(...)` (a later patch wins over an earlier one) and write the result to <out>.
# <orig> is never changed. On any failure it returns non-zero and leaves no <out>, so a caller can
# never start Cassandra on a half-written config.
edl_merge_cassandra_yaml() {
    if [ "$#" -lt 3 ]; then
        echo "ERROR: usage: edl_merge_cassandra_yaml <orig> <out> <patch>..." >&2
        return 1
    fi
    _edl_orig=$1
    _edl_out=$2
    shift 2

    if [ ! -f "$_edl_orig" ]; then
        echo "ERROR: original cassandra.yaml not found: $_edl_orig" >&2
        return 1
    fi

    _edl_tmp="${_edl_out}.edl-merge.$$"
    if ! cp "$_edl_orig" "$_edl_tmp"; then
        rm -f "$_edl_tmp"
        return 1
    fi

    for _edl_patch in "$@"; do
        if [ ! -f "$_edl_patch" ]; then
            echo "ERROR: patch file not found: $_edl_patch" >&2
            rm -f "$_edl_tmp"
            return 1
        fi
        if ! EDL_MERGE_PATCH="$_edl_patch" yq -i '. *= load(env(EDL_MERGE_PATCH))' "$_edl_tmp"; then
            echo "ERROR: yq failed to merge $_edl_patch. Check its YAML syntax." >&2
            rm -f "$_edl_tmp"
            return 1
        fi
    done

    if ! mv -f "$_edl_tmp" "$_edl_out"; then
        rm -f "$_edl_tmp"
        return 1
    fi
}
