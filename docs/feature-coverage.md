# Feature coverage — 0.4.0

38 setting pages: 28 guarded editors, 7 information pages and 3 explicitly unavailable workflows. Additional node actions and diagnostics are described below.

An editor means the form and request pipeline exist. It does not mean a write has been confirmed on the user’s hardware. Unsupported reads produce an error and never enable a blind write.

| Area | Page | Build behavior |
|---|---|---|
| Wireless | Wi-Fi networks | Native editor; writes untested |
| Wireless | Guest Wi-Fi | Native editor; writes untested |
| Wireless | Automatic mesh steering | Native editor; writes untested |
| Wireless | WPS | Native editor; writes untested |
| Wireless | MAC filtering | Native editor; writes untested |
| Wireless | Wireless schedule | Native editor; writes untested |
| Wireless | DFS capability | Read-only information |
| Network | Internet & bridge mode | Native editor; writes untested |
| Network | LAN, DHCP & reservations | Native editor; writes untested |
| Network | IPv6 | Native editor; writes untested |
| Network | WAN MAC cloning | Native editor; writes untested |
| Network | NAT & static routes | Native editor; writes untested |
| Security | Firewall & VPN passthrough | Native editor; writes untested |
| Security | SIP ALG | Native editor; writes untested |
| Security | DMZ | Native editor; writes untested |
| Security | Port forwarding | Native editor; writes untested |
| Security | Port-range forwarding | Native editor; writes untested |
| Security | Port triggering | Native editor; writes untested |
| Security | UPnP | Native editor; writes untested |
| Family | Parental controls | Native editor; writes untested |
| Family | Device prioritization | Native editor; writes untested |
| Administration | Management access | Native editor; writes untested |
| Administration | Time zone & daylight saving | Native editor; writes untested |
| Administration | Router logging | Native editor; writes untested |
| Administration | Ethernet-port LEDs | Native editor; writes untested |
| Administration | Scheduled restart | Native editor; writes untested |
| Administration | Automatic firmware updates | Native editor; writes untested |
| Storage | USB file sharing | Native editor; writes untested |
| Administration | HomeKit router support | Native editor; writes untested |
| Storage | Attached USB storage | Read-only information |
| Network | DHCP leases | Read-only information |
| Administration | Firmware status | Read-only information |
| Network | Internet status | Read-only information |
| Network | Dynamic DNS | Read-only information |
| Network | Dynamic DNS status | Read-only information |
| Wireless | Manual parent selection | Unavailable; explanation included |
| Administration | Add or remove mesh nodes | Unavailable; explanation included |
| Administration | Full configuration backup & restore | Unavailable; explanation included |

## Navigation and monitoring

- Overview: responsive summary cards, tappable reported topology, weak-backhaul hints and incomplete-report notices.
- Nodes: model, firmware, reported presence, wired/wireless parent, RSSI and hop metric. Direct sessions isolate node-specific passwords.
- Devices: search by name/IP/MAC/AP; node, wired/wireless and connection-report filters; signal, negotiated rate and router timestamp where reported.
- Settings: search/grouping, target selector, capability-limited choices, native switches, password controls, time-zone list, half-hour schedules and editable rule/reservation tables.
- Tools: sequential read-only validation with stop support; JSON export; optional 7-day app-private association history; theme and foreground polling preferences.

## Additional guarded actions

Rename a mesh node, restart a directly connected node, change that node’s local admin password, request a four-packet router ping, and request automatic wireless channel selection. These actions require management mode and their own confirmation. Disruptive actions also require typing APPLY. Their protocol paths are source-based; no live write has been performed.

## Boundaries

- No manual parent locking, mesh onboarding/removal or full backup/restore workflow.
- No Linksys cloud remote login, paid subscription services or cloud push alerts.
- Dynamic DNS is inspectable; provider-specific account editors are not implemented.
- Existing QoS application rules are preserved, but only device prioritization rules have an editor.
- No front status-light/night-mode control: the user’s firmware rejected the night-mode getter. The LED editor addresses Ethernet-port LEDs only.
- No one-click firmware download/install, factory reset or forced recovery. Firmware status and update policy/window are separate supported pages.
- No WAN speed-test runner or traffic-volume charts. Negotiated link rates and hop metrics are not internet throughput or per-device data usage.
- IPv4 WAN forms cover DHCP, static, PPPoE, PPTP, L2TP and bridge, limited to advertised modes. Other current types are viewable and preserved but cannot be newly selected. IPv6 has automatic/PPPoE/pass-through choices only when advertised; no tunnel editor.
- Legacy WEP values can be viewed/edited when already configured; selecting a new WEP configuration is not implemented.
- No SSH, MQTT commands, firmware replacement or automatic parent steering override.
- No continuous background collector; observations are sampled while the app is open. History does not reconstruct events while closed.
- Routing pages are view-only when the primary reports bridge mode; manage OpenWrt/OPNsense routing separately. Bridge mode itself remains an explicitly guarded WAN choice.

## First verification

Start by installing the APK and opening the demo. Then perform one read-only connection to the test primary, before testing the remaining nodes. Keep management mode disabled until the app’s read validation log has been reviewed.

## 0.3.0 additions

Successful passwords can be remembered encrypted on-device, with forget/disable controls. Child login uses a dedicated firmware-version-appropriate password check, not full inventory, and distinguishes password rejection/delay/lockout. Public GitHub release checks and signed APK installation are an explicit exception to local-only router networking. No router credentials are sent in update requests.

Node information includes GHz, radio ID, channel, matched reported PHY/negotiated rate and its source/report time, plus separate measured child-to-parent throughput and measurement time. A reported rate is only displayed after matching both backhaul station MAC and parent AP BSSID. Missing values remain unavailable. Wired 1024 placeholder throughput is hidden, with Ethernet link rates accessible as per-port diagnostics. Wired paths remain refreshed firmware reports and unverified as physical cable paths; no static manual map is included.


## 0.4.0 review improvements

Direct-node uptime, restart observation and boot-time verification, clearer band labels, rejected-session request blocking, reconnect/password replacement, versioned admin-password policy, and stricter updater metadata/navigation behavior are included. Snapshot times and retained-data notices distinguish cached firmware information from current reachability. An explicit experimental wired-neighbor reader extracts LLDP evidence without saving full diagnostics or overriding the topology. It does not guarantee exact physical parent identification. New paths require one-at-a-time live validation; the user's confirmed 0.3.0 node login/restart is not a test of this new implementation.
