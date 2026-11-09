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

98 automated tests pass; Android lint reports 0 errors. Signed with the same permanent certificate as 0.3.0–0.5.0 for in-place updates.

Hardware validation of discovery and the updated certificate prompts is pending. Discovery is limited to the phone's own network segment (at most 1,022 addresses; larger networks narrow to the phone's /24).
