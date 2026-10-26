#!/usr/bin/env bash
# Turns compiler and test errors from an xcodebuild log into GitHub
# annotations, because the raw log is hard to reach from outside. When the
# log has no "error:" lines (a crash, a signing or simulator problem), its
# last lines become the annotation instead.
# Usage: tools/ci_report.sh xcodebuild.log
log="$1"
if [ ! -s "$log" ]; then
  echo "::error title=No build log::$log is missing or empty; the step failed before xcodebuild wrote anything."
  exit 0
fi

grep -E "error:" "$log" | sed "s#$GITHUB_WORKSPACE/##g" | sort -u | head -150 > errors.txt || true
cat errors.txt
head -10 errors.txt | while IFS= read -r line; do
  if [[ "$line" =~ ^([^:]+):([0-9]+):([0-9]+):\ error:\ (.*)$ ]]; then
    echo "::error file=${BASH_REMATCH[1]},line=${BASH_REMATCH[2]},col=${BASH_REMATCH[3]}::${BASH_REMATCH[4]}"
  elif [[ "$line" =~ ^([^:]+):([0-9]+):\ error:\ (.*)$ ]]; then
    echo "::error file=${BASH_REMATCH[1]},line=${BASH_REMATCH[2]}::${BASH_REMATCH[3]}"
  fi
done

if [ -s errors.txt ]; then
  title="All errors"
  source_file=errors.txt
else
  title="Last lines of the log"
  tail -40 "$log" | sed "s#$GITHUB_WORKSPACE/##g" > log-tail.txt
  source_file=log-tail.txt
fi
all=$(python3 -c "import sys;print(open(sys.argv[1]).read()[:60000].replace('%','%25').replace('\r','%0D').replace('\n','%0A'))" "$source_file")
echo "::error title=$title::$all"
