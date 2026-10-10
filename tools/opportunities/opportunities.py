#!/usr/bin/env python3
"""Read-only opportunity monitor for MyCar.

Finds recent public Reddit threads where people are asking how to play YouTube
(or other video) on Android Auto, so a human can answer a few of them by hand.

It never posts and never writes anything to Reddit. It only reads search
results. Posting stays manual on purpose: automated promotional posting violates
Reddit's rules and gets accounts banned.

Reddit blocks unauthenticated scripted access (HTTP 403/429), so the reliable
path is the official API with an OAuth token. Set these environment variables:

    REDDIT_CLIENT_ID       from https://www.reddit.com/prefs/apps  (script app)
    REDDIT_CLIENT_SECRET   same app
    REDDIT_REFRESH_TOKEN   preferred: a token scoped to 'read' (see README)

  …or, less safely (never share a password with anyone):

    REDDIT_USERNAME / REDDIT_PASSWORD   with the same client id/secret

Without credentials it falls back to the public .json endpoints, which Reddit
usually rejects from scripts. In that case use F5Bot / Feedly / a web search.

Usage:
    python3 opportunities.py                 # last 45 days, only new threads
    python3 opportunities.py --days 14
    python3 opportunities.py --all           # ignore seen.txt
    python3 opportunities.py --limit 15

Outputs:
    opportunities.md   the report
    seen.txt           ids already reported, so each run shows only new ones
"""

from __future__ import annotations

import argparse
import base64
import json
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime, timezone

HERE = os.path.dirname(os.path.abspath(__file__))
QUERIES_FILE = os.path.join(HERE, "queries.txt")
SEEN_FILE = os.path.join(HERE, "seen.txt")
OUT_FILE = os.path.join(HERE, "opportunities.md")

USER_AGENT = "MyCar-opportunity-monitor/1.0 (read-only; by mstipanov)"
DELAY_SECONDS = 2.0
TOKEN_URL = "https://www.reddit.com/api/v1/access_token"
OAUTH_BASE = "https://oauth.reddit.com"
PUBLIC_BASE = "https://www.reddit.com"

POSITIVE = [
    "youtube", "netflix", "video", "watch", "mirror", "mirroring",
    "screen", "carstream", "fermata", "screen2auto", "aaad", "stream",
]
NEGATIVE = ["sold", "buying", "for sale", "issue with my car"]


def log(msg: str) -> None:
    print(msg, file=sys.stderr)


def load_queries(path: str) -> tuple[list[str], list[str]]:
    subs, terms = [], []
    with open(path, encoding="utf-8") as fh:
        for raw in fh:
            line = raw.strip()
            if not line or line.startswith("#"):
                continue
            if line.startswith("r/"):
                subs.append(line[2:].strip())
            elif line.startswith("q/"):
                terms.append(line[2:].strip())
    return subs, terms


# --- auth ------------------------------------------------------------------

def get_token() -> str | None:
    """Return an OAuth bearer token, or None if no credentials are configured."""
    client_id = os.environ.get("REDDIT_CLIENT_ID")
    client_secret = os.environ.get("REDDIT_CLIENT_SECRET")
    refresh = os.environ.get("REDDIT_REFRESH_TOKEN")
    username = os.environ.get("REDDIT_USERNAME")
    password = os.environ.get("REDDIT_PASSWORD")

    if not (client_id and client_secret):
        return None
    if refresh:
        data = {"grant_type": "refresh_token", "refresh_token": refresh}
    elif username and password:
        data = {"grant_type": "password", "username": username, "password": password}
    else:
        return None

    basic = base64.b64encode(f"{client_id}:{client_secret}".encode()).decode()
    body = urllib.parse.urlencode(data).encode()
    req = urllib.request.Request(
        TOKEN_URL,
        data=body,
        headers={"Authorization": f"Basic {basic}", "User-Agent": USER_AGENT},
    )
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            return json.loads(resp.read().decode())["access_token"]
    except Exception as exc:  # noqa: BLE001
        log(f"  ! token request failed: {type(exc).__name__}: {exc}")
        return None


# --- fetching --------------------------------------------------------------

