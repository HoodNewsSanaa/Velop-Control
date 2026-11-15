Velop Control 0.7.0 — stock-app tools and settings

- **Tools → Channel finder:** shows each node radio's channel, runs the stock channel selection once, follows progress and highlights changed and DFS channels. Replaces the old one-shot "Optimize wireless channels".
- **Tools → Speed check:** runs the router's built-in Speedtest by Ookla on the primary with live progress, download/upload/latency and the router's stored history.
- **Tools → Traceroute**, **WPS pairing** (push button), **Firmware update** (check now / install now through the stock Linksys update service), and **Router logs** (incoming, outgoing, security, DHCP; clear).
- Device details: **Remove from device list** for offline devices.
- New settings pages: DFS channels (now editable), Airtime fairness, Express Forwarding, IPTV (warns that enabling it can move a default LAN to 192.168.10.1), Linksys cloud remote access, Usage-data sharing, plus read-only Guest Wi-Fi clients and Routing table.
- Node and client steering remain on Settings → Automatic mesh steering (the firmware has no other steering settings).
- Deliberately not included: factory reset, node removal and firmware rollback.
- Fixes: tool screens resume checking after the app returns from the background and when reopened; disconnecting clears tool state; Speed check no longer reports "finished" before the router starts the test; screens keep their scroll position when they refresh (including 30-second auto-refresh); resizing, folding or attaching a keyboard no longer disconnects; connection history no longer logs every device again after each app launch.
- All changes use the existing guarded flow: management controls, a preview or confirmation, and typing APPLY for disruptive actions.

117 automated tests pass; Android lint reports 0 errors. Hardware validation of the new writes and tools is pending.

Velop Control 0.6.3 — neighbor signal fixes

- Fixes Neighbor signals discarding real readings. The firmware reports each neighbor as signal minus noise (a positive number); the app now converts it the same way the firmware does (value − 95 = dBm). In 0.6.2 most real neighbor readings were dropped.
- Neighbor signals now lists nodes the primary has no report from, and explains why: nodes report about hourly and when their Wi-Fi starts, only while node steering is on, and the primary forgets reports when it restarts.
- Release signing uses a new distribution key from 0.6.1 onward; installs signed with the 0.3.0–0.5.0 key must be uninstalled once.

111 automated tests pass; Android lint reports 0 errors. Hardware validation is pending.

Velop Control 0.6.2 — neighbor signals & steering

- New **Nodes → Neighbor signals & steering…** (also under Tools). Reads the primary's neighbor report (nodes/diagnostics/GetNodeNeighborInfo) and shows, for every node, how strongly it hears each other node's radios, with channel and band.
- Each node gets a verdict using the stock firmware's steering rules with default settings: an uplink at or above −60 dBm is left alone; otherwise the node is moved when another node is more than 10 dB stronger, or when the strongest is the primary. Children and 2.4 GHz radios are ignored, as the firmware does. This explains why a node stays on a farther parent.
- Radio BSSIDs are matched to nodes by exact MAC where known, otherwise by unique nearby address (labelled "matched by address"); unidentified radios are listed as such.
- Shows each directly connected node's radio transmit-power setting and power-table region. Both reads were also added to node diagnostics and sequential validation.
- All additions are read-only.

109 automated tests pass; Android lint reports 0 errors. Hardware validation is pending.

Velop Control 0.6.1 — accurate mesh diagram

- Node parents now come from the mesh backhaul report (the firmware's bh_report), with the device inventory's last-seen parent only as a fallback. Previously the inventory value took priority, which could attach nodes to the wrong parent. Node details show which source was used.
- The Mesh connections diagram is drawn as a real tree: each node appears directly under its parent instead of in alphabetical order. Nodes without a reported parent are listed separately and labelled. Shared connector lines are neutral; the link into each node shows wired (blue dashed) or wireless (green).
- New **Tools → Allow screenshots** option. Screenshots and the recent-apps preview stay blocked by default because they would capture device names, IP/MAC addresses and settings.

100 automated tests pass; Android lint reports 0 errors. Hardware validation is pending.

Velop Control 0.6.0 — find your primary automatically

- The login screen finds your primary Velop automatically after one successful connection, even if its address changed (common in bridge mode). It checks the last address, then the Wi-Fi gateway, then the phone's subnet. **Find my primary Velop** runs the search on demand.
- On first use, the address field suggests your Wi-Fi gateway (the primary in router mode). In bridge mode, enter the Velop's own address.
- Discovery sends no passwords. Probes are certificate-only TLS handshakes; only hosts presenting the approved or a stock Linksys certificate receive one anonymous identity read, and a host matches only if its serial number matches. Connecting still requires a tap.
- When the primary moves, its approved certificate pin and saved password move with it.
- Firmware analysis of MX4200 1.0.13.216903 and 2.0.7.216903 showed that Linksys ships one HTTPS certificate (with its private key) per firmware build, shared by nodes and replaced by updates. After a Velop firmware update, the primary is still found by serial number, and the "Router certificate changed" dialog explains the update, reports whether the serial number still matches, and states that pinning cannot prove which device answered. Saved passwords carry over after approval.
- Removed an unused read action from the allowlist. Adds the normal ACCESS_NETWORK_STATE permission (Wi-Fi/Ethernet gateway and subnet only; VPN and mobile networks are ignored).

98 automated tests pass; Android lint reports 0 errors.

Hardware validation of discovery and the updated certificate prompts is pending. Discovery is limited to the phone's own network segment (at most 1,022 addresses; larger networks narrow to the phone's /24).
