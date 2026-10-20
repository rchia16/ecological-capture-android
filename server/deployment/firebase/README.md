# GB10 Firebase receiver

This outbound-only service receives encrypted packages from the new Firebase path. It does not start, stop, replace or publish the existing gateway. Build the ARM64 image on GB10 from the repository root:

```sh
apptainer build --arch arm64 receiver.sif server/deployment/firebase/receiver.def
```

Prepare private directories under `$HOME/server/ecocapture-receiver`: `config`, `data`, `secrets`, and `bin`, owned by the receiver account with mode 0700. Record the built SIF hash. Copy `receiver.example.json` to `config/receiver.json` and fill in the real endpoint/audience, study and bucket. Paths inside that example are container paths.

Provision the dedicated GB10 age identity at `secrets/gb10.agekey` and dedicated Google service-account ADC at `secrets/google-adc.json`, both mode 0600, through an approved secret-provisioning method. The receiver's Google identity must match the cloud function's `ECO_WORKER_EMAIL`. It needs bucket-scoped Object Viewer access and no Firestore access. Never bind a researcher recovery private key onto GB10. Use the official pinned age **v1.3.2** Linux ARM64 executable at `bin/age`, verify its release checksum and set its executable permission. The image contains neither keys nor age binaries.

Copy the accepted image to `$HOME/server/ecocapture-receiver/receiver.sif`. Before installing the service, test one cycle with those four explicit binds:

```sh
apptainer run --cleanenv --containall \
  --bind "$HOME/server/ecocapture-receiver/config:/receiver-config:ro" \
  --bind "$HOME/server/ecocapture-receiver/data:/receiver-data" \
  --bind "$HOME/server/ecocapture-receiver/secrets:/receiver-secrets:ro" \
  --bind "$HOME/server/ecocapture-receiver/bin:/receiver-bin:ro" \
  "$HOME/server/ecocapture-receiver/receiver.sif" --once
```

The image declares `GOOGLE_APPLICATION_CREDENTIALS=/receiver-secrets/google-adc.json`. Review the service account's key lifecycle with your operator. Do not run with an unrelated administrator credential. If site policy requires federation instead of a service-account key, provision and validate a compatible ID-token credential mechanism before deployment; this version uses Google ID-token ADC support for service-account credentials.

After synthetic acceptance, copy `ecocapture-receiver.service` into the account's systemd user-unit directory, reload and enable it. Confirm the `/usr/bin/apptainer` path on the actual host. Configure user lingering with the administrator if the service must survive logout. It polls every 60 seconds and retries temporary service/storage failures without deleting pending cloud data.

The private `data` directory contains:

```text
.receipts.sqlite3              durable local receipt/acknowledgement ledger
.staging/SNAPSHOT/             temporary ciphertext and decrypted TAR; removed after each attempt
STUDY/PARTICIPANT/YYYY-MM-DD/EVENT/SNAPSHOT/
    video.mp4
    event.json
    manifest.json
```

Dates are UTC. Files are published by rename only after age authentication, ciphertext SHA-256, manifest/source SHA-256, schema and participant/event/snapshot checks pass. Keep staging and final directories on the same filesystem. Allow at least three times the 256 MiB package limit plus 64 MiB free for an attempt; disk exhaustion leaves the package pending. A failed acknowledgement is retried from SQLite before new downloads. A crash after file publication is recovered by revalidating the existing package and committing its receipt.

The receiver account can read decrypted research footage: use approved encrypted storage and researcher access controls. Researchers should access published directories through your existing approved GB10 workflow. No file browser or public HTTP server is created.

Only generic cycle statuses are emitted. Monitor systemd health, failed cycles, free disk space and the cloud catalogue backlog. On a package verification failure the catalogue shows `NEEDS_ATTENTION`; inspect on the authorised system and use the local operator `firebase/admin.py retry` command after resolving the cause. Do not mark a corrupted package verified manually. Stop the service before consistent backup of the SQLite/WAL and received files. Receipt is not archival, a backup or permission to remove phone/cloud copies.
