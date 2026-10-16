#!/usr/bin/env python3
"""Generate the placeholder movement sounds shipped with the mod.

Every file this writes is synthesised from noise and sine sweeps in this script - no Respawn or EA
asset is involved, and nothing here is sampled from the game. They exist so the movement sounds are
audible out of the box instead of resolving to silence, and so the sound system itself (loops,
volume, triggering) can be tested.

Real Titanfall 2 audio is copyrighted and stays out of this repository. To use it, replace these
files locally, or (better, since it keeps the repository clean) put them in a resource pack at
assets/titanfall_movement/sounds/movement/<name>.ogg, which overrides the mod's files.

Usage:
    python3 -m venv .venv && .venv/bin/pip install soundfile numpy
    .venv/bin/python tools/generate-placeholder-audio.py

Output: src/main/resources/assets/titanfall_movement/sounds/movement/*.ogg
"""

from __future__ import annotations

import pathlib
import sys

import numpy as np
import soundfile as sf

RATE = 44100
OUT = pathlib.Path(__file__).resolve().parent.parent / "src/main/resources/assets/titanfall_movement/sounds/movement"

rng = np.random.default_rng(20261016)


def time(seconds: float) -> np.ndarray:
    return np.arange(int(RATE * seconds)) / RATE


def sweep(frequencies: np.ndarray, duration: float) -> np.ndarray:
    """Sine whose frequency follows the given [start, end] sweep over the duration."""
    t = time(duration)
    freq = np.interp(t / duration, [0.0, 1.0], frequencies)
    phase = 2.0 * np.pi * np.cumsum(freq) / RATE
    return np.sin(phase)


def noise(duration: float, smoothing: int = 8) -> np.ndarray:
    """White noise softened by a moving average, which stands in for a low-pass filter."""
    raw = rng.normal(0.0, 1.0, int(RATE * duration))
    if smoothing > 1:
        kernel = np.ones(smoothing) / smoothing
        raw = np.convolve(raw, kernel, mode="same")
    return raw


def envelope(duration: float, attack: float, decay: float) -> np.ndarray:
    t = time(duration)
    rise = np.clip(t / max(attack, 1e-4), 0.0, 1.0)
    fall = np.exp(-t / max(decay, 1e-4))
    return rise * fall


def seamless(data: np.ndarray, fade: float = 0.05) -> np.ndarray:
    """Cross-fade the tail into the head so the file loops without a click."""
    samples = int(RATE * fade)
    if samples * 2 >= len(data):
        return data
    head, tail = data[:samples].copy(), data[-samples:].copy()
    ramp = np.linspace(0.0, 1.0, samples)
    return np.concatenate([head * ramp + tail * (1.0 - ramp), data[samples:-samples]])


def normalise(data: np.ndarray, peak: float = 0.55) -> np.ndarray:
    highest = float(np.max(np.abs(data)))
    return data * (peak / highest) if highest > 0 else data


def write(name: str, data: np.ndarray) -> None:
    data = normalise(np.asarray(data, dtype=np.float32))
    path = OUT / f"{name}.ogg"
    sf.write(path, data, RATE, format="OGG", subtype="VORBIS")
    print(f"{path.relative_to(OUT.parent.parent.parent.parent.parent)}: {len(data) / RATE:.2f}s, peak {np.max(np.abs(data)):.2f}")


def main() -> int:
    OUT.mkdir(parents=True, exist_ok=True)

    # Ground jump: a short upward blip.
    jump = sweep(np.array([420.0, 560.0]), 0.18) * envelope(0.18, 0.005, 0.045)
    # Double jump: same idea a fifth higher, so it reads as a second boost.
    double = sweep(np.array([540.0, 780.0]), 0.22) * envelope(0.22, 0.005, 0.05)
    # Wall jump: a kick with a noise transient on the front.
    walljump = sweep(np.array([300.0, 720.0]), 0.30) * envelope(0.30, 0.004, 0.07)
    walljump += noise(0.30, 4) * envelope(0.30, 0.002, 0.02) * 0.4
    # Landing: a low thud plus a scrape.
    land = np.sin(2 * np.pi * 92.0 * time(0.35)) * envelope(0.35, 0.004, 0.06)
    land += noise(0.35, 24) * envelope(0.35, 0.002, 0.035) * 0.5
    # Wall run: a whoosh that starts, and a loop that sustains it.
    wallrun_start = noise(0.35, 10) * envelope(0.35, 0.03, 0.10)
    wallrun_loop = seamless(noise(2.0, 12) * 0.85)
    # Slide: a scraper start and a lower sustained loop.
    slide_start = noise(0.35, 16) * envelope(0.35, 0.02, 0.09)
    slide_start += sweep(np.array([260.0, 150.0]), 0.35) * envelope(0.35, 0.01, 0.08) * 0.35
    slide_loop = seamless(noise(1.5, 28) * 0.9 + np.sin(2 * np.pi * 70.0 * time(1.5)) * 0.15)

    for name, data in [
        ("jump", jump),
        ("double_jump", double),
        ("walljump", walljump),
        ("land", land),
        ("wallrun_start", wallrun_start),
        ("wallrun_loop", wallrun_loop),
        ("slide_start", slide_start),
        ("slide_loop", slide_loop),
    ]:
        write(name, data)

    return 0


if __name__ == "__main__":
    sys.exit(main())
