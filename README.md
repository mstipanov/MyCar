# MyCar

[![Latest release](https://img.shields.io/github/v/release/mstipanov/MyCar?sort=semver&label=release)](https://github.com/mstipanov/MyCar/releases/latest)
[![License: MIT](https://img.shields.io/github/license/mstipanov/MyCar)](LICENSE)

An Android Auto app that shows a live mirror of the phone's own screen. It registers as a
**navigation** app: that is the category the Car App Library requires for the full-bleed
`NavigationTemplate`, and it lets Android Auto treat MyCar as the car's default/last navigation
app. The trade-off is the car's single navigation slot, which MyCar now shares with Google
Maps/Waze.

Personal sideload project. It deliberately does things Google Play does not allow, so it can
never be published — see [Caveats](#caveats).

## How it works

Android Auto gives you no way to render arbitrary UI. The one hole is the map surface that
navigation, point-of-interest and weather apps get:

```
  phone screen
        |
        |  MediaProjection  (consent prompted by MainActivity)
        v
  VirtualDisplay --> ImageReader --> FrameStore        <- capture/ package
                                        |
                                        |  (raw frames, stride-corrected)
                                        v
  MirrorSurfaceCallback  -->  Surface  <-- handed over by Android Auto
                                        |   because we declare NAVIGATION
                                        |   + NAVIGATION_TEMPLATES
                                        |   + ACCESS_SURFACE
                                        v
                                   car display
```

* `car/MirrorCarAppService` — the service Android Auto binds to. Its intent filter declares
  `androidx.car.app.category.NAVIGATION`, so the app is listed as a navigation app and is
  eligible to be the car's default/last navigation app.
* `car/MirrorSession` / `car/MirrorScreen` — returns a bare `NavigationTemplate` so the host
  reserves its map area, then registers the surface callback that claims that area.
  `NavigationTemplate` is the only full-bleed map template — the `MapWithContentTemplate` a
  weather app would normally use always splits the screen with a mandatory content pane. The
  `NAVIGATION_TEMPLATES` permission is what gates it; the category decides which launcher it
  appears in.
* `car/MirrorSurfaceCallback` — the "map". Runs a 30 fps loop that letterboxes the newest
  captured frame into the surface.
* `capture/ScreenMirrorService` — foreground service that owns the `MediaProjection` and
  feeds `FrameStore`. If the system ends capture by itself while Android Auto is connected, it
  asks for it again and reopens the chosen app.
* `capture/FrameStore` — single-slot, lock-protected hand-off so no bitmap is allocated
  per frame.
* `MainActivity` — the phone screen where capture permission is granted and the settings live.
* `touch/TouchInjectorService` — accessibility service that injects the taps you make on the
  mirror (so the car touchscreen can drive the phone) and presses Share on the system capture
  prompt for hands-free start.
* `auto/CarConnectionWatcher` / `auto/BootReceiver` — a quiet foreground service that starts
  mirroring the moment Android Auto connects and stops it when the car disconnects, and comes
  back after a reboot.
* `Settings.kt` / `RotationLock.kt` — the app's stored options (keep screen on, lock landscape,
  touch control, auto-start) and the system-rotation helper behind the landscape lock.

The `app/build.gradle.kts`, the manifest and `res/xml/automotive_app_desc.xml` are the parts
that must be exactly right for Android Auto to list the app at all.

## Build and install

```sh
./gradlew :app:installDebug
```

## Download and releases

Grab the latest APK from the [Releases page](https://github.com/mstipanov/MyCar/releases/latest) —
`MyCar-<version>.apk`, signed with the project release key and installable with
[KingInstaller](https://github.com/fcaronte/KingInstaller/releases).

Each release is built from the tagged source and published by hand:

```sh
./gradlew :app:assembleRelease
gh release create <version> app/build/outputs/apk/release/MyCar-<version>.apk \
  --title "MyCar-v<version>" --notes "<the changelog for this version>"
```

`<version>` is the `versionName` in `app/build.gradle.kts` and the newest `## <version>` section in
`CHANGELOG.md`; the release notes come from that section. `AGENT.md` documents the full loop.

### In-app updates

Installed builds can update themselves: `update/UpdateManager.kt` polls a `/version` endpoint,
downloads the newer APK and verifies the package name and `versionCode` before handing it to the
system installer. The updater is dependency-free (`HttpURLConnection` + `org.json`), so the app
pulls in no HTTP or serialization stack just to update itself.

### Beta builds

New features can be tried by testers before they reach stable. The `beta` build type produces a
pre-release APK:

```sh
./gradlew :app:assembleBeta     # -> app/build/outputs/apk/beta/MyCar-<version>-betaN.apk
```

A beta shares the stable package **and signature** (both are signed with the release key), so
installing it replaces the stable app on the tester's phone. Beta notes live in
`CHANGELOG-beta.md`.

## Enable it in Android Auto

Download the APK from the [latest release](https://github.com/mstipanov/MyCar/releases/latest) and
install it with [KingInstaller](https://github.com/fcaronte/KingInstaller/releases) — Android Auto only lists
apps installed by a trusted store, so a plain sideload will not show up (the download page says
the same). Then:

1. On the phone, open **Android Auto** settings.
2. Scroll to **Version** and tap it ~10 times to unlock **Developer mode**.
3. Open the overflow menu → **Developer settings**.
4. Turn on **Unknown sources** (sometimes worded *"Allow apps from unknown sources"*). This
   is the toggle that lets a sideloaded app appear; without it Android Auto only lists
   Play-approved apps.
5. On the phone, open **MyCar**. With **Start automatically when Android Auto connects** on
   (the default), grant **Display over other apps** and enable the **MyCar** accessibility
   service once, and mirroring will start by itself from then on — see
   [Hands-free start](#hands-free-start). To start a one-off session manually instead, tap
   **Start mirroring** and approve the capture prompt. Only the phone UI can ask for capture —
   Android Auto does not show a car app's dialogs.
6. Connect to the car and open **MyCar** from the navigation launcher. As a navigation app it
   shares that slot with Google Maps/Waze, and Android Auto may open it by itself once it is the
   last navigation app used.

Capture survives the phone screen turning off, but **not** the phone being locked or the
projection being revoked. When capture stops on its own while Android Auto is still connected —
a phone call or a lock, say — MyCar asks for it again by itself and reopens the chosen app, as
long as *Display over other apps* is granted (see [Hands-free start](#hands-free-start)).

## Phone settings

* **Keep the screen on while mirroring** — holds a screen wake lock, so the mirror keeps showing
  content instead of the phone sleeping (which would mirror a black screen).
* **Lock the screen to landscape while mirroring** — forces the device rotation and turns
  auto-rotate off for the session. Needs the *Modify system settings* special access; the
  previous auto-rotate setting is restored when mirroring stops.
* **Car screen scaling** — how the mirrored screen is fitted into the map area. *Fit* shows the
  whole phone screen with black bars; *Fill height* crops the sides (best full screen); *Fill
  width* crops the top and bottom (best when a side panel narrows the area); *Fill* (the default)
  crops to fill the whole area. Read by the renderer every frame, so it applies immediately.
* **Control the phone from the car touchscreen** — forwards taps, pans (swipe/scroll) and
  pinch-to-zoom on the mirror to the phone through the app's accessibility service. Sideloaded
  apps need *Allow restricted settings* (Settings → Apps → MyCar → ⋮) before they can be switched
  on in Accessibility. Taps and pinches land exactly where you touch; a pan has no touch-down
  position from the car, so it rides a virtual finger and is best-effort (see
  [Caveats](#caveats)).
* **Start automatically when Android Auto connects** — the mirror appears without touching the
  phone, and mirroring stops by itself once Android Auto disconnects. See
  [Hands-free start](#hands-free-start).
* **Open an app when mirroring starts** — pick any installed app (navigation, music, …) and MyCar
  opens it as soon as the mirror is live, so the car screen shows that app instead of the phone's
  home screen. When it fires from the background it uses the same *Display over other apps*
  permission; the chosen package and label are stored in the app's settings.
* **Show the quick launch menu on the car screen** — a slim, icon-only strip of shortcuts (Google
  Maps, Waze, YouTube) beside the mirror: a column on the right, or along the bottom while the
  media panel is open. The strip gets its own space, so it never covers the mirror. Tapping an
  icon opens that phone app (needs *Display over other apps*, or the MyCar accessibility service);
  a rotate button flips the phone between portrait and landscape (needs *Modify system settings*).

### Hands-free start

When **Start automatically when Android Auto connects** is on, a small foreground service
(`auto/CarConnectionWatcher`) subscribes to Android Auto's connection feed. The moment the car
connects it launches the phone UI, which asks for capture; the app then presses Share for you.
When the car disconnects it stops capture again, so nothing keeps mirroring to a car that is
gone. If capture stops by itself while the car is still connected — a phone call, a screen lock
— MyCar asks for it again and reopens the chosen app once the phone is awake and unlocked. Three
pieces make the connect side work, in order of importance:

1. **Display over other apps** (`SYSTEM_ALERT_WINDOW`) — required, because the app is in the
   background when the car connects and Android otherwise blocks it from starting the consent
   screen. The auto-start setting asks for this the first time it is enabled.
2. **The MyCar accessibility service** — it presses Share on the system capture prompt. It is the
   same service used for car touch; enabling it now also turns on `canRetrieveWindowContent`, but
   the code only ever looks at SystemUI's projection window.
3. **The `PROJECT_MEDIA` app-op** (optional, best) — makes the prompt grant itself silently, with
   no window to press at all. Grants are per session and cannot be set from inside an app, so it
   is a one-time `adb` command:

   ```sh
   adb shell cmd appops set --user 0 com.example.mycar PROJECT_MEDIA allow
   ```

   This is the only path that removes *every* tap. Without it, the accessibility service still
   makes it hands-free, just with a prompt that flashes up and is answered automatically.

## Testing without a car

Use the Desktop Head Unit:

1. SDK Manager → **SDK Tools** → install **Android Auto Desktop Head Unit Emulator**.
2. On the phone: Android Auto → Developer settings → **Enable desktop head unit**.
3. `adb forward tcp:5277 tcp:5277`
4. `$ANDROID_HOME/extras/google/auto/desktop-head-unit`

The DHU also has an instrument cluster mode (Ctrl+K) if you want to see the cluster side.

## Caveats

* **Sideload only.** Android Auto only runs apps installed from a trusted store (Google Play,
  ONE store), so a sideloaded build needs an installer that impersonates one (KingInstaller).
  The navigation category with the `NAVIGATION_TEMPLATES` permission is Play-valid, but the app
  still cannot ship: it mirrors the whole screen and never actually navigates, and
  `createHostValidator()` returns `ALLOW_ALL_HOSTS_VALIDATOR` precisely because no host trusts
  this build.
* **A consent grant is required per capture session.** Android does not let `MediaProjection`
  permission persist. MyCar makes it hands-free two ways — an accessibility service that answers
  the prompt, or the `PROJECT_MEDIA` app-op (one-time `adb`) that grants silently. With neither,
  it still needs a tap.
* **`FLAG_SECURE` content is black.** Banking apps, DRM video and some password fields render
  as black boxes. This is enforced by the system and is not a bug in this code.
* **The permission prompt is not shown in the car.** Android Auto only mirrors the app's
  template, not its dialogs, so the prompt appears on the phone. Auto-start brings it up and
  answers it without you touching the phone.
* **Taps and pinches are exact; pans are approximate.** With *Control the phone from the car
  touchscreen* on, a tap is injected where you tapped and a pinch is centred where you pinched —
  the Car App Library supplies both coordinates. A pan does not: it reports only movement deltas,
  so the drag is applied to a virtual finger (screen centre, re-anchored by your last tap) rather
  than under your actual finger. It works for scrolling, but can act on the wrong element.

## Where to tweak

* Frame rate — `FRAME_INTERVAL_MS` in `car/MirrorSurfaceCallback.kt` (33 ms ≈ 30 fps).
* Scaling — the **Car screen scaling** setting chooses between `FIT` and the `FILL_*` modes; the
  transform itself is `computeDestination()` in `car/MirrorSurfaceCallback.kt`. Under *Fit* a
  portrait phone screen in a landscape car area will always leave large black bars unless you
  also rotate it.
* Capture resolution — `realDisplaySize()` in `capture/ScreenMirrorService.kt`. Capping the
  long edge here is the cheapest way to cut CPU and memory if the mirror stutters.
* Pan direction and feel — `PAN_SIGN`, `PAN_DISPATCH_MS`, `PAN_DURATION_MS` and `FLING_SECONDS` in
  `car/MirrorSurfaceCallback.kt`. If panning comes out mirrored on your car, flip `PAN_SIGN`.

## Troubleshooting

* **App does not appear in Android Auto** — *Unknown sources* is off, the build was not installed
  by an installer that impersonates the Play Store (*KingInstaller*), or the head unit was
  connected before the app was installed (reconnect it). Confirm the merged manifest still
  declares the `androidx.car.app.category.NAVIGATION` category plus the `NAVIGATION_TEMPLATES` and
  `ACCESS_SURFACE` permissions:
  `app/build/intermediates/merged_manifest/debug/processDebugMainManifest/AndroidManifest.xml`
* **Car screen stays black, never letterboxes** — no frames are arriving, so capture is not
  running. Check for the foreground notification and re-approve capture.
* **Car screen is black but the notification is present** — the phone is locked, or the
  foreground activity is `FLAG_SECURE`.
* **`onSurfaceAvailable` never fires** — the host has not handed over the map surface. Some hosts
  only do that once an app takes navigation focus. This build declares the navigation category
  but never calls `NavigationManager.navigationStarted()`, so on those hosts open MyCar from the
  navigation launcher first.
* **Skewed / diagonal image** — the capture buffer's `rowStride` is not being honoured. That
  logic lives in `FrameStore.update()`; do not "simplify" it.
* **`Surface.lockCanvas` throws** — fall back to the documented alternative: create a
  `VirtualDisplay` on `surfaceContainer.surface` and render a `Presentation` into it. The
  Car App Library "Draw maps" guide has that example.
