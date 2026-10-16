#!/bin/sh
set -eu
# No remote commands; execute on GB10 only after reviewing the bundle.
# Usage: sh run.sh IMAGE CONFIG_DIR STATE_DIR [serve|cleanup|revoke ...]
if [ "$#" -lt 3 ]; then
    echo 'Usage: run.sh IMAGE CONFIG_DIR STATE_DIR [action ...]' >&2
    exit 2
fi
image=$1
configuration=$2
state=$3
shift 3
if [ "$#" -eq 0 ]; then set -- serve; fi
for path in "$image" "$configuration" "$state"; do
    case "$path" in
        *[!a-zA-Z0-9_./-]* ) echo 'Use paths without spaces or bind separators.' >&2; exit 2 ;;
    esac
    case "$path" in /* ) ;; * ) echo 'Use absolute paths.' >&2; exit 2 ;; esac
done
for directory in "$configuration" "$state"; do
    [ -d "$directory" ] && [ ! -L "$directory" ] || exit 2
done
[ "$(stat -c %a "$state")" = 700 ] || { echo 'State directory must have mode 0700.' >&2; exit 2; }
[ "$(stat -c %u "$state")" = "$(id -u)" ] || { echo 'State must belong to the running account.' >&2; exit 2; }
[ -f "$configuration/gateway.json" ] && [ ! -L "$configuration/gateway.json" ] || exit 2
[ -f "$image" ] && [ ! -L "$image" ] || exit 2
# Contain home/tmp/default binds, but share host networking for loopback Tailscale.
# No --net, --nv, writable overlay, host home bind, password or FileSender key.
umask 077
exec apptainer run --containall --cleanenv --no-mount hostfs,bind-paths \
    --bind "$configuration:/gateway-config:ro" \
    --bind "$state:/gateway-state:rw" "$image" "$@"
