#!/usr/bin/env bash
set -euo pipefail

RUNNER_DIR=/runner
TEMPLATE_DIR=/opt/actions-runner

go version

if [ ! -f "${RUNNER_DIR}/run.sh" ]; then
    echo "Initializing runner files..."
    cp -a "${TEMPLATE_DIR}/." "${RUNNER_DIR}/"
fi

if [ ! -f "${RUNNER_DIR}/.runner" ]; then
    echo
    echo "Runner has not been registered yet."
    echo "Run config.sh manually first."
    exit 1
fi

exec ./run.sh
