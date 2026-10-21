"""Expands `#include "file"` lines of a shader in app/src/main/assets (the app does the same in EarthRenderer.readShader)."""
import re
from pathlib import Path

ASSETS = Path(__file__).resolve().parent.parent / "app" / "src" / "main" / "assets"


def expand(text: str) -> str:
    return re.sub(r'^\s*#include "([^"]+)"\s*$', lambda m: expand((ASSETS / m.group(1)).read_text(encoding="utf-8")), text, flags=re.M)


if __name__ == "__main__":
    import sys
    print(expand(Path(sys.argv[1]).read_text(encoding="utf-8")))
