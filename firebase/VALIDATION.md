# Firebase implementation validation — 2026-10-19

All checks used local synthetic fixtures or demo Firebase emulators. No real Firebase project was mutated, no participant data was sent, no credentials were provisioned on GB10 and no device package was installed or removed.

| Check | Result |
| --- | --- |
| Default Android JVM suite | 73 tests passed |
| Firebase-enabled Android JVM suite | 73 tests passed |
| Firebase-enabled debug APK | Assembled successfully with synthetic client configuration |
| Android instrumentation sources | Compiled in both configurations; not executed on a device |
| Server regression suite, including receiver | 77 tests: 76 passed, one existing Windows symlink-privilege skip |
| Firebase contract tests | Four passed |
| Real Firebase Storage/Firestore emulator rules tests | Four passed |
| Cloud API emulator integration tests | Eight passed |
| Research catalogue tests | Two passed; filtering and safe CSV export |
| Research catalogue bundle | Built successfully with synthetic web configuration |
| Go encryption tests | Passed, including both recipients, wrong-key rejection, source changes and overwrite rejection |
| Go encryption → real age receiver | Passed separately for both GB10 and recovery identities |
| Full synthetic flow | Passed: Go encryption → registration → emulated bucket → completion hash check → receiver download/decrypt/verification → durable receipt; repeated receipt skipped duplicate receiving |
| Source diff whitespace check | Passed |

The API tests use real Firestore transactions and real emulated Storage data. Firebase identity claims, custom-token signing and worker identity verification are injected, so these do not prove real IAM, Google sign-in or OIDC configuration. The encrypted integration uses the exact Go package compiled into the Android AAR but runs its harness on Windows, not through JNI on a phone.

Cloud tests ran on a checkout-local Python 3.12.10 test interpreter. Deployment targets managed Python 3.12. The local Go toolchain was Go 1.27.2; module/tool dependencies are pinned in go.mod/go.sum. The generated AAR targets Android arm64 API 31. Android checks used Java 21 and Gradle 8.14.1. The dashboard CLI/build ran with the installed Node 24; Node 22 is the documented supported development default for the pinned emulator CLI.

No browser automation tool was available in this session. Actual Google sign-in popup behavior, deployed CSP and device accessibility remain acceptance checks. The GB10 ARM64 Apptainer image/service must be built and accepted on the target host. Retention, recovery-key custody, cloud location, IAM and institutional archival remain operator/release decisions.

The target project supplied by the investigator is `ecological-capture`, number `605508959167`, under `uts.edu.au`. After investigator sign-in, authenticated preflight found an active project, the existing Hosting site `ecological-capture.web.app`, no database or storage bucket, and billing disabled. Sydney was selected by the investigator. Android app `1:605508959167:android:a949c3495d9cbfcbf4ff0a` was registered; no web app, database, bucket, deployed function or Hosting release was created. The investigator has no billing account and proposed using the existing Tailscale hostname and GB10 instead. Local `demo-ecocapture` client configuration remains for compilation only.
