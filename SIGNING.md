# Signing setup

Do this once. After that every build is signed with the same key, and new
versions install over old ones on your phone.

The keystore and its passwords stay on your computer and in GitHub secrets.
They are never committed, and Claude never holds them.

## 1. Create the keystore

On your own computer, in a folder you will not lose, run:

```
keytool -genkeypair -v \
  -keystore release.jks \
  -storetype JKS \
  -keyalg RSA \
  -keysize 2048 \
  -validity 10000 \
  -alias appstudio
```

`keytool` ships with any JDK. If the command is not found, install a JDK
(Temurin 17 or later) and try again.

It asks for a keystore password, then some name and organization fields, then
whether to reuse the keystore password for the key. Answer yes to that last
question, so the store password and the key password are the same. Write the
password down somewhere safe. Losing it means losing the ability to update
these apps.

Back `release.jks` up. If you lose it you cannot install an update over an
existing app, only uninstall and start again.

## 2. Turn the keystore into text

GitHub secrets hold text, so encode the file:

macOS or Linux:

```
base64 -i release.jks | tr -d '\n' > release.jks.base64
```

Windows PowerShell:

```
[Convert]::ToBase64String([IO.File]::ReadAllBytes("release.jks")) > release.jks.base64
```

## 3. Add four repository secrets

Go to the repository on GitHub, then Settings, then Secrets and variables,
then Actions, then New repository secret. Add these four:

| Secret name | Value |
| --- | --- |
| `RELEASE_KEYSTORE_BASE64` | the whole contents of `release.jks.base64`, one line, no spaces |
| `RELEASE_KEYSTORE_PASSWORD` | the keystore password from step 1 |
| `RELEASE_KEY_ALIAS` | `appstudio` |
| `RELEASE_KEY_PASSWORD` | the same password, if you answered yes in step 1 |

## 4. Check it worked

Go to the Actions tab, open the latest run of "Build signed APKs", and run it
again from the "Re-run all jobs" button. When the keystore is in place the log
reads "Signing with the release keystore." The release notes on the new
release say the same.

Until the secrets are set, the build still runs and still publishes an APK.
It is signed with the throwaway debug key, which is fine for trying the
pipeline but cannot be updated in place later.

## Installing on your phone

1. Open the repository's Releases page in Chrome on the phone.
2. Download the `.apk` file from the newest release.
3. Chrome asks whether to allow installs from this source. Allow it. On
   Android 8 and later the permission is per app, granted to Chrome, under
   Settings, Apps, Chrome, Install unknown apps.
4. Open the downloaded file and install.

Later builds install over the top as long as the version code went up and the
signing key is the same.
