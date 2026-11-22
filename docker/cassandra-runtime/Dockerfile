# The JRE the cassandra-pods kit runs a Cassandra release tarball on. It holds no Cassandra and no
# easy-db-lab script: the pod's cassandra-fetch init container downloads and checks the tarball,
# and the scripts arrive in the kit's cassandra-pods-config ConfigMap. So one image per JDK runs
# every release, branch build and catalog build.
#
# Published by .github/workflows/publish-container.yml for linux/amd64 and linux/arm64 as
# ghcr.io/rustyrazorblade/cassandra-runtime:<RUNTIME_REVISION>-jre<JAVA>, one tag per JDK of
# cassandra/release-lines.yaml. A change here bumps RUNTIME_REVISION and
# Constants.Kit.CassandraPods.RUNTIME_IMAGE_REVISION together (CassandraRuntimeImageTest), so a
# published tag is never overwritten.
ARG JAVA=17
# Pinned to Ubuntu 22.04 (jammy), not the moving `-jre` tag: a newer Ubuntu's python3 is newer than
# older tarballs' cqlsh accepts (26.04 has 3.14 and noble 3.12; 5.0.8's cqlsh takes 3.8-3.11). Jammy's
# python3 3.10 is inside every 4.x and 5.0 cqlsh range.
FROM eclipse-temurin:${JAVA}-jre-jammy

ARG RUNTIME_REVISION=1
# yq at the version packer/base/install/install_yq.sh installs on the AMI, so the pods' config merge
# (edl_merge_cassandra_yaml) runs the same yq as the VM path. The checksums are the release's.
ARG YQ_VERSION=v4.41.1
ARG YQ_SHA256_AMD64=ce0d5a61c256a463fd32f67f133e0c2948bc2cf77d44c42ff335a40e6bef34bf
ARG YQ_SHA256_ARM64=066aa930d74e39a25447b1900d8cbb3e1c7df72cd75bc203bc6ae5ee577a5b4a
ARG TARGETARCH

# curl fetches the tarball, python3 runs cqlsh, procps gives nodetool and the scripts ps and pgrep.
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl python3 procps \
 && rm -rf /var/lib/apt/lists/* \
 && case "${TARGETARCH}" in \
      amd64) yq_sha256="${YQ_SHA256_AMD64}" ;; \
      arm64) yq_sha256="${YQ_SHA256_ARM64}" ;; \
      *) echo "No yq checksum for architecture '${TARGETARCH}'" >&2; exit 1 ;; \
    esac \
 && curl -fsSL -o /usr/local/bin/yq \
      "https://github.com/mikefarah/yq/releases/download/${YQ_VERSION}/yq_linux_${TARGETARCH}" \
 && echo "${yq_sha256}  /usr/local/bin/yq" | sha256sum -c - \
 && chmod 0755 /usr/local/bin/yq

# uid 999, as packer/cassandra/install/install_cassandra.sh makes the cassandra user on the AMI;
# the pod sets fsGroup 999, so the claim is writable. The temurin base already has uid 1000.
RUN groupadd --system --gid 999 cassandra \
 && useradd --system --uid 999 --gid 999 --home-dir /var/lib/cassandra --no-create-home cassandra
USER 999:999

LABEL org.opencontainers.image.source="https://github.com/rustyrazorblade/easy-db-lab" \
      org.opencontainers.image.description="JRE for easy-db-lab's cassandra-pods kit (revision ${RUNTIME_REVISION})"
