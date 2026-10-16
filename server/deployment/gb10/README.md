# GB10 Apptainer + Tailscale Funnel package

Prepared locally for the proposed SSH target `rchia@100.76.243.123`. No SSH connection, password access, upload, remote build, service start or public Funnel activation has been performed. This remains Checkpoint 4's synthetic mock gateway; it has no FileSender client or remote AI inference.

Investigator supplied machine name `promaxgb10-4e2b` and tailnet domain `tail06a565.ts.net`. The prepared hostname is **`promaxgb10-4e2b.tail06a565.ts.net`**, with future health URL `https://promaxgb10-4e2b.tail06a565.ts.net/v1/health`. `gateway.synthetic.json` contains this non-secret configuration and is included in the source bundle for later external installation. Confirm the full DNSName on the machine before publication; name composition does not establish HTTPS availability.

## Contents and network path

For clean start/stop/restart/status and optional logout/boot persistence, use the [systemd user-service controller](SERVICE.md). It manages the existing fixed SIF without a rebuild. Host integration acceptance is still required; it does not change Funnel or the private 8443 service.

```text
Phone (ordinary HTTPS, no Tailscale)
  -> host Tailscale Funnel :443 (TLS)
  -> container nginx 127.0.0.1:18080 (fixed host/header checks)
  -> container gateway 127.0.0.1:8000 (installation token authentication)
  -> private host-bound SQLite and synthetic staging
```

The SIF is read-only and contains Python, nginx, pinned gateway dependencies, source and synthetic tests. No GPU bindings, Tailscale identity, SSH credential or FileSender secret enter it. Host network is shared intentionally so the host Tailscale daemon can reach loopback. Private config is bound read-only; the separate 0700 state directory is writable. Container temporary files use isolated temporary storage. A container is not a security boundary against other privileged/local host users; choose the operating account and host controls accordingly.

`run.sh` starts only loopback processes. `publish-funnel.sh` is a separate explicit publication action; it is never invoked by the container or build. Existing Serve/Funnel routes must be reviewed before publication because this package would use port 443 and path `/`. Investigator-reported preflight: Apptainer 1.4.5, Tailscale 1.102.2, existing tailnet-only 8443 route to localhost:8081. Port 8080 is occupied, so this package now uses 18080. Ports 8000/443 had no listeners in the supplied output; Investigator confirmed no 18080 listener and architecture aarch64. Native image build and runtime checks remain pending. Preserve the existing 8443 route.

## Local preparation - ready now

Review `gateway.def`, `container_runtime.py`, `run.sh` and `publish-funnel.sh`. Create a source archive from Windows:

```powershell
& server/.venv/Scripts/python.exe -B server/deployment/gb10/package_local.py server/.packages/gb10-gateway-promaxgb10-runtime-review.tar.gz
```

The output is exclusive (choose a new filename on subsequent builds), has a companion archive SHA-256, and contains `SHA256SUMS` for all source files. Only explicitly selected source/config/schema/test/documentation files are included; `.venv`, runtime state, keys, credentials and the original FileSender script are excluded. Shell files are normalized to LF and executable modes in the TAR. This is a source bundle, not an already built/tested ARM64 SIF.

Do not put an SSH password in this repository, config, image, chat or an environment variable. For later manual SSH/SCP, enter it at OpenSSH's interactive password prompt; alternatively use a passphrase-protected SSH key and agent. No password is needed to review this package. Keep normal host-key verification enabled; confirm the GB10 fingerprint through a trusted route before its first connection.

## Future GB10 preflight - not run yet

When deployment is authorized, inspect interactively using `ssh rchia@100.76.243.123`:

```sh
uname -m
apptainer version
tailscale version
tailscale status
tailscale serve status
tailscale funnel status
df -h
ss -ltn
```

