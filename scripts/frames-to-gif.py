#!/usr/bin/env python
"""The frames of a recording, as the animated GIF at the top of the README.

Separate from the recorder that produced them, because the two jobs need two different things to
exist: frames come out of a browser, and a GIF is a palette and an LZW stream that Pillow already
knows how to write. The recorder therefore needs no image library and this needs no browser.

    python scripts/frames-to-gif.py target/demo/frames docs/demo.gif
    python scripts/frames-to-gif.py target/demo/frames docs/demo.gif --width 960 --hold 2000

Pillow is the one dependency in this repository that is not declared anywhere, and deliberately:
nothing in the build or in CI makes a GIF, and a recording is something somebody does by hand once
a release. If it is missing, this says so and says how.
"""

from __future__ import annotations

import argparse
import pathlib
import sys

try:
    from PIL import Image
except ImportError:  # pragma: no cover - the message is the whole behaviour
    print(
        "this needs Pillow, which nothing else here does: python -m pip install Pillow",
        file=sys.stderr,
    )
    raise SystemExit(2) from None

# Enough for a dark interface with four seat colours in it, and small enough that the palette is
# not most of the file. A GIF cannot hold more than 256 anyway.
COLOURS = 128


def collapse(frames: list, frame_ms: int, hold_ms: int) -> tuple[list, list[int]]:
    """Runs of identical frames become one frame held for as long as the run lasted.

    A recording of a sale is mostly a picture that is not changing: the lobby fills for twenty
    seconds before the doors open, and the last seats sit inside holds that will not expire until
    long after the camera stops. Those are forty seconds of the same bytes, and a viewer plays every
    one of them. Collapsing keeps the timing honest rather than cutting it: a run of seven identical
    frames is one frame shown for as long as seven were, up to the hold, so what is lost is the
    repetition and not the pause.
    """
    kept = [frames[0]]
    runs = [1]
    for frame in frames[1:]:
        if frame.tobytes() == kept[-1].tobytes():
            runs[-1] += 1
        else:
            kept.append(frame)
            runs.append(1)
    durations = [min(run * frame_ms, hold_ms) for run in runs]
    # The last frame is the one somebody reads the final numbers off, so it gets at least the hold.
    durations[-1] = max(durations[-1], hold_ms)
    return kept, durations


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description="frames of a recording, as one animated GIF")
    parser.add_argument("frames", type=pathlib.Path, help="the directory the recorder wrote")
    parser.add_argument("gif", type=pathlib.Path, help="where to write it")
    parser.add_argument("--width", type=int, default=960, help="scaled to this, default 960")
    parser.add_argument("--frame", type=int, default=300, help="ms per frame, default 300")
    parser.add_argument(
        "--hold",
        type=int,
        default=2000,
        help="ms on the last frame, default 2000, so the end state can be read",
    )
    arguments = parser.parse_args(argv)

    paths = sorted(arguments.frames.glob("*.png"))
    if not paths:
        print(f"no frames in {arguments.frames}", file=sys.stderr)
        return 2

    frames = []
    for path in paths:
        with Image.open(path) as image:
            frame = image.convert("RGB")
            if arguments.width and frame.width != arguments.width:
                height = round(frame.height * arguments.width / frame.width)
                frame = frame.resize((arguments.width, height), Image.LANCZOS)
            frames.append(frame)

    frames, durations = collapse(frames, arguments.frame, arguments.hold)

    # One palette for the whole animation, taken from the first frame and imposed on the rest.
    # A palette per frame is both bigger and worse to watch: the dark background is quantised
    # slightly differently each time, so a still picture of a seat map shimmers.
    palette = frames[0].quantize(colors=COLOURS)
    quantized = [palette] + [
        frame.quantize(palette=palette, dither=Image.Dither.NONE) for frame in frames[1:]
    ]

    arguments.gif.parent.mkdir(parents=True, exist_ok=True)
    quantized[0].save(
        arguments.gif,
        save_all=True,
        append_images=quantized[1:],
        duration=durations,
        loop=0,
        optimize=True,
    )

    size = arguments.gif.stat().st_size
    seconds = sum(durations) / 1000
    print(
        f"{arguments.gif}: {len(quantized)} frames, {seconds:.1f}s, "
        f"{quantized[0].width}x{quantized[0].height}, {size / 1_000_000:.1f} MB"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