def get_json(url: str, token: str | None) -> dict | None:
    headers = {"User-Agent": USER_AGENT}
    if token:
        headers["Authorization"] = f"bearer {token}"
    req = urllib.request.Request(url, headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            return json.loads(resp.read().decode("utf-8", "replace"))
    except urllib.error.HTTPError as exc:
        if exc.code in (403, 429) and not token:
            log("  ! Reddit refused the request (no OAuth token). See the README.")
        else:
            log(f"  ! HTTP {exc.code} for {url}")
    except Exception as exc:  # noqa: BLE001
        log(f"  ! {type(exc).__name__}: {exc}")
    return None


def search(sub: str | None, term: str, limit: int, token: str | None) -> list[dict]:
    q = urllib.parse.quote(term)
    base = OAUTH_BASE if token else PUBLIC_BASE
    if sub:
        url = (f"{base}/r/{sub}/search?q={q}&restrict_sr=1&sort=new&t=month"
               f"&limit={limit}&raw_json=1")
    else:
        url = f"{base}/search?q={q}&sort=new&t=month&limit={limit}&raw_json=1"
    data = get_json(url, token)
    if not data or "data" not in data:
        return []
    return [c.get("data", {}) for c in data.get("data", {}).get("children", [])]


# --- scoring ---------------------------------------------------------------

def score(post: dict, terms: list[str]) -> int:
    text = f"{post.get('title', '')} {post.get('selftext', '')}".lower()
    s = sum(2 for w in POSITIVE if w in text)
    s += sum(1 for t in terms if t in text)
    if post.get("over_18"):
        s -= 5
    if post.get("stickied"):
        s -= 10
    if sum(1 for w in NEGATIVE if w in text) >= 2:
        s -= 5
    return s


def suggested_angle(post: dict) -> str:
    text = f"{post.get('title', '')} {post.get('selftext', '')}".lower()
    if "netflix" in text:
        return "Mention the mirror handles any app, not just YouTube; note DRM/FLAG_SECURE limits."
    if any(w in text for w in ("map", "waze", "navigation", "nav")):
        return "Lead with the nav-slot point: MyCar can sit in the weather slot so Maps keeps routing."
    if any(w in text for w in ("carstream", "fermata", "screen2auto", "aaad")):
        return "Compare honestly; highlight that MyCar keeps the nav slot, then link the guide."
    if "root" in text:
        return "Confirm no root needed; explain the MediaProjection consent."
    return "Answer the question first, then offer MyCar as one option with the disclosure line."


def main() -> int:
    ap = argparse.ArgumentParser(description="Read-only Android Auto opportunity monitor")
    ap.add_argument("--days", type=int, default=45)
    ap.add_argument("--limit", type=int, default=15)
    ap.add_argument("--all", action="store_true", help="ignore seen.txt")
    args = ap.parse_args()

    subs, terms = load_queries(QUERIES_FILE)
    if not terms:
        log("No search terms in queries.txt")
        return 1

    token = get_token()
    if token:
        log("Authenticated with Reddit OAuth (read-only).")
    else:
        log("No Reddit credentials — falling back to public endpoints (usually blocked).")

    seen: set[str] = set()
    if os.path.exists(SEEN_FILE) and not args.all:
        with open(SEEN_FILE, encoding="utf-8") as fh:
            seen = {line.strip() for line in fh if line.strip()}

    cutoff = time.time() - args.days * 86400
    found: dict[str, dict] = {}

    jobs: list[tuple[str | None, str]] = [(None, t) for t in terms]
    for sub in subs:
        jobs += [(sub, t) for t in terms]

    log(f"Running {len(jobs)} read-only queries…")
    for sub, term in jobs:
        log(f"  - {'r/' + sub if sub else 'all reddit'}: {term}")
        for post in search(sub, term, args.limit, token):
            pid = post.get("id") or post.get("permalink")
            if not pid or pid in found:
                continue
            if post.get("created_utc", 0) < cutoff or post.get("over_18"):
                continue
            found[pid] = post
        time.sleep(DELAY_SECONDS)

    rows = [p for pid, p in found.items() if pid not in seen]
    for p in rows:
        p["_score"] = score(p, terms)
    rows = [p for p in rows if p["_score"] > 0]
    rows.sort(key=lambda p: p.get("created_utc", 0), reverse=True)

    now = datetime.now(timezone.utc).strftime("%Y-%m-%d %H:%M UTC")
    lines = [
        "# Android Auto opportunities",
        "",
        f"_Generated {now} · last {args.days} days · {len(rows)} new thread(s)_",
        "",
        "Read-only. Answer by hand — never mass-post. Always disclose you're the dev.",
        "",
    ]
    if not rows:
        lines.append("Nothing new. Try a smaller `--days`, or add terms to `queries.txt`.")
    for p in rows:
        created = datetime.fromtimestamp(p.get("created_utc", 0), timezone.utc).strftime("%Y-%m-%d")
        url = "https://www.reddit.com" + p.get("permalink", "")
        lines += [
            f"## [{p.get('title', '(no title)')}]({url})",
            "",
            f"- **r/{p.get('subreddit', '?')}** · {created} · score {p.get('score', 0)} "
            f"· {p.get('num_comments', 0)} comments · relevance {p['_score']}",
            f"- Flair: {p.get('link_flair_text') or '—'}",
            f"- Suggested angle: {suggested_angle(p)}",
            "",
        ]
        body = (p.get("selftext") or "").strip().replace("\n", " ")
        if body:
            lines += [f"> {body[:320]}{'…' if len(body) > 320 else ''}", ""]

    with open(OUT_FILE, "w", encoding="utf-8") as fh:
        fh.write("\n".join(lines) + "\n")
    with open(SEEN_FILE, "a", encoding="utf-8") as fh:
        for p in rows:
            fh.write(f"{p.get('id') or p.get('permalink')}\n")

    log(f"\nWrote {len(rows)} thread(s) to {OUT_FILE}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
