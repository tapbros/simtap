#!/usr/bin/env python3
"""Compile production decisions and service against small host-side Android fakes."""
import pathlib
import subprocess
import tempfile


def main():
    root = pathlib.Path(__file__).resolve().parents[1]
    sources = sorted((root / "tests/java").rglob("*.java"))
    sources += [
        root / "src/com/tapbros/simtap" / name
        for name in ("NetworkJob.java", "MobileDataDialog.java", "SimCache.java", "SimTapService.java")
    ]
    with tempfile.TemporaryDirectory(prefix="simtap-tests-") as classes:
        subprocess.run(
            ["javac", "-encoding", "UTF-8", "--release", "8", "-d", classes]
            + [str(source) for source in sources],
            check=True,
        )
        subprocess.run(
            ["java", "-cp", classes, "com.tapbros.simtap.ServiceRegression"],
            check=True,
        )


if __name__ == "__main__":
    main()