Expected architecture is aarch64/ARM64; verify rather than assuming. Check that Apptainer supports native builds/fakeroot, that ports 8000/18080 are free, that Tailscale is connected, and that publishing 443 would not replace another service. Obtain the machine's actual Tailscale DNSName (for example `gb10.<tailnet>.ts.net`), without trailing dot. The Tailscale IP is an SSH management address, not the public HTTPS hostname. Do not paste full device/account listings or credentials back into chat; report versions, available ports, DNSName and relevant outcomes only.

## Future build and private start - not run yet

Transfer the reviewed archive to a new private directory, verify its companion SHA-256, extract it, and run these commands from the extracted bundle root. These operations fetch the base image, apt packages and Python dependencies but never publish Funnel:

```sh
sha256sum -c SHA256SUMS
apptainer build --arch arm64 --fakeroot gateway-gb10.sif server/deployment/gb10/gateway.def
sha256sum gateway-gb10.sif
```

The image definition embeds the same test suite in `%test`, so build fails on a test failure. If fakeroot is unavailable, stop and have the host operator choose an approved build method; do not automatically install software or use sudo. The base image tag and apt package versions are mutable; record the resolved base digest, installed versions and SIF hash after a successful build before promoting it. No Linux/ARM64 build has been claimed here.

Choose private paths **outside the extracted source**, using absolute paths without spaces, commas or colons. Example paths below are synthetic test staging only; their filesystem encryption and institutional acceptance are not established:

```sh
install -d -m 700 /home/rchia/server/ecocapture-gateway-config /home/rchia/server/ecocapture-gateway-state
install -m 600 server/deployment/gb10/gateway.synthetic.json /home/rchia/server/ecocapture-gateway-config/gateway.json
chmod 600 /home/rchia/server/ecocapture-gateway-config/gateway.json
sh server/deployment/gb10/run.sh /ABSOLUTE/BUNDLE/gateway-gb10.sif /home/rchia/server/ecocapture-gateway-config /home/rchia/server/ecocapture-gateway-state serve
```

Replace the absolute bundle placeholder and confirm the supplied hostname matches the machine. Install into a new config directory; do not overwrite an existing operational configuration. If the DNSName differs, use `prepare_config.py` with the actual DNSName to create a fresh config; it validates the hostname and refuses overwrites. The launcher enforces state owner/mode 0700. Run in the foreground initially; Ctrl+C stops both child processes. No persistent system service is installed. Startup status indicates processes were started, not readiness. In a second GB10 session, verify readiness with the confirmed DNSName:

```sh
curl --fail --max-time 10 -H 'Host: promaxgb10-4e2b.tail06a565.ts.net' http://127.0.0.1:18080/v1/health
```

Expected body is `{"status":"ok","transport":"mock"}`. The loopback proxy sets trusted HTTPS headers for the gateway; public TLS exists only when Tailscale terminates it. Raw nginx logs are disabled. Gateway process diagnostics are deliberately generic; missing processes/port conflicts require operator inspection without enabling sensitive request logging. Incoming public traffic shares a conservative loopback peer-rate bucket; per-installation quotas/rates still apply. Throughput and Funnel's bandwidth limits must be measured with harmless fixtures.

Synthetic credentials can later be issued using the same launcher and an external state file:

```sh
sh server/deployment/gb10/run.sh /ABSOLUTE/BUNDLE/gateway-gb10.sif /home/rchia/server/ecocapture-gateway-config /home/rchia/server/ecocapture-gateway-state issue-synthetic --installation-id CANONICAL-RANDOM-UUID --credential-out /gateway-state/synthetic-credential.json
```

The file is host-visible at `/home/rchia/server/ecocapture-gateway-state/synthetic-credential.json`, mode 0600. Token output is not printed; do not cat it into logs/chat. Use `revoke --installation-id UUID` or `cleanup` through the same launcher. The file must not already exist. Production participant provisioning and real forwarding remain unavailable.

## Future public exposure - separate action, not run yet

After private readiness, host approval and existing route review:

```sh
sh server/deployment/gb10/publish-funnel.sh --publish-synthetic-gateway
tailscale funnel status
```

