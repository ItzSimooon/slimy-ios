#!/usr/bin/env python3
"""Synthesises Slimy's sound effects and music loop as small WAV files.

Everything is generated from square, triangle and noise waves here, so the
sounds are original and need no licence. Output: Resources/Sounds/*.wav
"""
import math
import os
import random
import struct
import wave

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RATE = 22050


def square(phase, duty=0.5):
    return 1.0 if (phase % 1.0) < duty else -1.0


def triangle(phase):
    p = phase % 1.0
    return 4 * p - 1 if p < 0.5 else 3 - 4 * p


def tone(freq, dur, wave_fn=square, vol=0.4, slide=0.0, attack=0.005, release=0.06, duty=0.5, vibrato=0.0):
    n = int(RATE * dur)
    out = []
    phase = 0.0
    for i in range(n):
        t = i / RATE
        f = freq + slide * (t / dur)
        if vibrato:
            f *= 1 + 0.01 * math.sin(2 * math.pi * vibrato * t)
        phase += f / RATE
        env = min(1.0, t / attack) if attack else 1.0
        env *= min(1.0, (dur - t) / release) if release else 1.0
        sample = wave_fn(phase, duty) if wave_fn is square else wave_fn(phase)
        out.append(sample * vol * max(0.0, env))
    return out


def noise(dur, vol=0.3, release=0.05, seed=1):
    rng = random.Random(seed)
    n = int(RATE * dur)
    return [rng.uniform(-1, 1) * vol * min(1.0, (dur - i / RATE) / release) for i in range(n)]


def silence(dur):
    return [0.0] * int(RATE * dur)


def mix(*tracks):
    n = max(len(t) for t in tracks)
    return [sum(t[i] for t in tracks if i < len(t)) for i in range(n)]


def seq(*parts):
    out = []
    for p in parts:
        out.extend(p)
    return out


def note(name):
    names = {"C": -9, "C#": -8, "D": -7, "D#": -6, "E": -5, "F": -4, "F#": -3, "G": -2, "G#": -1, "A": 0, "A#": 1, "B": 2}
    pitch, octave = name[:-1], int(name[-1])
    return 440.0 * 2 ** ((names[pitch] + (octave - 4) * 12) / 12)


def write(name, samples):
    path = os.path.join(ROOT, "Resources", "Sounds", name + ".wav")
    os.makedirs(os.path.dirname(path), exist_ok=True)
    peak = max(1e-6, max(abs(s) for s in samples))
    scale = min(1.0, 0.9 / peak)
    with wave.open(path, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(RATE)
        w.writeframes(b"".join(struct.pack("<h", int(max(-1, min(1, s * scale)) * 32000)) for s in samples))


SOUNDS = {
    "tap": tone(880, 0.04, vol=0.25, release=0.03),
    "hop": tone(300, 0.12, triangle, vol=0.5, slide=500),
    "land": mix(tone(160, 0.08, triangle, vol=0.5, slide=-80), noise(0.05, 0.12)),
    "eat": seq(tone(420, 0.05, vol=0.3), silence(0.03), tone(360, 0.05, vol=0.3), silence(0.03), tone(440, 0.06, vol=0.3)),
    "pet": seq(tone(note("E5"), 0.07, triangle, vol=0.4), tone(note("G5"), 0.1, triangle, vol=0.4)),
    "splash": mix(noise(0.25, 0.25, release=0.2, seed=3), tone(600, 0.2, triangle, vol=0.2, slide=-300)),
    "sleep": seq(tone(note("E5"), 0.12, triangle, vol=0.35), tone(note("C5"), 0.12, triangle, vol=0.35),
                 tone(note("A4"), 0.25, triangle, vol=0.35)),
    "wake": seq(tone(note("C5"), 0.08, triangle, vol=0.35), tone(note("E5"), 0.08, triangle, vol=0.35),
                tone(note("G5"), 0.16, triangle, vol=0.35)),
    "coin": seq(tone(note("B5"), 0.06, vol=0.25), tone(note("E6"), 0.18, vol=0.25)),
    "levelUp": seq(*[tone(note(n), 0.08, vol=0.28) for n in ("C5", "E5", "G5", "C6")], tone(note("E6"), 0.25, vol=0.28)),
    "success": seq(tone(note("G5"), 0.07, vol=0.25), tone(note("C6"), 0.16, vol=0.25)),
    "nope": seq(tone(220, 0.08, vol=0.25), tone(180, 0.12, vol=0.25)),
    "pop": tone(700, 0.07, triangle, vol=0.45, slide=600, release=0.04),
    "friend": seq(tone(note("A5"), 0.08, triangle, vol=0.35), tone(note("C#6"), 0.08, triangle, vol=0.35),
                  tone(note("E6"), 0.2, triangle, vol=0.35)),
    "perfect": tone(note("A6"), 0.08, vol=0.2),
    "miss": tone(140, 0.12, vol=0.25, slide=-40),
    "whoosh": noise(0.18, 0.18, release=0.15, seed=7),
    "dig": mix(noise(0.1, 0.25, seed=5), tone(120, 0.1, triangle, vol=0.3)),
    "treasure": seq(*[tone(note(n), 0.07, vol=0.25) for n in ("E5", "G#5", "B5", "E6")]),
    "tone1": tone(note("C5"), 0.3, triangle, vol=0.45, release=0.15),
    "tone2": tone(note("D5"), 0.3, triangle, vol=0.45, release=0.15),
    "tone3": tone(note("E5"), 0.3, triangle, vol=0.45, release=0.15),
    "tone4": tone(note("G5"), 0.3, triangle, vol=0.45, release=0.15),
    "tone5": tone(note("A5"), 0.3, triangle, vol=0.45, release=0.15),
    "tone6": tone(note("C6"), 0.3, triangle, vol=0.45, release=0.15),
    "tone7": tone(note("D6"), 0.3, triangle, vol=0.45, release=0.15),
    "tone8": tone(note("E6"), 0.3, triangle, vol=0.45, release=0.15),
    "tone9": tone(note("G6"), 0.3, triangle, vol=0.45, release=0.15),
}


def music():
    """A calm 8-bar loop: square-wave melody over a triangle bass."""
    bpm = 96
    beat = 60 / bpm
    melody = ["E5", "G5", "A5", "G5", "E5", "D5", "C5", "D5",
              "E5", "G5", "C6", "B5", "A5", "G5", "E5", "-",
              "F5", "A5", "C6", "A5", "G5", "E5", "D5", "E5",
              "C5", "D5", "E5", "G5", "E5", "D5", "C5", "-"]
    bass = ["C3", "C3", "A2", "A2", "F2", "F2", "G2", "G2"]
    lead = []
    for n in melody:
        lead += silence(beat / 2) if n == "-" else tone(note(n), beat / 2, vol=0.12, duty=0.25, release=0.08)
    low = []
    for n in bass:
        low += tone(note(n), beat * 2, triangle, vol=0.22, release=0.2)
    return mix(lead, low)


def main():
    for name, samples in SOUNDS.items():
        write(name, samples)
    write("music", music())
    print(f"{len(SOUNDS) + 1} sounds written")


if __name__ == "__main__":
    main()
