#!/usr/bin/env bash
# Publishes screenshots to the orphan branch "ci-screenshots", with a README
# that shows them side by side, so the newest set is one click away on
# GitHub. The iPhone set lives at the top of the branch (as it always has),
# the Android set in android/. Each publish replaces only its own set and
# keeps the other one, and the branch stays a single commit: it is rebuilt
# from what is there, force-pushed with a lease, and rebuilt again if the
# other job pushed in between. Runs in GitHub Actions with GITHUB_TOKEN
# (contents: write).
# Usage: tools/ci_publish_screenshots.sh <dir with PNGs> [ios|android]
set -euo pipefail

src="$1"
part="${2:-ios}"
case "$part" in
  ios | android) ;;
  *)
    echo "Unknown set '$part': use ios or android."
    exit 1
    ;;
esac
branch="ci-screenshots"
ios_order=(home gift crates kitchen shop journal friends care settings chat arcade bath)
android_order=(home onboarding gift crates crate-reveal kitchen shop journal photo-booth friends friends-offline
  care chat chat-empty settings arcade game-snack-catcher bath whats-new)

shopt -s nullglob
pngs=("$src"/*.png)
if [ ${#pngs[@]} -eq 0 ]; then
  echo "No screenshots to publish."
  exit 0
fi
: "${GITHUB_TOKEN:?GITHUB_TOKEN is needed to push}"

src_abs="$(cd "$src" && pwd)"
# CI_SCREENSHOTS_REMOTE points a dry run at another repository (a local bare one).
remote="${CI_SCREENSHOTS_REMOTE:-https://x-access-token:${GITHUB_TOKEN}@${GITHUB_SERVER_URL#https://}/${GITHUB_REPOSITORY}.git}"
short="${GITHUB_SHA:0:7}"
run_url="$GITHUB_SERVER_URL/$GITHUB_REPOSITORY/actions/runs/$GITHUB_RUN_ID"
stamp="Commit \`$short\` on \`$GITHUB_REF_NAME\`, taken $(date -u '+%Y-%m-%d %H:%M UTC') in [run $GITHUB_RUN_NUMBER]($run_url)."

# table <folder prefix, "" or "android/"> <screens in order...>
table() {
  local prefix="$1"
  shift
  echo "| Screen | Light | Dark |"
  echo "|---|---|---|"
  for screen in "$@"; do
    local light="${prefix}light-$screen.png" dark="${prefix}dark-$screen.png" cell_light="" cell_dark=""
    [ -f "$light" ] || [ -f "$dark" ] || continue
    [ -f "$light" ] && cell_light="<img src=\"$light\" width=\"260\" alt=\"$screen, light\">"
    [ -f "$dark" ] && cell_dark="<img src=\"$dark\" width=\"260\" alt=\"$screen, dark\">"
    echo "| $screen | $cell_light | $cell_dark |"
  done
}

readme() {
  local ios=(./*.png) android=(android/*.png)
  echo "# Slimy screenshots"
  echo
  echo "The newest screenshots of the iPhone and the Android app. Each set is replaced when its CI job finishes;"
  echo "download older sets from the run's artifacts."
  if [ ${#ios[@]} -gt 0 ]; then
    echo
    echo "## iPhone"
    echo
    [ -f ios-run.txt ] && { cat ios-run.txt; echo; }
    table "" "${ios_order[@]}"
  fi
  if [ ${#android[@]} -gt 0 ]; then
    echo
    echo "## Android"
    echo
    echo "Rendered on the JVM by Robolectric and Roborazzi (412 x 915 dp phone)."
    echo
    [ -f android/run.txt ] && { cat android/run.txt; echo; }
    table "android/" "${android_order[@]}"
  fi
}

# One attempt: the branch as it is now, with this set replaced. Runs in a
# subshell under `if`, where `set -e` is off, so every step checks itself.
publish() {
  local work old=""
  work="$(mktemp -d)"
  cd "$work" || return 1
  git init -q -b "$branch" || return 1
  git config user.name "github-actions[bot]"
  git config user.email "41898282+github-actions[bot]@users.noreply.github.com"
  if git fetch -q --depth 1 "$remote" "refs/heads/$branch" 2>/dev/null; then
    old="$(git rev-parse FETCH_HEAD)" || return 1
    git checkout -q FETCH_HEAD -- . || return 1
  fi
  if [ "$part" = ios ]; then
    rm -f ./*.png
    cp "$src_abs"/*.png . || return 1
    echo "$stamp" > ios-run.txt
  else
    rm -rf android
    mkdir android
    cp "$src_abs"/*.png android/ || return 1
    echo "$stamp" > android/run.txt
  fi
  readme > README.md || return 1
  git add -A || return 1
  # Unborn HEAD: a fresh root commit, so the branch never grows a history.
  git commit -q -m "Screenshots ($part) of $short ($GITHUB_REF_NAME)" || return 1
  if [ -n "$old" ]; then
    git push -q --force-with-lease="refs/heads/$branch:$old" "$remote" "HEAD:refs/heads/$branch" || return 1
  else
    git push -q "$remote" "HEAD:refs/heads/$branch" || return 1
  fi
  echo "Published ${#pngs[@]} $part screenshots to $branch."
}

for attempt in 1 2 3 4 5; do
  if (publish); then
    exit 0
  fi
  echo "The $branch branch changed while publishing; trying again ($attempt of 5)."
  sleep $((attempt * 5))
done
echo "::error::Couldn't publish the $part screenshots to $branch."
exit 1
