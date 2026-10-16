"""Credential-free HTTPS health probe. TLS verification is always on; no redirects or HTTP fallback."""
import argparse
import http.client
import json
from pathlib import Path
import ssl
from urllib.parse import urlsplit


def probe(url, ca=None):
    parsed = urlsplit(url)
    if parsed.scheme != "https" or not parsed.hostname or parsed.username or parsed.password or parsed.query or parsed.fragment:
        return {"status": "INVALID_HTTPS_URL", "retryable": False}
    context = ssl.create_default_context(cafile=str(ca) if ca else None)
    connection = http.client.HTTPSConnection(parsed.hostname, parsed.port or 443, context=context, timeout=5)
    try:
        connection.request("GET", parsed.path or "/v1/health")
        response = connection.getresponse()
        data = response.read(4097)
        if response.status >= 500:
            return {"status": "GATEWAY_UNAVAILABLE", "retryable": True}
        if response.status != 200 or len(data) > 4096 or json.loads(data) != {"status": "ok", "transport": "mock"}:
            return {"status": "UNEXPECTED_GATEWAY_RESPONSE", "retryable": False}
        return {"status": "HTTPS_MOCK_GATEWAY_REACHABLE", "retryable": False}
    except ssl.SSLCertVerificationError:
        return {"status": "TLS_VERIFICATION_FAILED", "retryable": False}
    except (OSError, http.client.HTTPException):
        return {"status": "GATEWAY_UNAVAILABLE", "retryable": True}
    except (ValueError, UnicodeError):
        return {"status": "UNEXPECTED_GATEWAY_RESPONSE", "retryable": False}
    finally:
        connection.close()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("url")
    parser.add_argument("--ca", type=Path)
    args = parser.parse_args()
    result = probe(args.url, args.ca)
    print(json.dumps(result))
    return 0 if result["status"] == "HTTPS_MOCK_GATEWAY_REACHABLE" else 1


if __name__ == "__main__":
    raise SystemExit(main())
