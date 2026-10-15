# MyCar (beta)

Beta builds ship features before they reach the stable channel. The app is the same package, so
installing a beta replaces the stable build on your phone.

## 1.37-beta1
- "Close an app when Android Auto disconnects" now stops Radarbot through the *stop* action on its own notification instead of force-stopping it, so Android's app-info Settings screen no longer opens (and can no longer be left behind). Needs Notification access; other apps are unchanged

## 1.36-beta1
- The mirror now fills the whole car screen edge to edge, the way native Android Auto apps do: the black frame that used to surround it (the host insets its map area for its own bars) is gone

## 1.35-beta1
- Dragging on the car screen now moves a floating Picture-in-Picture window (like YouTube's) instead of the app behind it

## 1.33-beta17
- Fix the pan direction: dragging now moves the mirror the same way your finger does, instead of the opposite way

## 1.33-beta16
- Fixed drag-to-scroll from the car screen: a pan no longer jumps, drifts or lands as a stray tap, and a drag no longer overshoots when you let go

## 1.33-beta15
- Fix announcement pausing only working for the first alert: the paused state is now recomputed on every audio change (a media player with no media session) instead of a latch that could get stuck

## 1.33-beta14
- Remove the desk-test override; announcement pausing now acts only while mirroring, as intended

## 1.33-beta13
- Restore the desk-test override so announcement pausing can be checked away from the car (it only affects debuggable builds)

## 1.33-beta12
- Finalise announcement pausing: the desktop-only debug override and the unused notification path are removed

## 1.33-beta11
- Announcement pausing now stays paused while the alert is still playing and resumes once it ends, instead of using a fixed hold

## 1.33-beta10
- Detect Radarbot's untagged spoken alerts by the brief overlap of two media players (YouTube + Radarbot), holding the media paused briefly

## 1.33-beta9
- Announcement pausing now also triggers on Radarbot's alert **notification**, because its spoken alerts are played as plain media with no distinguishing audio attributes

## 1.33-beta8
- The announcement watcher now starts with the app process (not only when the settings screen or mirroring opens), and the diagnostic always shows the live state and app version

## 1.33-beta7
- The announcement diagnostic now keeps its last result after mirroring stops and watches whenever the setting is on (it only *acts* while mirroring), so it can be read on the phone after a drive

## 1.33-beta6
- Announcement pausing now also watches media **sessions**, so a "News & Talk / Speech" app like Radarbot is detected even when its audio player is not tagged as speech; the diagnostic now lists the active sessions too

## 1.33-beta5
- Announcement pausing now also triggers on a **speech** content type (Radarbot's "News & Talk" stream), and no longer pauses a speech stream by mistake

## 1.33-beta4
- The announcement-pause diagnostic now always shows the audio usages MyCar sees, so an unmatched announcement is diagnosable instead of silent

## 1.33-beta3
- "Pause media during announcements" now also starts when you switch it on mid-session, and shows a diagnostic line with what the last announcement did (which audio usage it saw, and whether it paused)

## 1.33-beta2
- Fixed the up/down/remove buttons in the app-list editor being invisible in dark mode

## 1.33-beta1
- New "Pause media during announcements": YouTube or music pauses while a navigation or alert app (Radarbot, Maps, Waze) speaks, then resumes. Needs notification access; active while mirroring

## 1.32-beta2
- Fixed the car launcher's rotate button needing two taps the first time: it now flips based on the phone's actual orientation instead of a stale stored value

## 1.32-beta1
- Settings polish: buttons are no longer all-caps, secondary actions use a tinted style so they no longer look disabled, and the close-app hint is shorter

## 1.31-beta3
- Dark mode: the phone screen now has a light and a dark look, with an Appearance setting to pick Light, Dark, or Match system

## 1.31-beta2
- The settings screen has a cleaner look: a header with the live status, and the controls grouped into labelled cards (Mirroring, Hands-free, Car screen, Updates)

## 1.31-beta1
- "Open an app when mirroring starts" is now a list: pick several apps and MyCar opens them one after another, so the last one is on screen and the others run behind it

## 1.30-beta1
- New "Close an app when Android Auto disconnects": pick an app and MyCar closes it when the car disconnects; tick "Force stop it" to stop its media and background work too (needs the accessibility service)

## 1.28-beta1
- The Android Auto category is now selectable in the app: Navigation (default) or Weather. Switching takes effect when Android Auto reconnects

## 1.27-beta1
- "Choose launcher apps" is now an editor: reorder the car launcher shortcuts with up/down arrows, remove them, and add more apps

## 1.26-beta1
- Dropped Waze from the car launcher and pinned the rotate button to the bottom of the strip
- New "Choose launcher apps" option: pick which apps appear on the car launcher

## 1.25-beta1
- Fixed the launcher's rotate button doing nothing: MyCar now asks for "Modify system settings" (the permission it needs to rotate) when the quick launch menu is switched on

## 1.24-beta1
- The car quick launch strip now has a rotate button that flips the mirrored phone between portrait and landscape

## 1.23-beta1
- The car quick launch menu is now a slim icon-only strip that never covers the mirror: a column on the right, or along the bottom when a media/assistant panel is open

## 1.22-beta1
- MyCar now registers as an Android Auto **navigation** app instead of a weather app, so it appears in the navigation launcher and Android Auto can open it by itself; it shares the navigation slot with Google Maps/Waze

## 1.21-beta1
- New app icon: a car with a lit-up screen, replacing the default placeholder art

## 1.20-beta1
- The quick launch menu is now always shown on the right of the car screen instead of appearing on a tap, and is styled like the Android Auto app list
- Opening an app from the menu now works: it needs "Display over other apps" (or the MyCar accessibility service), which is asked for when the menu is switched on

## 1.19-beta1
- New quick launch menu on the car screen: tap the mirror to bring up shortcuts for Google Maps, Waze and YouTube and open one on the phone; it hides again after a few seconds
- New option "Show the quick launch menu on the car screen" (on by default)

## 1.15-beta1
- The car screen can now drive more than taps: swipe to pan/scroll and pinch to zoom
- Pinch maps exactly to where you pinch; panning uses a virtual finger (the car only reports
  movement, not where your finger is), so it is best-effort and may need tuning per car

## 1.14-beta4
- The "open an app" picker now lists all your apps (Android 11+ was hiding most of them) and has a
  search box

## 1.14-beta3
- New option: open a chosen app automatically once mirroring starts, so the car mirror comes up on
  it (for example a navigation or music app) instead of the phone's home screen

## 1.14-beta2
- Hands-free start: mirroring now begins by itself the moment Android Auto connects — no need to
  open the app or tap Start
- The system capture prompt comes up already set to "Share entire screen", and MyCar presses Share
  for you (through its accessibility service)
- Needs "Display over other apps" so MyCar can start the prompt from the background; the
  accessibility service (already used for car touch) also accepts the prompt
- New setting "Start automatically when Android Auto connects" (on by default)

## 1.14-beta1
- Beta channel preview build (no feature change yet): verifies the beta download page and in-app
  updates before the hands-free update lands here
