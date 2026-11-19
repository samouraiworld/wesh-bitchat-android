#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
project_root="$(cd "$script_dir/../.." && pwd)"
image_name="wesh-android-store-assets:python3.12.12-pillow13.0.0"

if ! command -v docker >/dev/null 2>&1; then
  echo "error: Docker is required for canonical store-asset rendering" >&2
  exit 1
fi
if [[ "$#" -eq 0 ]]; then
  echo "usage: run_in_container.sh SCRIPT [ARG ...]" >&2
  exit 2
fi

docker build \
  --platform linux/amd64 \
  --file "$script_dir/Dockerfile" \
  --tag "$image_name" \
  "$project_root"

docker run \
  --rm \
  --platform linux/amd64 \
  --network none \
  --user "$(id -u):$(id -g)" \
  --env HOME=/tmp/store-assets-home \
  --volume "$project_root:/workspace" \
  "$image_name" \
  "$@"
