"""Import a checked-out NekokoLPA/operator-icons snapshot (Python 3.11+).

Usage: python3 tools/import_operator_catalog.py SOURCE_DIRECTORY COMMIT_SHA
The app uses the resulting catalog offline; it never sends SIM identifiers upstream.
"""
import json
from pathlib import Path
import re
import shutil
import sys
import tomllib

source = Path(sys.argv[1])
revision = sys.argv[2]
assert re.fullmatch(r"[0-9a-f]{40}", revision)
target = Path(__file__).resolve().parents[1] / "app-common/src/main/assets/operator_catalog"
target.mkdir(parents=True, exist_ok=True)
catalogs = []
icons = set()
for path in sorted((source / "catalog").glob("*.toml")):
    data = tomllib.loads(path.read_text())
    assert re.fullmatch(r"[0-9]{3}", data["mcc"])
    assert re.fullmatch(r"[A-Z]{2}(?:/[A-Z]{2})*", data["iso"])
    operators = []
    for row in data.get("operators", []):
        assert re.fullmatch(r"[0-9]{2,3}", row["mnc"])
        icon = None
        if row.get("icon") and row.get("icon_scope"):
            assert re.fullmatch(r"[a-zA-Z0-9_-]+", row["icon"])
            assert re.fullmatch(r"[a-zA-Z0-9_-]+", row["icon_scope"])
            icon = f'{row["icon_scope"]}/{row["icon"]}.png'
            assert (source / "icons" / icon).is_file(), icon
            icons.add(icon)
        # Preserve GID/name rules, but intentionally use the parent network icon.
        operators.append({
            "mnc": row["mnc"], "operator": row.get("operator", ""),
            "brand": row.get("brand", ""), "icon": icon,
            "gids": [{k: g[k] for k in ("gid1", "gid2", "profile_names", "profile_provider_names")
                      if k in g} for g in row.get("gids", [])],
        })
    catalogs.append({"mcc": data["mcc"], "iso": data["iso"], "operators": operators})

for icon in sorted(icons):
    dest = target / "icons" / icon
    dest.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(source / "icons" / icon, dest)
(target / "catalog.json").write_text(json.dumps(catalogs, ensure_ascii=False, separators=(",", ":")))
(target / "SOURCE.txt").write_text(
    "Source: https://github.com/NekokoLPA/operator-icons\n"
    f"Revision: {revision}\n"
    "Generated with tools/import_operator_catalog.py.\n"
    "Rules and PNGs are from the upstream snapshot. Network-level icons are used;\n"
    "nested brand icon overrides are not used. No runtime network requests.\n"
    "No LICENSE file was present in this data repository at this revision.\n"
    "The application's MIT license does not establish the license of these assets.\n"
    "Upstream terms and trademark permissions need checking before public distribution.\n"
)
print(f"Imported {len(catalogs)} MCC catalogs, {sum(len(c['operators']) for c in catalogs)} networks, {len(icons)} icons")
