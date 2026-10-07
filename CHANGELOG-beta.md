# MyCar (beta)

Beta builds ship features before they reach the stable channel. The app is the same package, so
installing a beta replaces the stable build on your phone.

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
