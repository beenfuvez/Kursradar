@echo off
rem Kursradar - Startdatei fuer Windows (Doppelklick genuegt)
chcp 65001 >nul
title Kursradar
cd /d "%~dp0"

set "PY="
where py >nul 2>nul && py -3 --version >nul 2>nul && set "PY=py -3"
if not defined PY python --version >nul 2>nul && set "PY=python"
if not defined PY if exist "%LOCALAPPDATA%\Programs\Python\Python312\python.exe" set "PY="%LOCALAPPDATA%\Programs\Python\Python312\python.exe""
if not defined PY if exist "%ProgramFiles%\Python312\python.exe" set "PY="%ProgramFiles%\Python312\python.exe""
if defined PY goto start

echo.
echo  Python wurde auf diesem PC nicht gefunden.
where winget >nul 2>nul
if errorlevel 1 goto manual
echo  Python wird jetzt automatisch installiert (einmalig, ca. 1-2 Minuten) ...
echo.
winget install -e --id Python.Python.3.12 --scope user --accept-source-agreements --accept-package-agreements
if exist "%LOCALAPPDATA%\Programs\Python\Python312\python.exe" (
  set "PY="%LOCALAPPDATA%\Programs\Python\Python312\python.exe""
  goto start
)
echo.
echo  Installation abgeschlossen. Bitte diese Datei noch einmal doppelklicken.
pause
exit /b

:manual
echo  Bitte Python von python.org installieren und dabei "Add Python to PATH" ankreuzen.
echo  Die Download-Seite wird jetzt geoeffnet. Danach diese Datei erneut starten.
start "" https://www.python.org/downloads/
pause
exit /b

:start
echo.
echo  Kursradar wird gestartet - der Browser oeffnet sich gleich.
echo.
%PY% server.py
if errorlevel 1 (
  echo.
  echo  Kursradar wurde mit einem Fehler beendet. Bitte die Meldung oben an Claude schicken.
  pause
)
