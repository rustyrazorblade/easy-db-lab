# The Pyroscope Java agent as an image, so a pod gets the jar from an init container instead of
# a hostPath into the AMI. The jar is architecture-independent; the image is published for
# linux/amd64 and linux/arm64 by .github/workflows/publish-container.yml.
#
# Upgrading means changing PYROSCOPE_VERSION and PYROSCOPE_SHA256 together, and the image tag
# (Constants.Kit.Agents.PYROSCOPE_IMAGE) with them. The base images are pinned by digest, so a build
# of one version always has the same layers.
FROM alpine:3.22@sha256:49340b346798875b5592e5d420241f2f9cfba5f47e62ebb1446a1b34734e898f AS fetch
ARG PYROSCOPE_VERSION=2.3.0
ARG PYROSCOPE_SHA256=59d7d8b280ef1da91243835dbb6b2d02d754b7e951d5438e39ef1a50bed79159
RUN wget -q -O /pyroscope.jar \
      "https://github.com/grafana/pyroscope-java/releases/download/v${PYROSCOPE_VERSION}/pyroscope.jar" \
 && echo "${PYROSCOPE_SHA256}  /pyroscope.jar" | sha256sum -c - \
 && chmod 0644 /pyroscope.jar

FROM busybox:1.37.0@sha256:a7dbc319b1438115a207e11930dc4bb3e643dcb3ec0bdd694bb36cd8fad289ec
COPY --from=fetch /pyroscope.jar /pyroscope.jar
# Pod Security "restricted" requires a non-root init container; cp into an emptyDir works as any user.
USER 65532:65532
