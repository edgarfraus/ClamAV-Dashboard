#!/bin/bash
# Builds the console image. The Dockerfile runs the Maven build itself, so no
# local JDK or Maven is needed. Override the tag with IMAGE=..., e.g.
#   IMAGE=ghcr.io/<you>/clamav-dashboard:latest ./build_docker.sh
set -euo pipefail
IMAGE="${IMAGE:-clamav-dashboard:latest}"
docker build -t "$IMAGE" .
echo "Built $IMAGE"
