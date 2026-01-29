FROM node:20

ARG TZ
ENV TZ="$TZ"

# Install basic development tools and iptables/ipset
RUN apt-get update && apt-get install -y --no-install-recommends \
    less \
    git \
    procps \
    sudo \
    fzf \
    zsh \
    man-db \
    unzip \
    gnupg2 \
    gh \
    iptables \
    ipset \
    iproute2 \
    dnsutils \
    aggregate \
    jq \
    nano \
    vim \
    curl \
    zip \
    python3 \
    python3-pip \
    python3-venv \
    && apt-get clean && rm -rf /var/lib/apt/lists/*

# Install MkDocs and Cinder theme for documentation
COPY docs/requirements.txt /tmp/docs-requirements.txt
RUN pip3 install --break-system-packages -r /tmp/docs-requirements.txt

# Ensure default node user has access to /usr/local/share
RUN mkdir -p /usr/local/share/npm-global && \
    chown -R node:node /usr/local/share

ARG USERNAME=node

# Persist bash history.
RUN SNIPPET="export PROMPT_COMMAND='history -a' && export HISTFILE=/commandhistory/.bash_history" \
    && mkdir /commandhistory \
    && touch /commandhistory/.bash_history \
    && chown -R $USERNAME /commandhistory

# Set `DEVCONTAINER` environment variable to help with orientation
ENV DEVCONTAINER=true

# Create workspace directory
RUN mkdir -p /workspace && \
    chown -R node:node /workspace

WORKDIR /workspace

ARG GIT_DELTA_VERSION=0.18.2
RUN ARCH=$(dpkg --print-architecture) && \
    wget "https://github.com/dandavison/delta/releases/download/${GIT_DELTA_VERSION}/git-delta_${GIT_DELTA_VERSION}_${ARCH}.deb" && \
    sudo dpkg -i "git-delta_${GIT_DELTA_VERSION}_${ARCH}.deb" && \
    rm "git-delta_${GIT_DELTA_VERSION}_${ARCH}.deb"

# Allow node user to run sudo without password (required for docker-in-docker)
RUN echo "node ALL=(ALL) NOPASSWD:ALL" >> /etc/sudoers.d/node

# Set up non-root user
USER node

# Install global packages
ENV NPM_CONFIG_PREFIX=/usr/local/share/npm-global
ENV PATH=$PATH:/usr/local/share/npm-global/bin

# Set the default shell to zsh rather than sh
ENV SHELL=/bin/zsh

# Set the default editor and visual
ENV EDITOR=vim
ENV VISUAL=vim

# Default powerline10k theme
ARG ZSH_IN_DOCKER_VERSION=1.2.0
RUN sh -c "$(wget -O- https://github.com/deluan/zsh-in-docker/releases/download/v${ZSH_IN_DOCKER_VERSION}/zsh-in-docker.sh)" -- \
    -p git \
    -p fzf \
    -a "source /usr/share/doc/fzf/examples/key-bindings.zsh" \
    -a "source /usr/share/doc/fzf/examples/completion.zsh" \
    -a "export PROMPT_COMMAND='history -a' && export HISTFILE=/commandhistory/.bash_history" \
    -x

# Install ast-grep
RUN npm install -g @ast-grep/cli

# Install Claude Code using native installer
RUN curl -fsSL https://claude.ai/install.sh | bash
ENV PATH="/home/node/.local/bin:$PATH"

# Install SDKMAN and Java versions
# Java 21 is the default for the project, Java 11 is needed for cassandra-analytics
ENV SDKMAN_DIR="/home/node/.sdkman"
RUN curl -s "https://get.sdkman.io" | bash
RUN bash -c "source $SDKMAN_DIR/bin/sdkman-init.sh && \
    sdk install java 21.0.5-tem && \
    sdk install java 11.0.25-tem && \
    sdk default java 21.0.5-tem"
ENV PATH="$SDKMAN_DIR/candidates/java/current/bin:$PATH"
ENV JAVA_HOME="$SDKMAN_DIR/candidates/java/current"
