# MyCar (beta)

Beta builds ship features before they reach the stable channel. The app is the same package, so
installing a beta replaces the stable build on your phone.

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
