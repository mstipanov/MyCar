# Community announcement posts

Purpose: the one public post per community. Do **not** cross-post the same text everywhere on the
same day — space them out, and read each sub's rules first.

Rules of engagement (all subs):

- Always lead with the disclosure line. Undisclosed promotion is what gets accounts banned.
- Answer every comment for the first few hours. Engagement is what keeps the post alive.
- One post per sub, never the same link dropped in someone else's thread.
- Never post the same comment text twice; the monitor workstream (A) is for finding threads to
  answer *individually*.

---

## r/AndroidAuto — the main post

**Before posting:** send modmail (see `modmail.md`) — this sub requires user flair and has strict
self-promotion rules.

**Flair:** `App` (or the closest equivalent the sub offers).

**Title options (pick one):**
- `[App] MyCar — mirror your phone (YouTube, etc.) to Android Auto without losing the navigation slot`
- `[App] I built a screen mirror for Android Auto that doesn't steal the nav slot from Maps`

**Body:**

> **Disclosure: I'm the developer of MyCar.**
>
> I wanted YouTube (and Netflix, and a browser) on my car screen, but every tool I tried —
> CarStream, Fermata, Screen2Auto — took over the navigation slot, so I lost Google Maps while
> something was playing. So I built a screen mirror that doesn't have to.
>
> **What it is:** an Android Auto app that mirrors your phone's actual screen onto the car display
> and forwards taps, scrolls and pinches back to the phone. Because it mirrors the whole screen,
> it works with *any* app — no per-app support list.
>
> **What makes it different:**
> - It can register as a **weather** app, so Google Maps/Waze keep navigating while the mirror is on
>   screen. (It can also take the navigation slot if you prefer.)
> - Open source, MIT, no ads, no account.
> - Built-in quick-launch strip (Google Maps / YouTube + a rotate button by default).
> - Self-updating.
>
> **The catch, honestly:** it's sideload-only. Android Auto won't list an app unless it was
> installed by something that looks like a store, so you install it with KingInstaller and enable
> *Unknown sources* in Android Auto's developer settings. Full steps here:
> https://mstipanov.github.io/MyCar/
>
> It also can't show `FLAG_SECURE` content (banking apps, some DRM video go black) — that's Android,
> not the app.
>
> **Please use it as a passenger or while parked** — watching video while driving is illegal
> in most places.
>
> Source + APK: https://github.com/mstipanov/MyCar
>
> Happy to answer anything about how the mirroring or the Android Auto surface works.

---

## r/androidapps — variant

**Title:** `[App] MyCar — an open-source Android Auto screen mirror that keeps your nav slot free`

**Body:** same as above, but trim the Android Auto jargon and lead with the "personal project,
free, MIT" angle. r/androidapps runs a self-promotion megathread — check whether a standalone post
is allowed before posting; if not, use the megathread with the same text.

---

## r/AndroidQuestions — variant

Only post here if someone asks a matching question; do **not** start a new thread. Answer the
question, then mention MyCar as one option with the disclosure line and the link. This is the
"reply to a real question" pattern — keep it short and specific.

---

## r/CarPlay (crossover, only if relevant)

MyCar is Android-only, so only mention it if the thread is about Android Auto replacements or
phone-screen-on-car-display generally. Usually skip.
