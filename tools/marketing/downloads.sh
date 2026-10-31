#!/usr/bin/env bash
# Real download numbers for MyCar.
#
# Why this exists: the app page redirects to GitHub Releases (distributor's
# `app.mycar.redirect`), so the distributor's downloads.jsonl and /stats never
# see a MyCar install. The only honest counter is the GitHub release assets.
#
# Usage:  tools/marketing/downloads.sh            # total + last 10 releases
#         tools/marketing/downloads.sh 1.40 1.43  # a specific range of tags
set -euo pipefail

if ! command -v gh >/dev/null 2>&1; then
  echo "gh is required (brew install gh)" >&2
  exit 1
fi

repo="mstipanov/MyCar"

total=$(gh api "repos/$repo/releases" --paginate -q '.[].assets[].download_count' 2>/dev/null \
  | awk '{s+=$1} END {print s+0}')
count=$(gh api "repos/$repo/releases" --paginate -q '.[] | .tag_name' 2>/dev/null | wc -l | tr -d ' ')

echo "MyCar — GitHub release downloads"
echo "  releases:  $count"
echo "  downloads: $total"
echo

echo "  per release (newest first):"
gh api "repos/$repo/releases" --paginate \
  -q '.[] | "\(.tag_name)\t\((.assets | map(.download_count) | add) // 0)"' 2>/dev/null \
  | head -10 | awk -F'\t' '{printf "    %-16s %s\n", $1, $2}'

if [ "$#" -ge 2 ]; then
  echo
  echo "  range $1 .. $2:"
  gh api "repos/$repo/releases" --paginate \
    -q '.[] | "\(.tag_name)\t\((.assets | map(.download_count) | add) // 0)"' 2>/dev/null \
    | awk -F'\t' -v a="$1" -v b="$2" '$1>=a && $1<=b {printf "    %-16s %s\n", $1, $2}'
fi

echo
echo "Note: your own installs count too. Treat single digits per release as noise,"
echo "and compare releases only after a channel actually produced traffic."
