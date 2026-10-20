"""Outbound-only GB10 worker. ADC and age identity are supplied outside the image."""
import argparse
import json
import os
from pathlib import Path
import time
from urllib.parse import urlencode, urlparse

from .receiver import Receiver


class Cloud:
    def __init__(self, endpoint, audience, bucket):
        import google.auth.transport.requests
        from google.oauth2 import id_token
        from google.cloud import storage
        parsed = urlparse(endpoint)
        if parsed.scheme != "https" or parsed.query or parsed.fragment or parsed.username:
            raise ValueError("HTTPS_ENDPOINT_REQUIRED")
        self.endpoint = endpoint.rstrip("/")
        self.credentials = id_token.fetch_id_token_credentials(audience, request=google.auth.transport.requests.Request())
        self.session = google.auth.transport.requests.AuthorizedSession(self.credentials)
        self.bucket = storage.Client().bucket(bucket)

    def call(self, method, route, body=None):
        response = self.session.request(method, self.endpoint + route, json=body, timeout=600, allow_redirects=False)
        if response.status_code != 200:
            raise OSError("CLOUD_REQUEST_FAILED")
        return response.json()

    def pending(self, study):
        cursor = None
        while True:
            query = {"study": study}
            if cursor: query["after"] = cursor
            result = self.call("GET", "/pending?" + urlencode(query))
            yield from result["items"]
            cursor = result["next"]
            if not cursor: break

    def download(self, row, destination):
        blob = self.bucket.blob(row["object_path"], generation=int(row["generation"]))
        with destination.open("xb") as stream:
            blob.download_to_file(stream, if_generation_match=int(row["generation"]), timeout=180)
            stream.flush(); os.fsync(stream.fileno())

    def receipt(self, body):
        return self.call("POST", "/receipt", body)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config", required=True, type=Path)
    parser.add_argument("--once", action="store_true")
    args = parser.parse_args()
    config = json.loads(args.config.read_text(encoding="utf-8"))
    if os.name != "nt": os.umask(0o077)
    receiver = Receiver(config["root"], config["study_id"], config["age_tool"], config["identity"],
                        Cloud(config["endpoint"], config["audience"], config["bucket"]))
    try:
        while True:
            try:
                receiver.once()
                print('{"status":"RECEIVER_CHECK_COMPLETE"}', flush=True)
            except Exception:
                print('{"status":"RECEIVER_CHECK_FAILED"}', flush=True)
                if args.once: return 1
            if args.once: return 0
            time.sleep(60)
    finally:
        receiver.close()


if __name__ == "__main__":
    raise SystemExit(main())
