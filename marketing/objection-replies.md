# Objection-reply bank

Copy-paste starting points for comments. **Always personalize** — never post the same text twice.
Keep the disclosure ("I'm the dev") in any reply where you link MyCar.

---

**"Why not just use CarStream / Fermata Auto / Screen2Auto?"**

> Those are good, and they install one-tap via AAAD. The difference is they take the navigation
> slot, so you lose Google Maps while something plays. MyCar is a mirror that can sit in the
> *weather* slot, so Maps keeps navigating. If you don't care about keeping Maps up, they're
> simpler — honestly. (I'm the dev of MyCar.)

**"Isn't this dangerous / illegal?"**

> Agreed on the risk — I say up front it's for a passenger or while parked. Watching video while
> driving is illegal in most places and I don't want anyone using it that way.

**"The screen is black."**

> If the phone screen is fine but the car is black, either capture stopped (check the MyCar
> notification and re-approve) or the app on screen is `FLAG_SECURE` — banking apps, some DRM video
> and password fields are forced black by Android. YouTube mirrors fine; some paid streamers don't.

**"Do I need root?"**

> No. It uses the normal screen-capture consent plus the Android Auto map surface. No root at all.

**"Battery drain?"**

> Capturing at ~30 fps costs more than not capturing, yes. It survives the phone screen turning off
> (it keeps the display awake), and locks to landscape if you want. Realistically a long session
> will use noticeable battery — plug in if the car has a port.

**"It's not in the Play Store."**

> Correct — it can't be. It mirrors the whole screen and never actually navigates, which Play
> doesn't allow. It's sideload-only: install the APK with KingInstaller and turn on *Unknown
> sources* in Android Auto's developer settings. Steps are in the guide.

**"How do I install it?"**

> 1) Install KingInstaller. 2) Install MyCar's APK through it. 3) Android Auto settings → tap
> Version ~10× → Developer settings → enable *Unknown sources*. 4) Open MyCar, grant *Display over
> other apps* + the accessibility service. 5) Connect and open MyCar in the car. Full walkthrough:
> https://mstipanov.github.io/MyCar/

**"Will it work on my car / Android version?"**

> It needs Android 9+ on the phone and Android Auto (wired or wireless). Any car with Android Auto
> should work. If it doesn't show up, reconnect the head unit after installing.
