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
- `apps/swappuzzle` is Swap Puzzle: match a hidden row of 4 to 9 icons by
  swapping, told only how many are right after each swap.
- `apps/eightball` is Magic 8 Ball: a 20-sided die with the classic answers
  rises out of black fog when the screen faces up, sinks away when it faces
  down, and tumbles to a new answer when you shake the phone or tap. Icons
  at the bottom switch to a red ball of swearing and a green ball of dinner
  ideas.
- `apps/irontide` is Iron Tide: the classic naval guessing game against the
  computer. Place five ships on your grid, then trade single shots on two
  10x10 oceans until one fleet is gone. Splashes for misses, fire for hits,
  synthesized sound, and an easy, medium or hard admiral to play against.
- `apps/` holds one module per app. Each one builds its own APK.
- `CLAUDE.md` tells a Claude Code session how to add an app and cut a release.
