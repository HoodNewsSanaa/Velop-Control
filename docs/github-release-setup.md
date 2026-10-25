# Publishing and future updates

Target repository: `HoodNewsSanaa/Velop-Control`. The updater reads public GitHub releases; it does not contain a GitHub token. A release must include exactly `Velop-Control-VERSION.apk` for a numeric `vMAJOR.MINOR.PATCH` tag. It must be published rather than draft/prerelease, and GitHub must report the asset's SHA-256 digest.

The Android updater allows the GitHub API endpoint for this repository and HTTPS download redirects to GitHub's release asset hosts only. It checks the APK digest, application ID, increasing version code and installed signing certificate before offering Android installation. It never installs silently. A local-only preference disables automatic GitHub checks; manual checks remain explicit.

The first production-format signing key is separate from all source archives and must be kept private. The temporary 0.2.0 prototype key was not preserved; a one-time uninstall/reinstall is needed to move from that build. Future APKs must use the permanent key.

For the signed-release workflow, configure repository Actions secrets:

- `VELOP_KEYSTORE_BASE64`: Base64 of the private distribution PKCS#12 keystore.
- `VELOP_STORE_PASSWORD`: its password.

Never commit either value or the private keystore. Run **Signed Android release** from Actions with the version tag matching `app/build.gradle`. The workflow runs tests and lint, builds the signed APK, publishes its checksum and creates the latest GitHub release. Re-running a published version does not silently overwrite it.

Until repository creation, source upload, signing-secret configuration and release publication are actually completed, a packaged workflow is only preparation. Do not claim that GitHub already hosts this project or that end-to-end updates have been tested against a real release.
