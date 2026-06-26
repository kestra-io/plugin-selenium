#!/bin/bash
set -e

docker compose -f docker-compose-ci.yml up -d

# Poll until the Grid reports ready, timeout after 60 seconds.
TIMEOUT=60
ELAPSED=0
while true; do
    if [ "$ELAPSED" -ge "$TIMEOUT" ]; then
        echo "Selenium Grid did not become ready within ${TIMEOUT}s" >&2
        exit 1
    fi
    if curl -sf http://localhost:4444/wd/hub/status | grep -q '"ready": *true'; then
        break
    fi
    sleep 2
    ELAPSED=$((ELAPSED + 2))
done

echo "SELENIUM_GRID_URL=http://localhost:4444" >> "$GITHUB_ENV"