The command deliberately enables public HTTPS to only the loopback proxy. Tailscale may require HTTPS/Funnel permissions to be enabled in the tailnet. It does not grant API access without gateway tokens. To disable this published proxy, after checking the actual installed CLI and route state:

```sh
tailscale funnel --https=443 off
```

Do not use `tailscale funnel reset`, which could remove unrelated routes. Verify the health endpoint from outside the tailnet and on phone mobile data with VPN disabled; verify certificate trust and that the returned transport is mock. Operator acceptance must also cover replay, restart/resume, revocation, owner isolation, Linux symlink rejection and absence of secret/request logging on this actual proxy path. No real recordings should be sent for these checks.

Apptainer references: [definition files](https://apptainer.org/docs/user/latest/definition_files.html), [bind isolation](https://apptainer.org/docs/user/latest/bind_paths_and_mounts.html), [ARM64 images](https://apptainer.org/docs/user/latest/docker_and_oci.html). Tailscale references: [Funnel CLI](https://tailscale.com/docs/reference/tailscale-cli/funnel), [Funnel requirements and limits](https://tailscale.com/docs/features/tailscale-funnel).

## Manual checks for this local preparation

The image suite, private health, public mobile-data health and preserved 8443 service have now passed by investigator report. Use [live hosted synthetic acceptance](HOSTED_TEST.md) for the remaining public upload/resume/hash/replay/isolation/revocation checks. The new admin helper binds its source into the existing fixed SIF; no image rebuild is needed.

### Run embedded image tests with host-backed scratch

`apptainer test --containall --cleanenv` alone can give the tests a default 64 MiB session filesystem. The gateway deliberately requires a 64 MiB free-space margin in addition to object reservations, so this scratch filesystem can cause `DISK_CAPACITY` before ordinary transfer tests begin. Use a new private host-backed test workspace, separate from gateway state:

```sh
df -h /home/rchia/server
imageTestWorkspace=$(mktemp -d /home/rchia/server/ecocapture-image-test.XXXXXX)
apptainer test --containall --cleanenv --workdir "$imageTestWorkspace" /home/rchia/projects/ecological-gateway/gateway-gb10-fixed.sif
```

The host filesystem needs more than 64 MiB free plus the small synthetic fixtures. `--workdir` provides backing for contained temporary/home paths; no production config/state is bound for this test. The new directory is private mode 0700 and may retain Apptainer scratch subdirectories; do not reuse gateway storage for it. This works with the existing SIF and requires no rebuild or reduction of gateway disk safeguards. Report the final image-test summary. If failures persist, report `df` and the first distinct failure. The running gateway and Funnel routes are unchanged by this test. Reference: [Apptainer session directory limits](https://apptainer.org/docs/admin/1.3/configfiles.html).

Runtime troubleshooting update: the investigator's foreground start returned only `GB10_RUNTIME_FAILED`; no private health check has passed. The launcher now reports fixed, non-secret diagnostic codes for config/file/permission/nginx/gateway failures, without exception contents or raw nginx logs. nginx's startup error log/prefix and all module temporary paths are explicitly directed to `/dev/null` or the isolated writable runtime directory, avoiding default paths in the read-only image. This addresses a possible startup issue, not a confirmed cause of the reported failure. These changes require a newly built SIF; existing images do not automatically pick up source edits. Before rebuilding, verify that the external config uses container storage `/gateway-state/private` and the image actually contains the 18080 listener. Do not substitute the host storage path inside JSON; the host path belongs only in the bind arguments.

- **Ready now:** review package contents and confirm the private-config/state paths and separate publication step suit your GB10. Expected: no password/secret in source or image; no remote action taken. Report any desired path or operating-account changes.
- **Awaiting deployment authorization:** provide the actual DNSName, Apptainer/Tailscale versions and port availability from preflight. Expected: native ARM64 build support and no existing service conflict. No need to share a password.
- **Awaiting successful GB10 build/start/publication:** record SIF hash, build test results, private health result, then public mobile-data HTTPS/resume/revocation results. Until these exist, Checkpoint 4 remains partial and the ARM64 image is unvalidated.
