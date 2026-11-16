# syntax=docker/dockerfile:1
#
# Drop-in replacement for the Docker Official `cassandra` image, built from a
# tarball this repo produced from an arbitrary Apache Cassandra git ref.
#
# This reproduces the Docker Official Image layout (CASSANDRA_HOME=/opt/cassandra,
# CASSANDRA_CONF symlink, /var/lib/cassandra volume, the standard exposed ports,
# gosu step-down, jemalloc preload) but, instead of downloading and GPG-verifying
# a *released* tarball, it COPYs in the from-source tarball passed as a build arg.
# See .github/workflows/build-cassandra-ref.yml and openspec change
# cassandra-branch-publish for the rationale (design decision D0).
#
# Build args:
#   BASE_IMAGE     runtime JRE base image (default eclipse-temurin:17-jre-noble).
#                  The workflow overrides this per resolved Cassandra version.
#                  Pinned to a specific Ubuntu LTS variant (never the floating
#                  `-jre` tag) so the apt-provided python3 stays inside cqlsh's
#                  supported Python range — the floating tag drifted to Python
#                  3.14 and broke cqlsh (issue #764). noble ships Python 3.12,
#                  which is in the 3.6-3.13 range cqlsh 5.0+ supports.
#   TARBALL        path (relative to build context) to the
#                  apache-cassandra-<version>-bin.tar.gz to inject.
#
# OCI provenance build args (see the LABEL block below and issue #767). The
# workflow resolves each of these and passes them so `docker inspect` traces the
# image back to the exact Cassandra source commit:
#   OCI_REVISION   git SHA of the built Cassandra source ref.
#   OCI_SOURCE     source repo URL (https://github.com/<repo>).
#   OCI_VERSION    resolved Cassandra base.version (e.g. 6.0-alpha2).
#   OCI_CREATED    RFC-3339 build timestamp.
#   OCI_REF_NAME   the source ref (e.g. cassandra-6.0).

ARG BASE_IMAGE=eclipse-temurin:17-jre-noble

FROM ${BASE_IMAGE}

# explicitly set user/group IDs (matches the Docker Official image)
RUN set -eux; \
	groupadd -r cassandra --gid=999; \
	useradd -r -g cassandra --uid=999 cassandra

# Runtime dependencies, mirroring the Docker Official `cassandra` image:
#   libjemalloc2 - silences the "jemalloc could not be preloaded" warning
#   procps       - "free" is used by cassandra-env.sh
#   python3      - cqlsh needs a python interpreter
#   iproute2     - "ip" is used by docker-entrypoint.sh to discover the node IP
#   numactl      - Cassandra uses numactl automatically when present
#   gosu         - step-down from root in the entrypoint
# eclipse-temurin images are Ubuntu/Debian-based, so apt is available.
RUN set -eux; \
	apt-get update; \
	apt-get install -y --no-install-recommends \
		libjemalloc2 \
		procps \
		python3 \
		iproute2 \
		numactl \
		gosu \
	; \
	rm -rf /var/lib/apt/lists/*; \
	gosu --version; \
	gosu nobody true; \
# https://issues.apache.org/jira/browse/CASSANDRA-15767 — bin/cassandra only
# looks for libjemalloc.so / libjemalloc.so.1, not the packaged .so.2
	libjemalloc="$(readlink -e /usr/lib/*/libjemalloc.so.2)"; \
	ln -sT "$libjemalloc" /usr/local/lib/libjemalloc.so; \
	ldconfig

ENV CASSANDRA_HOME=/opt/cassandra
ENV CASSANDRA_CONF=/etc/cassandra
ENV PATH=$CASSANDRA_HOME/bin:$PATH

# Inject the from-source tarball instead of downloading + GPG-verifying a release.
ARG TARBALL
COPY ${TARBALL} /tmp/cassandra-bin.tgz

RUN set -eux; \
	mkdir -p "$CASSANDRA_HOME"; \
	tar --extract --file /tmp/cassandra-bin.tgz --directory "$CASSANDRA_HOME" --strip-components 1; \
	rm /tmp/cassandra-bin.tgz; \
	\
	[ ! -e "$CASSANDRA_CONF" ]; \
	mv "$CASSANDRA_HOME/conf" "$CASSANDRA_CONF"; \
	ln -sT "$CASSANDRA_CONF" "$CASSANDRA_HOME/conf"; \
	\
	mkdir -p "$CASSANDRA_CONF" /var/lib/cassandra /var/log/cassandra; \
	chown -R cassandra:cassandra "$CASSANDRA_CONF" /var/lib/cassandra /var/log/cassandra; \
	chmod 1777 "$CASSANDRA_CONF" /var/lib/cassandra /var/log/cassandra; \
	chmod -R a+rwX "$CASSANDRA_CONF"; \
	ln -sT /var/lib/cassandra "$CASSANDRA_HOME/data"; \
	ln -sT /var/log/cassandra "$CASSANDRA_HOME/logs"; \
	\
# smoke test that the binary runs under the chosen JRE
	cassandra -v

VOLUME /var/lib/cassandra

COPY docker-entrypoint.sh /usr/local/bin/
RUN chmod +x /usr/local/bin/docker-entrypoint.sh
ENTRYPOINT ["docker-entrypoint.sh"]

# Standard OCI image labels (issue #767). Placed last so the per-build values
# (revision, created timestamp) never invalidate the cache of the expensive apt
# and tarball-extraction layers above. The dynamic values are wired in from the
# build-cassandra-ref workflow via --build-arg.
ARG OCI_REVISION
ARG OCI_SOURCE=https://github.com/apache/cassandra
ARG OCI_VERSION
ARG OCI_CREATED
ARG OCI_REF_NAME
LABEL org.opencontainers.image.title="Apache Cassandra" \
      org.opencontainers.image.description="Apache Cassandra built from source by easy-db-lab" \
      org.opencontainers.image.url="https://cassandra.apache.org" \
      org.opencontainers.image.documentation="https://cassandra.apache.org/doc/latest/" \
      org.opencontainers.image.licenses="Apache-2.0" \
      org.opencontainers.image.source="${OCI_SOURCE}" \
      org.opencontainers.image.revision="${OCI_REVISION}" \
      org.opencontainers.image.version="${OCI_VERSION}" \
      org.opencontainers.image.created="${OCI_CREATED}" \
      org.opencontainers.image.ref.name="${OCI_REF_NAME}"

# 7000: intra-node communication
# 7001: TLS intra-node communication
# 7199: JMX
# 9042: CQL
# 9160: thrift service
EXPOSE 7000 7001 7199 9042 9160
CMD ["cassandra", "-f"]
