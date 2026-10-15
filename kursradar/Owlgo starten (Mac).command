#!/bin/bash
# Owlgo – Startdatei für macOS (Doppelklick genügt)
cd "$(dirname "$0")" || exit 1
clear
if command -v python3 >/dev/null 2>&1 && python3 --version >/dev/null 2>&1; then
  echo "Owlgo wird gestartet – der Browser öffnet sich gleich."
  echo
  python3 server.py || { echo; echo "Owlgo wurde mit einem Fehler beendet. Bitte die Meldung oben an Claude schicken."; read -n1 -p "Taste drücken zum Schließen …"; }
else
  echo "Python 3 wurde nicht gefunden."
  echo "Die Download-Seite von python.org wird geöffnet. Nach der Installation diese Datei erneut doppelklicken."
  open "https://www.python.org/downloads/macos/"
  read -n1 -p "Taste drücken zum Schließen …"
fi
