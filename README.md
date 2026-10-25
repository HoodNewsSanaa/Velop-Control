# Velop Control 0.4.0

Standalone Android app for stock Linksys Intelligent Mesh routers. Android 8.0+; native phone and tablet layouts; no WebView, Linksys account or separate backend. The first installable build is a development preview, with management controls off by default.

## Install and first test

Open `Velop-Control-0.4.0.apk` on Android and permit installation from that source if Android asks. Start with **Explore demo** to check that navigation works on your device. It contains five synthetic nodes and sample clients, and sends no router requests.

Next, while on the local Wi-Fi, connect to the **primary Velop’s** address and enter its local admin password. In bridge mode, use the Velop’s assigned address rather than the OpenWrt/OPNsense gateway. Approve the local HTTPS certificate only after checking the address on a trusted network. The app pins its SHA-256 fingerprint. Successful passwords are remembered by default, encrypted on-device with Android Keystore; this can be disabled or cleared in Tools.

Keep controls disabled for the first hardware session. Use **Tools → Run sequential validation**, then **Export diagnostic log**. It makes one read at a time, stops authenticated tests for a node after rejection, and never tests setters. Keep the app open until collection finishes. Each additional node must be connected separately from its details page; passwords are not sprayed across the mesh. Proceed one hardware test at a time.

## What is implemented

- Five navigation sections: Overview, Nodes, Devices, Settings and Tools. Bottom navigation on phones, a sidebar on tablets, light/dark appearance and search.
- Inventory discovery from a primary seed IP; reported topology, parents, node details, firmware, signal and hop metrics; explicit missing/unknown values.
- Searchable and filterable client list with AP association, IP/MAC, wired/wireless type, negotiated rate and signal where supplied.
- 38 settings pages, including native editors for wireless networks, guest access, steering, schedules, MAC filtering, WAN/bridge, LAN/DHCP/reservations, IPv6, routing, firewall, forwarding, parental controls, QoS device rules, management, time, logging, port LEDs, scheduled reboot, automatic update policy, SMB and HomeKit.
- Explicit change previews, native password fields, supported radio choices, rule/reservation tables, and a 48-slot daily schedule editor.
- Guarded node rename, direct-node restart, local admin password change, router ping and automatic channel optimization.
- Read-only firmware/storage/WAN/DDNS information, sequential API validation and identifier-free diagnostic export through Android’s file picker.
- Optional seven-day app-private association history and foreground-only 30-second polling.

See [complete feature coverage](docs/feature-coverage.md) for precise boundaries. Editors and active actions are implemented. The user confirmed direct-child login and restart in 0.3.0; settings writes and the new 0.4.0 monitoring/LLDP paths still need staged hardware validation. There is no claim of complete official-app parity.

## Change safeguards

Management mode starts disabled and resets on disconnect. Each setting change is projected onto an exact schema, validated, shown for confirmation, checked against a fresh router read, submitted once and read back. Drafts expire after three minutes. A changed router state blocks submission. Passwords are hidden from previews. Optional IP fields are omitted when blank; read-only capability fields never become setter fields. Disruptive changes require typing `APPLY`.

If a write times out, the app cannot tell whether the router applied it. It does not resubmit: reconnect and read current settings first. There is no automatic rollback, because a blind rollback could also disrupt the mesh. Firmware-driven steering and parent choice are never overridden. Manual parent locking, node onboarding and full backup/restore remain unavailable.

## Security and local operation

All router traffic is native Java over pinned HTTPS on port 443. Only numeric private IPv4 addresses are accepted; redirects and HTTP fallback are disabled. Before the first credentialed request, a certificate-only TLS connection presents the fingerprint for explicit trust. Matching pins are reused on later connections; a change requires another approval. First trust does not establish identity independently of the user’s trusted-network check.

Successful passwords can be saved encrypted with an Android Keystore AES-GCM key, bound to the local host and approved certificate. They are excluded from saved view state and autofill; entry fields and session arrays are cleared on disconnect. Tools can disable saving or forget stored credentials. Temporary immutable Java strings used in networking cannot be reliably zeroized. Android screenshots and cloud/device-transfer backups are disabled. No analytics, advertisements, arbitrary action console or MQTT/SSH transport is included. GitHub is the explicit Internet exception for app updates; no router credentials are included in update requests. Automatic release checks can be disabled.

Diagnostic JSON contains actions, results, durations, structural field types/counts, boolean capability flags and model/firmware. It excludes raw responses, passwords, SSIDs, device names, MACs and IPs. Optional local history stores device labels/MACs/association events inside the app and can be cleared. Exported validation logs do not include that history.

