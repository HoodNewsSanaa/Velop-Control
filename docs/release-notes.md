Velop Control 0.5.0 — add mesh nodes

- Add wired nodes through stock automatic Ethernet onboarding.
- Add wireless nodes using the primary router’s Bluetooth radio; the Android device stays on mesh Wi-Fi.
- Read current primary mode, firmware support, onboarding flags and member inventory before confirmation, then recheck before sending.
- Send setup once and monitor for a new connected Slave member. Existing/offline members and ordinary clients do not count as new nodes.
- Restore flags enabled by this app after a new member is confirmed, five minutes while the app is open, or explicit End setup. Previously enabled settings stay enabled.
- Persist cleanup information before changes; interrupted sessions show a cleanup reminder after reconnecting. Cleanup checks the original primary identity and never removes joined nodes.

86 automated tests pass; Android lint reports 0 errors. Signed with the same permanent certificate as 0.3.0 and 0.4.0 for in-place updates.

Hardware validation is pending. Automatic onboarding may add multiple eligible unconfigured nodes; power only the intended node nearby. Disabling the Bluetooth onboarding flag does not guarantee cancellation of firmware work already underway. Keep the app open during setup; if Android closes it, reconnect and finish cleanup. Node removal, factory reset and manual parent locking are not included.
