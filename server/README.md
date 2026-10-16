# Phase 3 gateway

Checkpoint 4 engineering acceptance passed on GB10/Funnel. [Checkpoint 5](deployment/gb10/CHECKPOINT5.md) adds an explicit synthetic-only operator archive/encrypt/FileSender path, off by default and prepared locally. HTTP completion remains mock unless a separately authorized worker completes the selected job. Live Checkpoint 5 FileSender execution and researcher round-trip are pending; the current hosted SIF/service has not been changed by the agent. Android still uses the Checkpoint 3 simulation; network integration comes later. Use harmless synthetic fixtures only. No path marks anything archived.

The [GB10 Apptainer/Funnel package](deployment/gb10/README.md) includes managed-service controls and investigator-supplied Checkpoint 4 acceptance. New Checkpoint 5 native build/selection and private integration remain separate future actions; the agent has made no SSH connection or deployment to `rchia@100.76.243.123`.

## Reproduce validation on Windows

From the repository root, with Python 3.11 or later:

```powershell
python -m venv server/.venv
& server/.venv/Scripts/python.exe -m pip install -r server/requirements.lock
$env:PYTHONPATH = (Resolve-Path server).Path
& server/.venv/Scripts/python.exe -B -W error::ResourceWarning -m unittest discover -s server/tests -v
& server/.venv/Scripts/python.exe -B server/tests/tls_smoke.py
```

The TLS smoke test starts an actual loopback server, creates disposable certificates and synthetic installation credentials in a temporary directory, verifies upload/replay/restart/revocation, and removes them on exit. It does not change the workstation CA store or contact FileSender. It uses Git's OpenSSL on Windows; override `ECOCAPTURE_TEST_OPENSSL` with an executable path if needed. On Linux use `.venv/bin/python`, `PYTHONPATH=server` and the system `openssl`. Repeat the filesystem symlink test on the eventual Linux host; this Windows machine lacks the privilege to create the real test symlink.

## Local administration

Copy `deployment/gateway.example.json` to an ignored `server/.state/config.json`; for a local test replace storage with an absolute, private test directory and hosts with `localhost` and `127.0.0.1`. Commands run with `PYTHONPATH=server`:

```powershell
& server/.venv/Scripts/python.exe -B -m ecocapture_gateway.cli --config server/.state/config.json serve --cert <test-certificate-path> --key <test-private-key-path>
& server/.venv/Scripts/python.exe -B -m ecocapture_gateway.cli --config server/.state/config.json issue-synthetic --installation-id <canonical-random-UUID> --credential-out <protected-path-outside-repository>
& server/.venv/Scripts/python.exe -B -m ecocapture_gateway.cli --config server/.state/config.json revoke --installation-id <same-UUID>
& server/.venv/Scripts/python.exe -B -m ecocapture_gateway.cli --config server/.state/config.json cleanup
```

Placeholders must be replaced before running these commands. Issuance is local administration, never an HTTP endpoint. Tokens contain 256 random bits; only SHA-256 token hashes enter SQLite. The credential file is created exclusively outside this repository, with POSIX mode 0600, and its token is never printed. Windows requires an operator-reviewed ACL on the containing directory; POSIX modes do not establish Windows confidentiality. There is no production participant provisioning command. `--study-code` is optional synthetic linkage for tests; default is null. The underlying issuance API defaults to ineligible.

## Hosting handoff

The three files in `deployment/` are review templates, with an intentionally reserved domain. They have not been installed or validated against a running nginx/systemd host. Choose an institutionally approved host/domain and owner before adapting them. Use a publicly trusted certificate, private staging on an approved encrypted volume, a dedicated service account and firewall controls. The backend listens only on 127.0.0.1 and trusts proxy headers only from that address. The host must prevent untrusted local processes from spoofing its proxy. Use a single worker for this development SQLite implementation.

The proxy overwrites forwarded headers and disables body buffering to avoid unplanned request copies. Raw nginx access/error logs are disabled because URIs can contain sensitive input. Monitoring, incident response, protected backups and aggregate diagnostic collection still require an operational decision. Local ASGI HTTPS/host checks and log suppression are tested; actual reverse-proxy/TLS/firewall behavior is a deployment acceptance check. Guidance: [FastAPI proxy trust](https://fastapi.tiangolo.com/advanced/behind-a-proxy/) and [HTTPS termination](https://fastapi.tiangolo.com/deployment/https/).

Cleanup is an explicit administrator action. The 24-hour expiry for incomplete/cancelled staging is an engineering default, not an approved retention policy. Completed mock payloads remain quota-counted until explicit cancellation. Minimal operational records remain bounded by the record cap. File removal is best effort; it is not secure erasure, withdrawal of remote copies or verified archival. Stop the service before taking a protected consistent SQLite/WAL and payload backup; recovery testing is pending. Do not copy staging or tokens into Git.

See [API contract](../documentation/PHASE3_GATEWAY_API.md), [checkpoint evidence](../documentation/PHASE3_CHECKPOINT4_REPORT.md) and [running manual checks](../documentation/PHASE3_OUTSTANDING_CHECKS.md).
