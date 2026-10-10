# AGENT.md

Instructions for AI agents working in this repository.

## What this repo is

- `app/` — Android app (Kotlin). Registers as an Android Auto **weather** app (so a real
  navigation app keeps the navigation slot) and mirrors the phone screen into the car's map
  surface. See `README.md` for how that works and its limits.
- `CHANGELOG.md` — user-facing changelog, in **English**. One `## <version>` section per release.
- `CHANGELOG-beta.md` — changelog for beta builds.
- Distribution is **GitHub Releases**: each version is tagged, built with `:app:assembleRelease`,
  and attached as `MyCar-<version>.apk`. See [Releases](#step-4--publish-the-release).

## Branches and channels

Never publish a beta from `master`. Work on a branch:

1. **Create a branch first** (`git checkout -b <name>`) before touching code.
2. **Any branch other than `master` always ships a beta** — `:app:assembleBeta`, a GitHub
   **prerelease**, and the beta channel. Never publish a stable release from a branch.
3. **`master` is the stable line.** Only a change merged into `master` may reach the stable
   release (`:app:assembleRelease`, GitHub `/latest`, the stable in-app channel).

While on a branch, the [Beta builds](#beta-builds) path is mandatory; the stable steps below only
apply once the branch is merged into `master`.

## The rule: after every change

Every user-visible change must go through this loop **before you consider the task done**:

1. Bump the version.
2. Update `CHANGELOG.md`.
3. Test and build.
4. Publish the release.

Skipping the version bump means the change is **not available for download** — the release, and
the in-app updater, only offer a build whose `versionCode` is strictly greater than the installed
one. A rebuilt APK with the same `versionCode` is invisible to users.

## Step 1 — Bump the version

Edit `app/build.gradle.kts` (`defaultConfig`):

```kotlin
versionCode = 21     // always +1
versionName = "1.16" // increment the last segment, e.g. 1.15 -> 1.16
```

Keep `versionCode` and `versionName` in lockstep with the newest `## <version>` section in
`CHANGELOG.md`.

## Step 2 — Update `CHANGELOG.md`

- Add a new section at the **top**, below `# MyCar`, e.g. `## 1.16`.
- Never edit or renumber a section that already shipped; only add a new one.
- One bullet per user-visible change, matching existing style.
- The release notes are taken from this section.

## Step 3 — Test and build

```sh
./gradlew :app:assembleRelease
```

- Build output: `app/build/outputs/apk/release/MyCar-<versionName>.apk`
- The APK is signed with the project release key — see [Signing](#signing).
- Verify the signature before publishing:

  ```sh
  "$ANDROID_HOME/build-tools/$(ls "$ANDROID_HOME/build-tools" | sort -V | tail -1)/apksigner" \
    verify --print-certs app/build/outputs/apk/release/MyCar-<versionName>.apk
  # certificate DN: CN=Marko Stipanov, O=MyCar, C=HR
  ```

## Step 4 — Publish the release

Create the GitHub Release: tag = `versionName`, title = `MyCar-v<versionName>`, asset = the APK.

```sh
gh release create 1.16 app/build/outputs/apk/release/MyCar-1.16.apk \
  --title "MyCar-v1.16" --latest \
  --notes "<the 1.16 section of CHANGELOG.md>"
```

Done when the release appears at
https://github.com/mstipanov/MyCar/releases/latest with the APK attached.

## Signing

Release (and beta) builds are signed with a private key that is **not** in this repository.

- Keystore: `~/.android/keystores/mycar-release.jks` — PKCS12, alias `mycar`, RSA 4096, valid
  ~27 years.
- Credentials: `keystore.properties` at the repo root, **gitignored**. It holds `storeFile`,
  `storePassword`, `keyAlias` and `keyPassword`.
- `app/build.gradle.kts` reads that file when it exists and leaves release builds unsigned when it
  does not, so a fresh checkout still configures.

**Back up the keystore and its password somewhere safe.** If they are lost you can never update an
installed app again: Android rejects an APK whose signature differs from the installed one, and a
new key would force every user to uninstall and reinstall. Never commit `keystore.properties`,
`*.jks` or `*.keystore` — they are in `.gitignore` for exactly this reason.

## Beta builds

Beta builds are cut the same way but are published as a GitHub **pre-release** rather than the
stable release:

1. Bump `versionCode` **and** the `-betaN` suffix in the `beta` build type
   (`app/build.gradle.kts`).
2. Add the `## <versionName>` section to `CHANGELOG-beta.md` (e.g. `## 1.16-beta1`).
3. `./gradlew :app:assembleBeta` → `app/build/outputs/apk/beta/MyCar-<versionName>.apk`.
4. `gh release create <version> app/build/outputs/apk/beta/MyCar-<versionName>.apk \
     --title "MyCar-v<versionName>" --prerelease --notes "<the beta changelog>"`.

Beta shares the stable package and **signature** (both use the release key), so a beta installs
over the stable app on a tester's phone, and vice versa.

## In-app updates

`app/.../update/UpdateManager.kt` calls `<baseUrl>/version` and compares `versionCode` to the
installed one. The base URL is baked in per build type: stable `https://mycar.sting.hr`, beta
`https://mycar-beta.sting.hr` (`UPDATE_BASE_URL` in `app/build.gradle.kts`). That endpoint is
served outside this repository and is unaffected by GitHub Releases.

The distributor's MyCar stable channel serves `app/build/outputs/apk/release`, so it hands out the
same **release-key-signed** APK as the GitHub Release — publish with `:app:assembleRelease`, never
`:app:assembleDebug`. A debug-signed APK (or a debug-signed Smart Notify-style channel) cannot
install over the release-signed app; Android rejects it with a signature mismatch.

The **page** at `https://mycar.sting.hr` (and `/index.html`) redirects to the GitHub Releases
page. Only the updater endpoints (`/version`, `/apk/*`) stay on that host; the redirect is served
both by the distributor's `app.mycar.redirect` and by a Cloudflare edge redirect rule (needed
because Cloudflare Access protects `/`).

- `response.versionCode <= installedCode` → up to date (no download offered).
- A dismissed version is remembered, so re-publishing the **same** `versionCode` never re-prompts.

Therefore: **always increment `versionCode`**.

Before installing, the APK is verified against the expected package name **and** `versionCode`.
That check is what turns "the server returned a login page instead of an APK" into a clean
"update failed" message.

## House rules

- Keep changes consistent with surrounding style, naming and patterns.
- Do not touch unrelated uncommitted work; leave it alone.
- Prefer dedicated tools over ad-hoc shell when editing files.
- Run the gate in Step 3 before claiming a change is complete.