## Build from source

JDK 17, Android SDK Platform 35 and Build Tools 35.0.0. The checked-in Gradle wrapper pins Gradle 8.11.1 with a distribution checksum; Android Gradle Plugin is 8.9.2. Configure the SDK in Android Studio or set `ANDROID_HOME`.

```sh
./gradlew testDebugUnitTest lintDebug assembleDebug
```

On Windows, use `gradlew.bat`. APK output is `app/build/outputs/apk/debug/app-debug.apk`. There are no third-party runtime libraries. JUnit, org.json and Robolectric are test-only dependencies. The GitHub Actions workflow runs the same checks and publishes a build artifact when this source is placed in a repository.

The delivered 0.4.0 APK is a non-debuggable preview signed with a permanent distribution key. That private key is stored separately and is not in this source archive. The temporary 0.2.0 signing key was not preserved; Android requires uninstalling that prototype once before installing this release, removing its local preferences/history. Future updates retain the distribution key for in-place installation.

## Verification

The package was compiled, unit tested, checked with Android lint and verified with Android APK signing/alignment tools. Automated tests cover topology correlation, missing groups, private-address restrictions, stale edits, exact setter projections, password redaction, input validation, single submission and timeout behavior. Native Android views were exercised with Robolectric at API 35 in phone and tablet sizes, including navigation, client search, theme switching and form collection. Rendered screens were inspected.

Automated verification does not replace physical-phone or router testing. The user confirmed direct-child login and restart in 0.3.0 after recovering a locked account. New 0.4.0 paths, bridge-mode behavior and general settings writes still require staged hardware validation. Development tools did not contact or modify the user’s routers.

Third-party reference notices are in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

## Changes in 0.3.0

Backhaul GHz/radio/channel and matched reported link rates are separate from measured child-to-parent throughput. Report times are shown where provided, wired 1024 placeholders are hidden, and wired parent paths remain unverified. No static manual wiring override is included. Child access uses a firmware-appropriate password-check action and reports lockout/delay explicitly. See [release notes](docs/release-notes.md) and [GitHub publishing setup](docs/github-release-setup.md).

GitHub release checks run on launch at most once per eight hours when enabled. Downloads are explicit, bounded, restricted to GitHub release hosts and verified against GitHub SHA-256 metadata plus this app’s package/version/signing certificate. Android asks the user to confirm installation. End-to-end publication/update testing remains pending until the repository and its first release exist.


## Changes in 0.4.0

- Restart requests remain single-shot. While the app is foregrounded, anonymous identity probes watch for loss/recovery for up to two minutes. One final authenticated uptime read can confirm a newer boot time, including when a short offline window was missed. A network interruption alone is not called a confirmed reboot. A lost reboot response stays uncertain and is never resent automatically. A recovered node triggers a mesh refresh.
- Nodes expose uptime at the last direct read, its timestamp and a refresh action. Snapshot timestamps appear on main screens. Failed operations pause automatic refresh and retain an explicitly marked previous snapshot.
- `5GH` and `5GL` display as **5 GHz · high band** and **5 GHz · low band**; channel remains separate. Unknown radio identifiers remain visible. Valid negotiated rates display even if RSSI is missing. Invalid/positive RSSI and non-finite rates are suppressed; client rates from a conflicting AP are not borrowed.
- Rejected sessions block subsequent authenticated requests until reconnection. A reconnect/replace-password action is available for connected nodes. Password changes select the versioned firmware action/policy and require reconnection before saving the new credential; an accepted asynchronous write is not reported as verified.
- An experimental **Inspect wired neighbors** action reads a directly connected wired node's pinned-HTTPS `/sysinfo.cgi?section=fwinfo` once, retaining only recognized LLDP address/port fields in memory. Full system reports are discarded and excluded from exports. Results do not overwrite the topology or claim a verified physical cable path. This endpoint and parser remain untested on live hardware.
- Mesh parsing runs once per refresh, backhaul peer lookup is indexed, client sorting is reused per screen, search survives refresh/navigation, and local-history failure cannot discard a successful network read. History transaction rollback and the seven-day view window are handled correctly.
- Updates cannot redirect navigation after the user leaves their page. Failed automatic checks also respect the eight-hour interval; APK metadata must match the exact release tag and filename.

0.4.0 uses the same distribution key as 0.3.0 and can update it in place. See [code review](docs/code-review-0.4.0.md) and [wired-parent investigation](docs/wired-parent-investigation.md) for findings and remaining limits.
