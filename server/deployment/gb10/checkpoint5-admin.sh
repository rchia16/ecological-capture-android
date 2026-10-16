#!/bin/sh
set -eu
# Explicit operator action only. --local-code overlays three reviewed source files read-only for this worker.
[ "$#" -ge 4 ] || { echo 'checkpoint5-admin.sh IMAGE CONFIG_DIR STATE_DIR [--local-code] [--secrets-dir DIR] action [args...]' >&2; exit 2; }
image=$1
configuration=$2
state=$3
shift 3
local_code=false
if [ "$1" = --local-code ]; then
    local_code=true
    shift
    [ "$#" -ge 1 ] || exit 2
fi
secrets_directory=''
if [ "$1" = --secrets-dir ]; then
    [ "$#" -ge 3 ] || exit 2
    secrets_directory=$2
    shift 2
fi
action=$1
shift
for path in "$image" "$configuration" "$state"; do
    case "$path" in *[!a-zA-Z0-9_./-]* ) exit 2 ;; esac
    case "$path" in /* ) ;; * ) exit 2 ;; esac
done
[ -f "$image" ] && [ ! -L "$image" ] || exit 2
[ -d "$configuration" ] && [ ! -L "$configuration" ] || exit 2
[ -d "$state" ] && [ ! -L "$state" ] || exit 2
[ "$(stat -c %a "$state")" = 700 ] && [ "$(stat -c %u "$state")" = "$(id -u)" ] || exit 2
if [ -n "$secrets_directory" ]; then
    case "$secrets_directory" in /* ) ;; * ) exit 2 ;; esac
    case "$secrets_directory" in *[!a-zA-Z0-9_./-]* ) exit 2 ;; esac
    [ -d "$secrets_directory" ] && [ ! -L "$secrets_directory" ] || exit 2
    [ "$(stat -c %a "$secrets_directory")" = 700 ] && [ "$(stat -c %u "$secrets_directory")" = "$(id -u)" ] || exit 2
fi
case "$action" in
    provision|retire )
        [ -z "$secrets_directory" ] || exit 2
        set -- python -B /opt/ecocapture/server/deployment/gb10/checkpoint5_probe.py "$action" --credentials /gateway-state/checkpoint5-credentials.json "$@"
        ;;
    prepare|prepare-synthetic|status|discard|send|reconcile|auth-check|retry-creation )
        set -- python -B -m ecocapture_gateway.forward_cli --config /gateway-config/gateway.json "$action" "$@"
        ;;
    * ) exit 2 ;;
esac
umask 077
set -- --bind "$configuration:/gateway-config:ro" --bind "$state:/gateway-state:rw" "$image" "$@"
if [ -n "$secrets_directory" ]; then
    set -- --bind "$secrets_directory:/gateway-secrets:ro" "$@"
fi
if [ "$local_code" = true ]; then
    script_directory=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
    module_directory=$(CDPATH= cd -- "$script_directory/../../ecocapture_gateway" && pwd)
    case "$module_directory" in *[!a-zA-Z0-9_./-]* ) exit 2 ;; esac
    for module in filesender_adapter forward_cli forwarding; do
        source_file="$module_directory/$module.py"
        [ -f "$source_file" ] && [ ! -L "$source_file" ] || exit 2
        set -- --bind "$source_file:/opt/ecocapture/server/ecocapture_gateway/$module.py:ro" "$@"
    done
fi
exec apptainer exec --containall --cleanenv --no-mount hostfs,bind-paths "$@"
