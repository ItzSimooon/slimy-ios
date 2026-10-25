#!/usr/bin/env python3
"""Checks that every Garten-Rätsel level can be solved.

Reads the levels from App/Arcade/Engines/PuzzleEngines.swift and solves each
with a breadth-first search over (slime, stones, seeds left), using the same
rules as GardenPuzzleEngine.move: stones can only be pushed onto grass.
"""
import re
import sys
from collections import deque
from pathlib import Path

SOURCE = Path(__file__).resolve().parent.parent / "App/Arcade/Engines/PuzzleEngines.swift"


def levels():
    text = SOURCE.read_text()
    block = text[text.index("static let levels"):]
    block = block[:block.index("    ]\n")]
    for group in re.findall(r"\[((?:\s*\"[^\"]*\",?)+)\]", block):
        yield re.findall(r"\"([^\"]*)\"", group)


def solve(rows):
    walls, stones, seeds = set(), set(), set()
    start = bed = None
    for y, row in enumerate(rows):
        for x, c in enumerate(row):
            if c == "#": walls.add((x, y))
            elif c == "o": stones.add((x, y))
            elif c == "*": seeds.add((x, y))
            elif c == "S": start = (x, y)
            elif c == "B": bed = (x, y)
    first = (start, frozenset(stones), frozenset(seeds))
    queue, seen = deque([(first, 0)]), {first}
    while queue:
        (pos, st, sd), steps = queue.popleft()
        if pos == bed and not sd:
            return steps
        for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
            nxt = (pos[0] + dx, pos[1] + dy)
            if nxt in walls:
                continue
            new_stones = st
            if nxt in st:
                beyond = (nxt[0] + dx, nxt[1] + dy)
                if beyond in walls or beyond in st or beyond in sd or beyond == bed:
                    continue
                new_stones = (st - {nxt}) | {beyond}
            state = (nxt, frozenset(new_stones), frozenset(sd - {nxt}))
            if state not in seen:
                seen.add(state)
                queue.append((state, steps + 1))
    return None


def main():
    ok = True
    found = list(levels())
    for i, rows in enumerate(found, 1):
        steps = solve(rows)
        print(f"level {i}: " + (f"solvable in {steps} moves" if steps else "NOT SOLVABLE"))
        ok &= steps is not None
    if not found:
        print("no levels found"); ok = False
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main()
