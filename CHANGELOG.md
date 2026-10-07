# MyCar

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
