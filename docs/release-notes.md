Velop Control 0.4.0 — reliability and monitoring review

- Show 5 GHz high/low band and channel clearly, without duplicate raw radio IDs.
- Add node uptime and direct status refresh, plus snapshot timestamps and cached-data notices.
- Track one-shot restart requests while open; distinguish acceptance, unreachable/returned, confirmed newer boot time and unconfirmed outcomes. Refresh the mesh after recovery; never repeat a reboot automatically.
- Stop further authenticated requests after credential rejection until reconnection. Add reconnect/replace-password access.
- Use the appropriate firmware password policy and action. An accepted password change requires reconnection before the new credential is saved.
- Add experimental read-only LLDP wired-neighbor inspection for directly connected wired nodes. No manual cable map or unverified topology override.
- Avoid conflicting AP metrics, invalid RSSI/rates, repeated parsing/sorting, stale history results and loss of network refreshes when history fails.
- Tighten exact GitHub release asset matching and keep update completion from changing the screen after navigation.

Signed with the same permanent distribution key as 0.3.0: install over that version to retain local settings. The old 0.2.0 prototype requires a one-time uninstall because its temporary signing key was not retained.

Preview limitations: stock firmware is preserved. Exact physical wired paths, manual parent locking and complete official-app parity are not promised. New monitoring and LLDP paths have automated coverage but remain pending live hardware validation. Router credentials stay local; GitHub is used only for app updates.
