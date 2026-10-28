# Google Drive sync

GBook syncs a library between Android and the desktop through the reader's own Google Drive, so there is no
GBook server. Everything goes in the Drive *app data folder*: hidden from the Drive UI, visible only to GBook,
and counted against the user's Drive storage. What syncs, the file format and the merge rules are in
[`shared/sync/README.md`](../shared/sync/README.md).

| | Android | Desktop |
|---|---|---|
| Code | `data/Sync.kt` (merge), `data/DriveSync.kt` (when and how), `data/DriveApi.kt` (REST) | `core/src/sync.rs` (merge), `app/src/sync.rs` (when and how), `app/src/drive.rs` (sign-in, REST) |
| Sign-in | Google Play services `AuthorizationClient` | Browser, loopback redirect to `127.0.0.1` with PKCE |
| Token | Kept by Play services | Refresh token in the system keyring (Secret Service, Windows Credential Manager), or `sync/token` readable only by the user when there is no keyring |
| Syncs | Coming to the front and every 5 min while open; leaving the app; 3 s after closing a book | Start and every 5 min; window focus after a minute away; 3 s after closing a book; on quit |

## Setting up the Google Cloud project

Sync needs one Google Cloud project that both apps sign in through. It is free, and the scopes GBook asks
for (`drive.appdata` and `email`) are non-sensitive, so Google doesn't need to review the app.

1. At [console.cloud.google.com](https://console.cloud.google.com), create a project (e.g. "GBook") and enable
   the **Google Drive API** (APIs & Services › Library).
2. **Google Auth Platform › Branding:** app name "GBook", a support email, and the GitHub page as the home
   page. **Audience:** External. **Data access:** add `.../auth/drive.appdata` and `email`.
3. Under **Audience**, press **Publish app** to move it to production. While it is in *Testing*, only listed
   test users can sign in and their sign-in expires after 7 days.
4. **Clients › Create client**, once per kind:
   - **Android:** package name `com.gbook` and the SHA-1 of the key that signs the APK. Make one client for
     each key: the release key (`keytool -list -v -keystore <your keystore> -alias <alias>`) and, for
     testing, the debug key (`keytool -list -v -keystore ~/.android/debug.keystore -alias androiddebugkey
     -storepass android`). No ID goes into the Android code; Play services matches the app by package and key.
   - **Desktop app:** this gives a client ID and a client secret. For an installed app Google doesn't treat the
     secret as confidential; it ships inside the binary.
5. Build the desktop app with them:

   ```sh
   export GBOOK_GOOGLE_CLIENT_ID=1234-abc.apps.googleusercontent.com
   export GBOOK_GOOGLE_CLIENT_SECRET=GOCSPX-...
   cargo build --release            # or desktop/packaging/build-packages.sh
   ```

   The same variables at run time override what was built in. In GitHub Actions, add them as repository
   secrets with those names; `.github/workflows/desktop.yml` passes them to the builds.

A desktop build without a client ID shows "Not available in this build" in Settings › Sync. On Android,
signing in with an APK whose key isn't registered fails with "This build of GBook isn't registered with Google
yet".

## Trying it without Google

`scripts/fake-drive.py` stands in for the Google endpoints GBook uses, keeping files in memory. Two desktop
copies with their own data folders can sync through it:

```sh
scripts/fake-drive.py 8765 &
for d in a b; do
  mkdir -p /tmp/gbook-$d/sync
  echo "{\"enabled\":true,\"account\":\"reader@example.com\"}" > /tmp/gbook-$d/sync/state.json
  echo test > /tmp/gbook-$d/sync/token
done
GBOOK_GOOGLE_API=http://127.0.0.1:8765 GBOOK_GOOGLE_CLIENT_ID=test GBOOK_DATA_DIR=/tmp/gbook-a desktop/target/debug/gbook
```

With `FAKE_DRIVE=http://127.0.0.1:8765`, `./gradlew :app:testDebugUnitTest` also runs the Android Drive client
against it. The merge itself is tested on both sides by the shared cases: `cargo test -p gbook-core` and
`./gradlew :app:testDebugUnitTest`.

## Notes

- **Signing out** stops syncing and leaves the library on that device as it is. Signing in again (or with
  another account) starts over with a first sync, where the account's copy wins for items both have.
- **Nothing is overwritten blindly.** Changes to different things on two devices are all kept. A device's
  first sync only fills in what the account is missing. A highlight note edited on both devices before they
  sync keeps both texts. Other fields changed on both take the later sync's value.
- **Earlier versions:** before a sync changes the library, the old one is saved in `sync/history/` (the last
  10). To go back, close GBook and copy one over `library.json`.
- **Removing GBook's data from Drive:** drive.google.com › Settings › Manage apps › GBook › Delete hidden app
  data.
- **Not synced yet:** profile pictures; text size and margins, which stay per device on purpose.
- The Android app now depends on Google Play services for sign-in.
