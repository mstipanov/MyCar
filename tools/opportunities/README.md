# Opportunity monitor (read-only)

Finds recent public threads where people ask how to play YouTube (or other video)
on Android Auto, so a human can answer a few by hand.

**It never posts and never writes to Reddit.** It only reads search results.
Posting stays manual on purpose: automated promotional posting violates Reddit's
rules and gets accounts banned — and a dev who shows up and answers follow-ups is
what actually converts.

## Reality check: Reddit needs OAuth now

Reddit returns **403/429** to unauthenticated scripted requests. The reliable
path is the official API with a token. The script uses OAuth when these are set,
and otherwise falls back to the public endpoints (which usually fail).

### Setup (5 minutes, free)

1. Open <https://www.reddit.com/prefs/apps> → **create another app…** → type
   **script**. Set the redirect URI to `http://localhost:8080` (unused here).
2. Note the **client id** (under the app name) and the **secret**.
3. Export credentials. Either:

   **Simplest — password grant (runs on your machine only):**
   ```sh
   export REDDIT_CLIENT_ID=xxxxxxxx
   export REDDIT_CLIENT_SECRET=yyyyyyyy
   export REDDIT_USERNAME=your_reddit_username
   export REDDIT_PASSWORD=your_reddit_password
   ```

   **Safer — refresh token (no password stored; scope `read`):** do the
   authorization-code flow once, then:
   ```sh
   export REDDIT_CLIENT_ID=xxxxxxxx
   export REDDIT_CLIENT_SECRET=yyyyyyyy
   export REDDIT_REFRESH_TOKEN=zzzzzzzz
   ```

   Never commit these. Keep them in your shell profile or a local `.env` you
   source yourself; the repo ignores `.env` via `.gitignore`.

## Run it

```sh
cd tools/opportunities
python3 opportunities.py                # last 45 days, only threads you haven't seen
python3 opportunities.py --days 14
python3 opportunities.py --all          # ignore seen.txt
```

Output lands in `opportunities.md`; `seen.txt` remembers what was already shown.
Both are gitignored.

## If you skip OAuth

Do one of these instead — all free, all more reliable than scraping:

- **F5Bot** (<https://f5bot.com>) — email alerts for keywords on Reddit/HN. Set:
  `android auto youtube`, `screen mirror android auto`, `carstream`, `fermata`.
- **Feedly / any RSS reader** — subscribe to the Reddit search feed
  `https://www.reddit.com/r/AndroidAuto/search.rss?q=youtube&restrict_sr=1&sort=new`.
  The reader fetches from its servers, so your IP isn't blocked.
- **Google Alerts** — query `"youtube" "android auto"`.
- **Ask the agent** — `websearch` reliably surfaced r/AndroidAuto threads on this
  topic even when `curl` was blocked.

## Manual passes (no usable API)

- **XDA forums** — `site:xdaforums.com youtube android auto`
- **Quora / Stack Exchange** — search, answer in your own words
- **YouTube comments** — on "YouTube on Android Auto" videos

## Rules of engagement

See `../marketing/reddit-announcement.md`. Short version: disclose you're the
dev, answer the actual question first, never post the same text twice, and never
drop the link in a thread you didn't contribute to.
