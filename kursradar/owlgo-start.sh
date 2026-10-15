#!/bin/sh
# Owlgo – Startdatei für Linux
cd "$(dirname "$0")" || exit 1
if ! command -v python3 >/dev/null 2>&1; then
  echo "Python 3 fehlt. Installieren z. B. mit: sudo apt install python3"; exit 1
fi
exec python3 server.py
