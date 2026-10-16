#!/bin/sh
set -eu
# Synthetic fixture administration through existing SIF; no rebuild or Funnel changes.
if [ "$#" -ne 4 ]; then
    echo 'Usage: hosted-admin.sh IMAGE CONFIG_DIR STATE_DIR provision|revoke-a|retire' >&2
    exit 2
fi
image=$1
configuration=$2
state=$3
action=$4
case "$action" in provision|revoke-a|retire ) ;; * ) exit 2 ;; esac
for path in "$image" "$configuration" "$state"; do
    case "$path" in *[!a-zA-Z0-9_./-]* ) exit 2 ;; esac
    case "$path" in /* ) ;; * ) exit 2 ;; esac
done
[ -f "$image" ] && [ ! -L "$image" ] || exit 2
[ -d "$configuration" ] && [ ! -L "$configuration" ] || exit 2
[ -d "$state" ] && [ ! -L "$state" ] || exit 2
[ "$(stat -c %a "$state")" = 700 ] && [ "$(stat -c %u "$state")" = "$(id -u)" ] || exit 2
script_directory=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
umask 077
exec apptainer exec --containall --cleanenv --no-mount hostfs,bind-paths \
    --bind "$configuration:/gateway-config:ro" --bind "$state:/gateway-state:rw" \
    --bind "$script_directory/hosted_probe.py:/hosted_probe.py:ro" \
    "$image" python -B /hosted_probe.py "$action" --credentials /gateway-state/hosted-probe-credentials.json
