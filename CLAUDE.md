# android-app-studio

A phone app factory. Mark describes an app out loud, Claude Code writes it
here, GitHub Actions builds and signs it, and the APK shows up on the
repository's Releases page for Mark to download and install.

There is no Android Studio in this loop, and no local Android SDK. The build
happens on the GitHub runner. Nothing in this repository should assume a local
emulator, a connected device, or `adb`.

## Layout

```
apps/<name>/              one module per app
  build.gradle.kts        versions, applicationId, signing fallback
  src/main/AndroidManifest.xml
  src/main/java/studio/apps/<name>/MainActivity.kt
  src/main/res/
build.gradle.kts          plugin versions only
settings.gradle.kts       the include() line for each app
gradle/libs.versions.toml all library and plugin versions
.github/workflows/release-apk.yml
SIGNING.md                Mark's one-time keystore setup
```

`apps/hello` is the template. It is a single-activity Jetpack Compose app with
one screen.

## Adding a new app

1. Copy `apps/hello` to `apps/<name>`, using a short lowercase name with no
   spaces.
2. Rename the package folder under `src/main/java/studio/apps/` to
   `<name>` and change the `package` line at the top of every Kotlin file to
   match.
3. In `apps/<name>/build.gradle.kts`, set `namespace` and `applicationId` to
   `studio.apps.<name>`. The `applicationId` is the app's identity on the
   phone, so it must differ from every other app here or the new app replaces
   an old one.
4. Set `versionCode = 1` and `versionName = "1.0"`.
5. In `apps/<name>/src/main/res/values/strings.xml`, set `app_name` to the
   name Mark will see on his home screen.
6. Add `include(":apps:<name>")` to `settings.gradle.kts`.
7. Write the screens.

Keep the `releaseKeystore` value and the `signingConfigs` and
`buildTypes { release { ... } }` blocks exactly as the template has them. They
read the keystore from environment variables CI sets, and fall back to the
debug key when it is absent.

## Changing an existing app

Bump `versionCode` by one in that app's `build.gradle.kts` on every change
Mark is going to install. Android refuses to install over an app with the same
or a higher version code, and the failure message on the phone says nothing
useful about why.

Update `versionName` when the change is worth a name ("1.1", "2.0"). It is
cosmetic.

## Releasing

Push to `main`. The workflow builds every app module, signs with the release
keystore when the repository secrets are set, and publishes a GitHub Release
tagged `build-<run number>` with each app's APK attached.

Nothing else triggers a release. There is no manual tagging step, and the
version code in the module is independent of the release tag.

When a build fails, read the failing step's log in the Actions tab, fix the
cause here, and push again. Do not disable a step or a check to get a green
build.

## Verifying a change without a phone

`./gradlew assembleRelease` is the whole check. It runs on the runner and
needs the Android SDK, which the runner has and a cloud session does not. A
cloud session can still review the Kotlin, the manifest, and the Gradle files
before pushing, and should, because every push costs Mark a wait.

## Rules

- Never commit a keystore, a password, or a `local.properties`. The
  `.gitignore` covers the usual names. Read `SIGNING.md` before touching
  anything about signing.
- Never put a signing password in a build file, a workflow file, or a comment.
  The four values come from repository secrets, by the names in `SIGNING.md`.
- Keep `minSdk` at 26 or higher. The launcher icons are adaptive-icon XML with
  no PNG fallback, which is what lets this repository hold no binary assets.
- One app per module. Do not add a second launcher activity to an existing app
  to make it two apps.
- Prefer Compose and Material 3. The template has no XML layouts and should
  stay that way.
