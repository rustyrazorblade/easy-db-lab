# The Packer image AMI builds run in when the profile's SSH transport is `ssm`: the stock image plus
# the AWS Session Manager plugin, which Packer's `ssh_interface = "session_manager"` needs on PATH.
#
# Built locally on first use and tagged by a hash of this file, so editing it triggers a rebuild.
# The base image is pinned by digest and the plugin by version and SHA-256, so every rebuild gets
# the same bytes. To upgrade, change the tag and digest, or the version and both checksums,
# together.
FROM hashicorp/packer:full-1.16.1@sha256:b13c7d10eabe2f671ca11551048a582b8135866ebc52cd4d54d1011635c46845

ARG SESSION_MANAGER_PLUGIN_VERSION=1.2.835.0
ARG SESSION_MANAGER_PLUGIN_SHA256_AMD64=7c6dcad12518571cc7959a713e6a8ae1bdf6ed66fd9bee37dc189e39ca58ae03
ARG SESSION_MANAGER_PLUGIN_SHA256_ARM64=0add94c4c8b6ca63f26e44fd655d662b0f6455a268b5b9ebebee0f462214e928

# The plugin ships for Linux only as a glibc .deb. Alpine has no dpkg, so the binary is unpacked
# with ar and tar, and gcompat supplies the glibc symbols it links against. The architecture comes
# from uname rather than TARGETARCH, which the classic build API does not reliably set.
RUN set -eux; \
    case "$(uname -m)" in \
      x86_64) plugin_dir=ubuntu_64bit; plugin_sha256="${SESSION_MANAGER_PLUGIN_SHA256_AMD64}" ;; \
      aarch64) plugin_dir=ubuntu_arm64; plugin_sha256="${SESSION_MANAGER_PLUGIN_SHA256_ARM64}" ;; \
      *) echo "unsupported architecture: $(uname -m)" >&2; exit 1 ;; \
    esac; \
    apk add --no-cache gcompat; \
    apk add --no-cache --virtual .unpack binutils; \
    mkdir /tmp/session-manager-plugin; \
    cd /tmp/session-manager-plugin; \
    wget -q -O plugin.deb "https://s3.amazonaws.com/session-manager-downloads/plugin/${SESSION_MANAGER_PLUGIN_VERSION}/${plugin_dir}/session-manager-plugin.deb"; \
    echo "${plugin_sha256}  plugin.deb" | sha256sum -c -; \
    ar x plugin.deb; \
    tar -xf data.tar.*; \
    install -m 0755 usr/local/sessionmanagerplugin/bin/session-manager-plugin /usr/local/bin/session-manager-plugin; \
    cd /; \
    rm -rf /tmp/session-manager-plugin; \
    apk del .unpack; \
    session-manager-plugin --version
