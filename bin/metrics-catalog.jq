# Compacts metric series into the metrics-catalog.json `series` format.
#
# Input: an array of {name, labels} objects, one per series, where labels maps each label key to a
# single string value. Output: one {name, labels} entry per distinct metric name, sorted by name,
# whose labels maps each label key (sorted) to the sorted distinct values seen for it, keeping at
# most $cap values per key.
#
# Per-pod identity labels are dropped: they carry nothing for dashboards or METRICS.md and would
# make the catalog grow with the number of pods.
#
# Shared by bin/export-workload-metrics and any offline conversion of a committed catalog, so the
# two cannot diverge:
#   jq -L bin 'include "metrics-catalog"; ... | compact_series(20)'

def identity_labels: ["__name__", "instance", "k8s_pod_name", "k8s_pod_uid", "service_instance_id"];

def compact_series($cap):
  group_by(.name)
  | map({
      name: .[0].name,
      labels: (
        [.[].labels | to_entries[] | select(.key | IN(identity_labels[]) | not)]
        | group_by(.key)
        | map({key: .[0].key, value: ([.[].value] | unique | .[:$cap])})
        | from_entries
      )
    });
