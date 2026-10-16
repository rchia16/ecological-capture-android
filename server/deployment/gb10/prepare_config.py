"""Prepare synthetic GB10 configuration locally, without SSH, credentials or publication."""
import argparse
import json
from pathlib import Path
from container_runtime import load_config


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("funnel_hostname", help="Actual GB10 DNSName, without https:// or trailing dot")
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    data = {"storage": "/gateway-state/private", "allowed_hosts": [args.funnel_hostname],
            "max_chunk_bytes": 4194304, "staging_ttl_seconds": 86400}
    # Validate before creating anything; do not overwrite an existing config.
    import tempfile
    with tempfile.TemporaryDirectory() as tmp:
        probe = Path(tmp) / "config.json"
        probe.write_text(json.dumps(data), encoding="utf-8")
        load_config(probe)
    with args.output.open("x", encoding="utf-8", newline="\n") as file:
        json.dump(data, file, indent=2)
        file.write("\n")
    print('{"status":"SYNTHETIC_CONFIG_CREATED"}')


if __name__ == "__main__":
    main()
