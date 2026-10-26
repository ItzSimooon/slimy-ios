#!/usr/bin/env bash
# Prints the UDID of the simulator for screenshots: the newest available
# "iPhone NN Pro" (falls back to a Pro Max, then to any iPhone). Its name and
# runtime go to stderr for the log.
# Usage: tools/ci_simulator.sh
set -euo pipefail
xcrun simctl list devices available --json | python3 -c '
import json, re, sys

best = None
for runtime, devices in json.load(sys.stdin)["devices"].items():
    version = re.search(r"iOS-(\d+)-(\d+)", runtime)
    if not version:
        continue
    os_version = (int(version.group(1)), int(version.group(2)))
    for device in devices:
        name = device.get("name", "")
        if not device.get("isAvailable", True) or not name.startswith("iPhone"):
            continue
        if re.fullmatch(r"iPhone \d+ Pro", name):
            rank = 3
        elif re.fullmatch(r"iPhone \d+ Pro Max", name):
            rank = 2
        else:
            rank = 1
        number = re.search(r"\d+", name)
        key = (rank, os_version, int(number.group()) if number else 0)
        if best is None or key > best[0]:
            best = (key, device["udid"], name, runtime.rsplit(".", 1)[-1])

if best is None:
    sys.exit("No iPhone simulator is available.")
sys.stderr.write("Simulator: %s (%s)\n" % (best[2], best[3]))
print(best[1])
'
