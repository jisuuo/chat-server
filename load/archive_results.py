#!/usr/bin/env python3
"""Copy compact official raw evidence into the tracked report directory."""

import hashlib
import json
import shutil
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SOURCE = ROOT / "load" / "results"
TARGET = ROOT / "docs" / "reports" / "2026-10-07-plan5b-results"


def main():
    TARGET.mkdir(parents=True, exist_ok=True)
    manifest = {}
    for path in sorted(SOURCE.glob("official-*")):
        if path.is_dir():
            for item in path.iterdir():
                if item.suffix not in (".json", ".csv"):
                    continue
                relative = Path(path.name) / item.name
                destination = TARGET / relative
                destination.parent.mkdir(parents=True, exist_ok=True)
                shutil.copy2(item, destination)
                manifest[str(relative)] = hashlib.sha256(destination.read_bytes()).hexdigest()
        elif path.suffix == ".json":
            destination = TARGET / path.name
            shutil.copy2(path, destination)
            manifest[path.name] = hashlib.sha256(destination.read_bytes()).hexdigest()
    for path in sorted(SOURCE.glob("sql-official-*.json")):
        destination = TARGET / path.name
        shutil.copy2(path, destination)
        manifest[path.name] = hashlib.sha256(destination.read_bytes()).hexdigest()
    (TARGET / "sha256.json").write_text(json.dumps(manifest, indent=2) + "\n")
    print(f"archived {len(manifest)} files to {TARGET}")


if __name__ == "__main__":
    main()
