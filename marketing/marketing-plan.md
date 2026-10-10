# MyCar — marketing plan & work streams

_Saved 2026-10-10. Continue from here later._

Free-only marketing for MyCar (an Android Auto screen mirror that can keep the
navigation slot free). No budget. Goal is adoption, not revenue.

---

## Decisions locked

| Question | Decision |
|---|---|
| Where does the guide live? | **GitHub Pages** (`docs/`), later optionally repoint `mycar.sting.hr` |
| Framing | **Passenger / parked use, global English** |
| How is MyCar presented? | Inside an honest **comparison** (recommended where it genuinely differs: the nav slot) — not a banner |
| Posting | **Manual**, disclosed ("I'm the dev"). No unattended reply bot |
| Success metric | Downloads (distributor `downloads.jsonl` / `/stats`) — not donations |

---

## Workstream C — Landing page + SEO ✅ DONE & LIVE

**Live:** https://mstipanov.github.io/MyCar/ (GitHub Pages, HTTP 200)

Deliverables:
- `docs/index.html` — "How to Play YouTube on Android Auto (2026)": short answer,
  honest method comparison table, MyCar section, 7-step install walkthrough,
  FAQ/troubleshooting, passenger/parked safety note, "not affiliated with Google"
  disclaimer, schema.org `SoftwareApplication`.
- `docs/styles.css`, `docs/sitemap.xml`, `docs/robots.txt`, `docs/icon.png`,
  `docs/.nojekyll`.

Repo hygiene done:
- GitHub description retargeted to "play YouTube … screen mirror".
- Homepage set to the guide.
- Topics added: `youtube`, `youtube-android-auto`, `android-auto-screen-mirror`.

Outstanding (manual, free):
- [ ] Add the site to **Google Search Console** and submit `sitemap.xml`.
- [ ] Optionally repoint `mycar.sting.hr` from the Releases redirect to the guide.
- [ ] Optionally add a "Read the guide" link to the release-notes footer.

---

## Workstream B — Community post + outreach ✅ DRAFTS DONE

Files in `marketing/`:
- `reddit-announcement.md` — r/AndroidAuto `[App]` post + variants
  (r/androidapps, r/AndroidQuestions, r/CarPlay), rules of engagement.
- `modmail.md` — ask-the-mods-first template.
- `media-pitch.md` — XDA / Android Authority / Android Police / Engadget pitch;
  hook = "keeps the nav slot". Table of who to pitch.
- `demo-script.md` — 20-second Shorts/TikTok/Reels shot list + caption.
- `objection-replies.md` — bank of replies (competitors, safety, black screen,
  root, battery, install, compatibility).

Outstanding (manual):
- [ ] Send the modmail; wait for the OK.
- [ ] Post the `[App]` thread once approved; answer every comment.
- [ ] Send media pitches individually (one follow-up max, then stop).
- [ ] Film and post the 20s demo; pin the guide link.

---

## Workstream A — Read-only opportunity monitor ✅ DONE

Files in `tools/opportunities/`:
- `opportunities.py` — searches recent Reddit threads for the keywords, scores
  them, writes `opportunities.md`, dedupes via `seen.txt`. **Read-only; never
  posts.**
- `queries.txt` — subs + search terms.
- `README.md` — setup + fallbacks.
- `.env.example` — credential template.

**Key finding:** Reddit returns **403/429** to unauthenticated scripted requests
(tested). The script therefore uses the official **OAuth API** when credentials
are set, and falls back to the public endpoints otherwise (usually fails).

Outstanding:
- [ ] If automation is wanted: create a Reddit "script" app, export creds per
      `.env.example`, run `python3 opportunities.py`.
- [ ] Otherwise use the free fallbacks: **F5Bot**, **Feedly** (Reddit search RSS),
      **Google Alerts**, and agent `websearch` (which does surface the threads).
- [ ] Answer 2–3 threads a month by hand, with the disclosure line.

---

## Findings that shape the strategy

- **Niche is saturated and lower-friction rivals win the default path:** AAAD /
  AAStore hand out Fermata Auto, CarStream, Screen2Auto as one-tap installs.
  `ScreenOnAuto` is a near-identical mirror and already did the r/AndroidAuto
  `[App]` post. MyCar's differentiator is the **nav slot**, so lead with that.
- **Biggest marketing problem is install friction:** KingInstaller + Unknown
  sources + accessibility (+ optional `adb appop`). Every removed step multiplies
  conversions from every channel. This is the single highest-ROI "content" work.
- **Reddit doesn't allow automated posting;** bans are real. Manual, disclosed,
  helpful-first is the only sustainable approach.
- **Can't ship on Play** (does things Play forbids) → no app-store funnel;
  sideload + GitHub + guide only.
- **0 GitHub stars.** Set expectations: aim for downloads, not donations.
- **Safety/legality:** always frame as passenger/parked; never "watch while driving".

## Rough priority (highest ROI first)

1. Google Search Console + the one Reddit post (with mod OK).
2. One media pitch (XDA / Android Police) — the nav-slot angle is genuinely new.
3. Short-form demo video.
4. Monitor + a few hand-written replies per month.
5. Friction-reduction work on the install flow.

---

## Definitions of done for this plan

- Guide is indexed by Google for "how to play youtube on android auto".
- One r/AndroidAuto `[App]` thread posted with mod approval and answered.
- At least one media/blog mention, or an existing roundup updated to include MyCar.
- The monitor surfaces threads and a handful get genuine replies.
