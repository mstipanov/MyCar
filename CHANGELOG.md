# MyCar

## 1.41
- New Support card in the app with Buy Me a Coffee and PayPal links, so you can back MyCar's development without leaving the app

## 1.40
- When Android Auto disconnects, the MyCar phone screen now steps aside: if it was the screen in front, it goes to the background instead of staying there after the car is gone

## 1.39
- Fixed hands-free features silently switching off after the app updated itself: auto-start, close-on-disconnect and pausing media during announcements now come back by themselves when the new build is installed, instead of waiting for MyCar to be opened or the phone rebooted

## 1.38
- Dragging on the car screen now only moves a floating Picture-in-Picture window (like YouTube's) when the drag starts on the window itself; dragging anywhere else scrolls the app behind it, so a floating window no longer takes every drag

## 1.37
- "Close an app when Android Auto disconnects" now stops Radarbot through the stop action on its own notification instead of force-stopping it, so no Settings screen opens (and none can be left behind); needs notification access, and other apps are unchanged

## 1.36
- The mirror now fills the whole car screen, edge to edge, so the black frame that used to surround it is gone

## 1.35
- Dragging on the car touchscreen now moves a floating Picture-in-Picture window (like YouTube's) instead of the app behind it

## 1.34
- Fixed drag-to-scroll from the car touchscreen: a pan no longer jumps, drifts or lands as a stray tap, and a drag no longer overshoots when you let go
- Dragging now moves the mirror the same way your finger does, instead of the opposite way

## 1.33
- New "Pause media during announcements": YouTube or music pauses while a navigation or alert app (Radarbot, Maps, Waze) speaks, then resumes; needs notification access and works while mirroring
- Dark mode: the app now has a light and a dark look, with an Appearance setting to pick Light, Dark, or Match system
- The settings screen has a cleaner look: a header with the live status, and the controls grouped into labelled cards (Mirroring, Hands-free, Car screen, Audio, Appearance, Updates)
- "Open an app when mirroring starts" is now a list: pick several apps and MyCar opens them one after another, so the last one is on screen and the others run behind it
- Fixed the car launcher's rotate button needing two taps the first time
- Fixed the up/down/remove buttons in the app-list editor being invisible in dark mode

## 1.30
- New "Close an app when Android Auto disconnects": pick an app and MyCar closes it when the car disconnects; tick "Force stop it" to stop its media and background work too (needs the accessibility service)

## 1.29
- MyCar now registers as an Android Auto **weather** app by default, so Google Maps/Waze keep the navigation slot while the mirror is on screen; choose Navigation in the app if you would rather Android Auto auto-open MyCar

## 1.28
- The Android Auto category is now selectable in the app: Navigation (default) or Weather. Switching takes effect when Android Auto reconnects

## 1.27
- "Choose launcher apps" is now an editor: reorder the car launcher shortcuts with up/down arrows, remove them, and add more apps

## 1.26
- Dropped Waze from the car launcher and pinned the rotate button to the bottom of the strip
- New "Choose launcher apps" option: pick which apps appear on the car launcher

## 1.25
- Fixed the launcher's rotate button doing nothing: MyCar now asks for "Modify system settings" (the permission it needs to rotate) when the quick launch menu is switched on

## 1.24
- The car quick launch strip now has a rotate button that flips the mirrored phone between portrait and landscape

## 1.23
- The car quick launch menu is now a slim icon-only strip that never covers the mirror: a column on the right, or along the bottom when a media/assistant panel is open

## 1.22
- MyCar now registers as an Android Auto **navigation** app instead of a weather app, so it appears in the navigation launcher and Android Auto can open it by itself; it shares the navigation slot with Google Maps/Waze

## 1.21
- New app icon: a car with a lit-up screen, replacing the default placeholder art

## 1.20
- The quick launch menu is now always shown on the right of the car screen instead of appearing on a tap, and is styled like the Android Auto app list
- Opening an app from the menu now works: it needs "Display over other apps" (or the MyCar accessibility service), which is asked for when the menu is switched on

## 1.19
- New quick launch menu on the car screen: tap the mirror to bring up shortcuts for Google Maps, Waze and YouTube and open one on the phone; it hides again after a few seconds
- New option "Show the quick launch menu on the car screen" (on by default)

## 1.18
- New "Car screen scaling" setting: Fit, Fill height (crop the sides), Fill width (crop the top and bottom) or Fill (crop to use the whole area); it applies while mirroring, no restart needed

## 1.17
- If sharing stops on its own while Android Auto is connected (a phone call, a screen lock), MyCar now asks for it again and reopens the configured app

## 1.16
- Mirroring now stops by itself when Android Auto disconnects (with auto-start on), instead of keeping the capture running against a car that is gone

## 1.15
- The car screen can now drive more than taps: swipe to pan or scroll, and pinch to zoom
- Pinch maps exactly to where you pinch; panning uses a virtual finger (the car only reports
  movement, not where your finger is), so it is best-effort and may need tuning per car

## 1.14
- Mirroring can start by itself the moment Android Auto connects — no need to open the app or tap Start
- The capture prompt now comes up already set to "Share entire screen", and MyCar can accept it for you through the app's accessibility service
- New option: open a chosen app (navigation, music, …) automatically once mirroring starts; the picker lists all your apps and has a search box
- New setting "Start automatically when Android Auto connects" (on by default); it needs "Display over other apps"
- A one-time `adb shell cmd appops set --user 0 com.example.mycar PROJECT_MEDIA allow` removes the last tap

## 1.13
- The mirror uses the full screen again while MyCar stays a weather app, so Google Maps/Waze keep navigating

## 1.12
- Fixed a crash when opening the app in the car (the weather template needed non-empty content)

## 1.11
- MyCar now registers as a weather app instead of a navigation app, so it can run alongside a real navigation app (Google Maps, Waze) instead of taking the navigation slot

## 1.10
- Removed the "Open on phone" button from the car screen
- The phone screen now scrolls, so the update controls are reachable in landscape

## 1.9
- Added tap control: touching the mirror on the car screen taps the phone (needs the MyCar accessibility service)

## 1.8
- Added a "Lock the screen to landscape while mirroring" option

## 1.7
- The mirror now shrinks to the visible map area when a side panel (media controls, assistant) is open

## 1.6
- Fixed a crash when turning the phone while mirroring

## 1.5
- Added a "Keep the screen on while mirroring" option

## 1.4
- The mirror now follows the phone's orientation, so turning the phone to landscape fills the car screen instead of staying portrait-shaped

## 1.3
- Fixed Android Auto dropping back to the app list when MyCar is opened

## 1.2
- Fixed a crash when opening MyCar in Android Auto caused by the Car App API level meta-data being declared in the wrong place

## 1.1
- Fixed a crash on launch caused by the app theme not being an AppCompat theme

## 1.0
- Mirrors the phone screen into Android Auto as a navigation app
- Start and stop mirroring from the phone
- In-app updates from mycar.sting.hr
