# Start/stop the GB10 gateway with systemd

`gatewayctl.py` manages only `ecocapture-gateway.service` in rchia's systemd user manager. It runs the already accepted `gateway-gb10-fixed.sif`, existing `run.sh`, and configured private directories. No SIF rebuild, password storage, GPU, new tokens, automatic transfer or Funnel mutation. This is a local prepared controller; systemd/Apptainer integration must be verified on GB10 before claiming operational acceptance.

## Install once

From Windows PowerShell in the repository root:

```powershell
scp server/deployment/gb10/gatewayctl.py rchia@100.76.243.123:/home/rchia/projects/ecological-gateway/server/deployment/gb10/
```

On GB10 as rchia (not sudo/root), from `~/projects/ecological-gateway`:

```sh
python3 server/deployment/gb10/gatewayctl.py install
```

Expected: `USER_SERVICE_INSTALLED`, `started:false`. It creates `~/.config/systemd/user/ecocapture-gateway.service` and reloads only the user manager. Existing unrelated units are untouched; a unit file without this controller's marker will not be overwritten. The unit discovers Apptainer's executable directory for PATH and uses the fixed approved engineering image/config/state paths. If the systemd user session is unavailable, report the safe failure code; do not disable security or run this controller as root.

## Switch from the old foreground process once

Stop the original foreground gateway with Ctrl+C in its terminal. If that terminal is gone, use the previously documented listener -> parent -> verified `container_runtime.py` supervisor method, then SIGTERM only that supervisor. The controller deliberately never kills an unmanaged process or guesses ownership by port.

Check both ports are now free:

```sh
ss -ltn '( sport = :8000 or sport = :18080 )'
```

Expected: header only. Starting over an existing listener fails with a safe port-busy code. Leave Tailscale, Funnel and the 8443 service running.

## Routine control

```sh
python3 server/deployment/gb10/gatewayctl.py start
python3 server/deployment/gb10/gatewayctl.py status
python3 server/deployment/gb10/gatewayctl.py stop
python3 server/deployment/gb10/gatewayctl.py restart
python3 server/deployment/gb10/gatewayctl.py logs
```

Use these as separate actions when needed, not an automatic sequence. `start`/`restart` return after systemd accepted the action, not a readiness guarantee. Check `status` a few seconds later: expect active/running and `mock_health_ok:true`. Status probes only the private loopback health endpoint and never sends credentials. `logs` shows only the last 30 journal entries from this unit; the gateway runtime logs fixed statuses and suppresses raw request/exception content. Do not enable raw nginx/access logging for troubleshooting.

Direct equivalents are `systemctl --user start|stop|restart|status ecocapture-gateway.service`. systemd owns the gateway process group. `KillMode=mixed` gives the supervisor a graceful stop and, after 20 seconds, confines forced cleanup to this unit's remaining processes. Restart-on-failure is bounded to three starts per minute. Stopping the service does not disable Funnel; the public endpoint will be unavailable until the gateway starts again. The existing 8443 service is outside this unit. Reference: [systemd kill behavior](https://www.freedesktop.org/software/systemd/man/252/systemd.kill.html).

## Persist after logout / start on boot

User services belong to the user manager. To keep rchia's user manager running after logout and create it at boot, have the host operator explicitly enable lingering:

```sh
sudo loginctl enable-linger rchia
loginctl show-user rchia -p Linger
```

Expected: `Linger=yes`. This is a host-account lifecycle setting requiring the relevant authorization; the controller does not change it silently. Then enable this unit for the user's default target:

```sh
python3 server/deployment/gb10/gatewayctl.py enable
python3 server/deployment/gb10/gatewayctl.py start
```

`enable` schedules startup with the user manager; it does not itself start the gateway. To remove automatic startup, use `disable`; use `stop` separately for the running service. Lingering affects the rchia user manager generally, not only this gateway, so choose it as a host operational decision. Reference: [loginctl lingering](https://www.freedesktop.org/software/systemd/man/252/loginctl.html).

## Manual acceptance

1. Install, stop the old foreground gateway once, start the managed service and check status. Expected active/running and mock_health_ok true. Report safe output.
2. Stop it and verify 8000/18080 have no listeners; start/restart it and verify health returns. Expected no PID guessing and no unrelated process stopped. Report results.
3. Confirm the existing 8443 service still works and Funnel route configuration is unchanged. Existing accepted engineering evidence remains; this check covers the service-management change only.
4. If lingering/autostart were selected, disconnect/reconnect SSH and check status/health; arrange a reboot test only at an appropriate time. Expected service persistence. Do not claim boot persistence without that evidence.

No new upload/revocation run is needed for this host controller; it does not change the accepted image or transfer protocol. Operational hosting/security/backup approvals remain separate in the running checklist. This does not start Checkpoint 5.
