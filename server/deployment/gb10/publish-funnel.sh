#!/bin/sh
set -eu
# An explicit future publication action, never called by build/run/package tooling.
if [ "$#" -ne 1 ] || [ "$1" != '--publish-synthetic-gateway' ]; then
    echo 'Review existing tailscale serve/funnel status first. To publish: --publish-synthetic-gateway' >&2
    exit 2
fi
exec tailscale funnel --bg --https=443 http://127.0.0.1:18080
