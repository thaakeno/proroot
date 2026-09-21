#!/usr/bin/python3
import json
import subprocess
import sys


def main():
    if len(sys.argv) != 2:
        raise SystemExit("scale argument required")

    scale = float(sys.argv[1])
    if not 0.75 <= scale <= 2.0:
        raise SystemExit("scale outside supported range")

    raw = subprocess.check_output(
        ["kscreen-doctor", "-j"],
        text=True,
        stderr=subprocess.STDOUT,
    )
    config = json.loads(raw)
    outputs = [
        output
        for output in config.get("outputs", [])
        if output.get("connected") and output.get("enabled")
    ]
    if not outputs:
        raise SystemExit("no connected KScreen output")

    output = next(
        (candidate for candidate in outputs if candidate.get("priority") == 1),
        outputs[0],
    )
    output_id = output.get("id")
    if output_id is None:
        raise SystemExit("KScreen output has no id")

    value = f"{scale:.2f}".rstrip("0").rstrip(".")
    subprocess.run(
        ["kscreen-doctor", f"output.{output_id}.scale.{value}"],
        check=True,
    )


if __name__ == "__main__":
    main()
