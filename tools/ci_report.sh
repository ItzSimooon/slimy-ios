#!/usr/bin/env bash
# Turns compiler and test errors from an xcodebuild log into GitHub
# annotations, because the raw log is hard to reach from outside.
# Usage: tools/ci_report.sh xcodebuild.log
log="$1"
grep -E "error:" "$log" | sed "s#$GITHUB_WORKSPACE/##g" | sort -u | head -150 > errors.txt || true
cat errors.txt
head -10 errors.txt | while IFS= read -r line; do
  if [[ "$line" =~ ^([^:]+):([0-9]+):([0-9]+):\ error:\ (.*)$ ]]; then
    echo "::error file=${BASH_REMATCH[1]},line=${BASH_REMATCH[2]},col=${BASH_REMATCH[3]}::${BASH_REMATCH[4]}"
  elif [[ "$line" =~ ^([^:]+):([0-9]+):\ error:\ (.*)$ ]]; then
    echo "::error file=${BASH_REMATCH[1]},line=${BASH_REMATCH[2]}::${BASH_REMATCH[3]}"
  fi
done
all=$(python3 -c "import sys;print(open('errors.txt').read()[:60000].replace('%','%25').replace('\r','%0D').replace('\n','%0A'))")
echo "::error title=All errors::$all"
