#!/bin/bash
# Test packer provisioning scripts locally using Docker
set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
IMAGE_NAME="easy-db-lab-packer-test"

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

usage() {
    echo "Usage: $0 <script-path> [options]"
    echo ""
    echo "Test packer provisioning scripts locally in Docker"
    echo ""
    echo "Arguments:"
    echo "  script-path    Path to script relative to packer directory"
    echo "                 Example: cassandra/install/install_cassandra_easy_stress.sh"
    echo ""
    echo "Options:"
    echo "  --rebuild      Force rebuild of Docker test image"
    echo "  --shell        Drop into shell instead of running script (for debugging)"
    echo "  --keep         Keep container after script execution (for inspection)"
    echo ""
    echo "Examples:"
    echo "  $0 cassandra/install/install_cassandra_easy_stress.sh"
    echo "  $0 base/install/install_python.sh --rebuild"
    echo "  $0 cassandra/install/install_cassandra.sh --shell"
    exit 1
}

# Parse arguments
SCRIPT_PATH=""
REBUILD=false
SHELL_MODE=false
KEEP_CONTAINER=false

while [[ $# -gt 0 ]]; do
    case $1 in
        --rebuild)
            REBUILD=true
            shift
            ;;
        --shell)
            SHELL_MODE=true
            shift
            ;;
        --keep)
            KEEP_CONTAINER=true
            shift
            ;;
        -h|--help)
            usage
            ;;
        *)
            if [[ -z "$SCRIPT_PATH" ]]; then
                SCRIPT_PATH="$1"
            else
                echo -e "${RED}Error: Unexpected argument: $1${NC}"
                usage
            fi
            shift
            ;;
    esac
done

if [[ -z "$SCRIPT_PATH" ]] && [[ "$SHELL_MODE" == "false" ]]; then
    echo -e "${RED}Error: Script path is required${NC}"
    usage
fi

# Check if script exists
if [[ ! -z "$SCRIPT_PATH" ]] && [[ ! -f "$SCRIPT_DIR/$SCRIPT_PATH" ]]; then
    echo -e "${RED}Error: Script not found: $SCRIPT_DIR/$SCRIPT_PATH${NC}"
    exit 1
fi

# Build Docker image if needed
if [[ "$REBUILD" == "true" ]] || ! docker image inspect "$IMAGE_NAME" &> /dev/null; then
    echo -e "${YELLOW}Building test Docker image...${NC}"
    docker build -f "$SCRIPT_DIR/Dockerfile" -t "$IMAGE_NAME" "$SCRIPT_DIR"
    echo -e "${GREEN}Docker image built successfully${NC}"
fi

# Prepare docker run options
DOCKER_OPTS=(
    --rm
    -v "$SCRIPT_DIR:/packer:ro"
    -v /var/run/docker.sock:/var/run/docker.sock
    -w /home/ubuntu
    --user ubuntu
    -e EDL_CACHE_LIB=/packer/base/install/edl-cache-lib.sh
)

if [[ "$KEEP_CONTAINER" == "true" ]]; then
    DOCKER_OPTS=(${DOCKER_OPTS[@]/--rm/})
    DOCKER_OPTS+=(-it)
fi

# Run script or drop into shell
if [[ "$SHELL_MODE" == "true" ]]; then
    echo -e "${YELLOW}Dropping into shell in test environment...${NC}"
    echo -e "${YELLOW}Packer directory mounted at: /packer${NC}"
    docker run -it "${DOCKER_OPTS[@]}" "$IMAGE_NAME" /bin/bash
else
    echo -e "${YELLOW}Testing script: $SCRIPT_PATH${NC}"
    echo "=========================================="

    # Copy script to container and make executable, then run it
    docker run "${DOCKER_OPTS[@]}" "$IMAGE_NAME" /bin/bash -c "
        cp /packer/$SCRIPT_PATH /tmp/test-script.sh
        chmod +x /tmp/test-script.sh
        cd /tmp
        /tmp/test-script.sh
    "

    if [[ $? -eq 0 ]]; then
        echo "=========================================="
        echo -e "${GREEN}✓ Script completed successfully${NC}"
    else
        echo "=========================================="
        echo -e "${RED}✗ Script failed${NC}"
        exit 1
    fi
fi
