#!/usr/bin/env python3
"""Packt Kursradar als kursradar_v<VERSION>.zip (Version aus der Datei VERSION)."""
import os, zipfile
here = os.path.dirname(os.path.abspath(__file__))
v = open(os.path.join(here, "VERSION")).read().strip()
out = os.path.join(os.path.dirname(here), f"kursradar_v{v}.zip")
FILES = ["depot-import.json", "Kursradar starten (Windows).bat", "Kursradar starten (Mac).command", "kursradar-start.sh",
         "LIESMICH.txt", "index.html", "server.py", "CHANGELOG.txt", "VERSION"]
with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as z:
    for f in FILES:
        info = zipfile.ZipInfo.from_file(os.path.join(here, f), f"kursradar_v{v}/{f}")
        info.compress_type = zipfile.ZIP_DEFLATED
        info.create_system = 3  # Unix: Ausführungsrechte bleiben beim Entpacken auf Mac/Linux erhalten
        mode = 0o755 if f.endswith((".command", ".sh")) else 0o644
        info.external_attr = (0o100000 | mode) << 16
        with open(os.path.join(here, f), "rb") as fh:
            z.writestr(info, fh.read())
print(out)
