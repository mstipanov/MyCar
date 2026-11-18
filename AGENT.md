# AGENT.md

Instructions for AI agents working in this repository.

## What this repo is

- `app/` — Android app (Kotlin). Registers as an Android Auto app and mirrors the phone screen
  into the car's map surface. Its single Android Auto category — **navigation** or **weather** — is
  chosen in-app. See `README.md` for how that works and its limits.
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

While on a branch, the [Beta builds](#beta-builds) path is mandatory. The stable release steps
apply only after the beta is approved **and** the branch is merged into `master`.

## The rule: beta first, release only after approval

Every user-visible change goes through the beta loop **before you consider the task done**:

1. Bump the version for a beta.
2. Update `CHANGELOG-beta.md`.
3. Test and build the beta.
4. Publish the beta as a GitHub pre-release and verify the beta channel.
5. **Stop.** Do not build or publish the stable release in the same pass.

The stable release is a separate step. Cut it only after Marko has installed and tested the beta
and explicitly confirmed that it is OK ("može", "sve je ok", "objavi stable"). Until then, leave
the stable channel on the previous approved build. If he asks for changes, cut another beta of the
same version with a higher `versionCode`; never rebuild the same beta name.

Skipping the version bump means the change is **not available for download** — the beta, the
release, and the in-app updater only offer a build whose `versionCode` is strictly greater than the
installed one. A rebuilt APK with the same `versionCode` is invisible to users.

## Step 1 — Bump the version

Versions follow the shared numbering: a version starts as `<major>.<minor>-beta1` and the release
drops the suffix; another beta of the same version only bumps `-betaN`. `versionCode` is a
separate counter that increases for **every** published build. A beta and the release of the same
version must never share a code: the in-app updater only offers strictly greater codes, so the
release would make the beta invisible (as happened with 1.47 and 1.47-beta1).

Edit `app/build.gradle.kts` (`defaultConfig`):

```kotlin
versionCode = 21     // always +1 per published build
versionName = "1.16" // the upcoming release; the -betaN suffix lives in the beta build type
```

Set `versionNameSuffix` in the `beta` build type to the matching `-betaN`. Keep the version name
in lockstep with the newest `## <version>` section in `CHANGELOG-beta.md` while in beta, and in
`CHANGELOG.md` only when the release is cut after approval.

## Step 2 — Update the beta changelog

- While the version is in beta, add its section to the **top** of `CHANGELOG-beta.md`, below the
  intro, e.g. `## 1.16-beta1`.
- Add the stable `CHANGELOG.md` section only when cutting the release after approval.
- Never edit or renumber a section that already shipped; only add a new one.
- One bullet per user-visible change, matching existing style.
- The beta notes are taken from the beta section; the release notes from the stable section.

## Step 3 — Test and build the beta

```sh
./gradlew :app:assembleBeta
```

- Build output: `app/build/outputs/apk/beta/MyCar-<versionName>.apk`
- The APK is signed with the project release key — see [Signing](#signing).
- Verify the signature before publishing:

  ```sh
  "$ANDROID_HOME/build-tools/$(ls "$ANDROID_HOME/build-tools" | sort -V | tail -1)/apksigner" \
    verify --print-certs app/build/outputs/apk/beta/MyCar-<versionName>.apk
  # certificate DN: CN=Marko Stipanov, O=MyCar, C=HR
  ```

## Step 4 — Publish the beta

Create a GitHub **pre-release**: tag = the beta `versionName`, title = `MyCar-v<versionName>`,
asset = the beta APK. The notes are the beta `CHANGELOG-beta.md` section **followed by the
support footer in `SUPPORT.md`**.

```sh
cat > /tmp/mycar-notes.md <<'EOF'
<the 1.16-beta1 section of CHANGELOG-beta.md>
EOF
cat SUPPORT.md >> /tmp/mycar-notes.md

gh release create 1.16-beta1 app/build/outputs/apk/beta/MyCar-1.16-beta1.apk \
  --title "MyCar-v1.16-beta1" --prerelease --notes-file /tmp/mycar-notes.md
```

Done when https://mycar-beta.sting.hr/version serves the new beta and its APK URL downloads.
**Then stop and wait for approval.**

## Step 5 — After approval, publish the stable release

Only after Marko explicitly approves the beta:

1. Keep the same `<major>.<minor>` version name and bump `versionCode` one more time (the release
   must be newer than the beta it came from).
2. Add the stable `## <versionName>` section to `CHANGELOG.md`.
3. `./gradlew :app:assembleRelease` → `app/build/outputs/apk/release/MyCar-<versionName>.apk`.
4. Verify the signature as in Step 3, against the release APK.
5. Create the GitHub Release: tag = `versionName`, title = `MyCar-v<versionName>`, asset = the
   release APK, notes = the `CHANGELOG.md` section plus `SUPPORT.md`, with `--latest`.

```sh
gh release create 1.16 app/build/outputs/apk/release/MyCar-1.16.apk \
  --title "MyCar-v1.16" --latest --notes-file /tmp/mycar-notes.md
```

Done when https://mycar.sting.hr/version serves the release and the GitHub release appears at
https://github.com/mstipanov/MyCar/releases/latest with the APK attached and the support footer.

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

Beta builds are published as a GitHub **pre-release** rather than the stable release. A published
beta is the end of the change until Marko tests it and approves it; do not also cut the stable
release in the same pass.

1. For a new version, bump the minor in `defaultConfig.versionName`; for another beta of the
   same version keep the name and only raise `versionCode`. Set `versionNameSuffix` in the
   `beta` build type to the matching `-betaN`.
2. Add the `## <versionName>` section to `CHANGELOG-beta.md` (e.g. `## 1.16-beta1`).
3. `./gradlew :app:assembleBeta` → `app/build/outputs/apk/beta/MyCar-<versionName>.apk`.
4. `gh release create <version> app/build/outputs/apk/beta/MyCar-<versionName>.apk \
     --title "MyCar-v<versionName>" --prerelease --notes-file <notes>` — the notes are the beta
   changelog section plus the `SUPPORT.md` footer, as in
   [Step 4](#step-4--publish-the-beta).

When that beta is accepted, release the same version name with the beta suffix removed and one
more `versionCode` bump, so beta testers receive the release as an update too — see
[Step 5](#step-5--after-approval-publish-the-stable-release).

Beta shares the stable package and **signature** (both use the release key), so a beta installs
over the stable app on a tester's phone, and vice versa.

## In-app updates

`app/.../update/UpdateManager.kt` calls `<baseUrl>/version` and compares `versionCode` to the
installed one. The base URL comes from the channel chosen in the phone UI
(`Settings.UpdateChannel`) and defaults to the build's own: stable `https://mycar.sting.hr`, beta
`https://mycar-beta.sting.hr` (`UPDATE_BASE_URL` in `app/build.gradle.kts` sets that default).
The endpoint is served outside this repository and is unaffected by GitHub Releases.

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
