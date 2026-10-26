# android-app-studio

Describe a phone app, get an APK.

Say what you want to a Claude Code session on this repository. It writes the
app, pushes to `main`, and GitHub Actions builds a signed APK and publishes it
as a release. Open the Releases page on your phone, download the APK, install
it.

## First time

Read `SIGNING.md` and do the four steps there. It takes about five minutes and
creates the signing key that lets later builds install over earlier ones.

Until then the build still works. The APK it publishes is signed with a
throwaway debug key, which installs fine but cannot be updated in place.

## Getting a build on your phone

1. Open this repository's Releases page in Chrome on the phone.
2. Download the `.apk` from the newest release.
3. Allow Chrome to install apps when it asks.
4. Open the download and install.

## What is here

- `apps/hello` is the starter app: one Compose screen, nothing else.
- `apps/` holds one module per app. Each one builds its own APK.
- `CLAUDE.md` tells a Claude Code session how to add an app and cut a release.
