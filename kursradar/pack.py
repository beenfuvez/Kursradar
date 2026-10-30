#!/usr/bin/env python3
"""Packt Owlgo als owlgo_v<VERSION>.zip und die Ausgabe ohne Depot als owlgo-ohne-depot_v<VERSION>.zip."""
import os, zipfile
here = os.path.dirname(os.path.abspath(__file__))
v = open(os.path.join(here, "VERSION")).read().strip()
dest = os.path.dirname(os.path.dirname(here))  # neben dem Repository, nie darin
FILES = ["depot-import.json", "Owlgo starten (Windows).bat", "Owlgo starten (Mac).command", "owlgo-start.sh",
         "LIESMICH.txt", "index.html", "server.py", "CHANGELOG.txt", "VERSION"]
FLAG = b"const NO_DEPOT=false;"


def ohne_depot(data):
    assert data.count(FLAG) == 1, "Schalter NO_DEPOT nicht gefunden"
    return data.replace(FLAG, b"const NO_DEPOT=true;")


def pack(name, files, patch=None):
    out = os.path.join(dest, f"{name}_v{v}.zip")
    with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as z:
        for f in files:
            info = zipfile.ZipInfo.from_file(os.path.join(here, f), f"{name}_v{v}/{f}")
            info.compress_type = zipfile.ZIP_DEFLATED
            info.create_system = 3  # Unix: Ausführungsrechte bleiben beim Entpacken auf Mac/Linux erhalten
            mode = 0o755 if f.endswith((".command", ".sh")) else 0o644
            info.external_attr = (0o100000 | mode) << 16
            with open(os.path.join(here, f), "rb") as fh:
                data = fh.read()
            z.writestr(info, patch(data) if patch and f == "index.html" else data)
    print(out)


pack("owlgo", FILES)
pack("owlgo-ohne-depot", [f for f in FILES if f != "depot-import.json"], ohne_depot)
with open(os.path.join(here, "index.html"), "rb") as fh, open(os.path.join(dest, "owlgo-ohne-depot.html"), "wb") as o:
    o.write(ohne_depot(fh.read()))  # Einzeldatei für die Online-Ansicht
print(os.path.join(dest, "owlgo-ohne-depot.html"))
