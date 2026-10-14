# Kursradar

Aktien-Cockpit mit Top-10-Listen, Charts, Trendanalyse, Filtern und eigenem Depot –
als Desktop-App (`kursradar/`) und als Android-App (`android/`) mit automatischen Updates.

## Android-App einrichten (einmalig)

1. **Dieses Repository muss öffentlich sein.** Die App lädt Updates ohne Anmeldung von hier.
2. Tab **Actions** öffnen. Falls GitHub fragt, Workflows aktivieren. Der Build „Android-App bauen“
   startet bei jeder Änderung automatisch (oder über „Run workflow“) und dauert ca. 5 Minuten.
3. Danach unter **Releases** die Datei `kursradar.apk` **auf dem Handy** öffnen und installieren.
   Android fragt einmalig, ob der Browser Apps installieren darf → erlauben.
4. In der App unten **Depot** → **Importieren** → `depot-import.json` wählen
   (die Datei liegt nur bei dir, nicht in diesem Repository).

## Automatische Updates

| Was ändert sich | Was du tun musst | Was die App macht |
|---|---|---|
| Oberfläche (`kursradar/index.html`, Version in `kursradar/VERSION`) | neue Dateien hochladen | lädt das Update beim nächsten Start still nach |
| App selbst (Version in `android/APP_VERSION`) | neue Dateien hochladen | zeigt „App-Update … Installieren“ |

In der App die Versionsnummer oben links antippen = sofort nach Updates suchen.
Versionsschema: kleine Änderung `3.1 → 3.2`, große Änderung `3.x → 4.1`.

## Desktop-Version

Ordner `kursradar/`: Doppelklick auf `Kursradar starten (Windows).bat` bzw. `(Mac).command`.

## Hinweise

- Persönliche Depot-Daten (`depot-import.json`, `portfolio.json`) sind per `.gitignore` ausgeschlossen – nicht hochladen.
- Der Signierschlüssel `android/keystore/kursradar.jks` liegt im Repository, damit GitHub
  Updates mit demselben Schlüssel bauen kann. Installiere die App deshalb nur aus den Releases
  dieses Repositorys.
- Kurse: Yahoo Finance, je nach Börse bis zu 15 Minuten verzögert. Keine Anlageberatung.
