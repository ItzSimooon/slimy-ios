#!/usr/bin/env bash
# Force-pushes the screenshots to the orphan branch "ci-screenshots", with a
# README that shows them side by side, so the newest set is one click away on
# GitHub. Runs in GitHub Actions with GITHUB_TOKEN (contents: write).
# Usage: tools/ci_publish_screenshots.sh <dir with PNGs>
set -euo pipefail

src="$1"
branch="ci-screenshots"
order=(home gift kitchen shop journal friends settings chat arcade bath)

shopt -s nullglob
pngs=("$src"/*.png)
if [ ${#pngs[@]} -eq 0 ]; then
  echo "No screenshots to publish."
  exit 0
fi
: "${GITHUB_TOKEN:?GITHUB_TOKEN is needed to push}"

work="$(mktemp -d)"
cp "${pngs[@]}" "$work/"
cd "$work"

short="${GITHUB_SHA:0:7}"
run_url="$GITHUB_SERVER_URL/$GITHUB_REPOSITORY/actions/runs/$GITHUB_RUN_ID"
{
  echo "# Slimy screenshots"
  echo
  echo "Commit \`$short\` on \`$GITHUB_REF_NAME\`, taken $(date -u '+%Y-%m-%d %H:%M UTC') in [run $GITHUB_RUN_NUMBER]($run_url)."
  echo "This branch is replaced on every push; download older sets from the run's artifacts."
  echo
  echo "| Screen | Light | Dark |"
  echo "|---|---|---|"
  for screen in "${order[@]}"; do
    light="light-$screen.png"
    dark="dark-$screen.png"
    [ -f "$light" ] || [ -f "$dark" ] || continue
    cell_light=""
    cell_dark=""
    [ -f "$light" ] && cell_light="<img src=\"$light\" width=\"260\" alt=\"$screen, light\">"
    [ -f "$dark" ] && cell_dark="<img src=\"$dark\" width=\"260\" alt=\"$screen, dark\">"
    echo "| $screen | $cell_light | $cell_dark |"
  done
} > README.md

git init -q -b "$branch"
git config user.name "github-actions[bot]"
git config user.email "41898282+github-actions[bot]@users.noreply.github.com"
git add -A
git commit -q -m "Screenshots of $short ($GITHUB_REF_NAME)"
git push -q -f "https://x-access-token:${GITHUB_TOKEN}@${GITHUB_SERVER_URL#https://}/${GITHUB_REPOSITORY}.git" "HEAD:refs/heads/$branch"
echo "Published $(ls -1 *.png | wc -l | tr -d ' ') screenshots to $branch."
