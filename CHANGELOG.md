# MyCar

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
