@echo off
REM ---------------------------------------------------------------
REM  Build the release APK, then serve it over your LAN so a phone
REM  on the same WiFi can download and install it.
REM
REM  Usage:
REM    build-and-serve.cmd            build + serve on port 8000
REM    build-and-serve.cmd 8080       build + serve on a custom port
REM    build-and-serve.cmd --serve    skip build, serve only
REM ---------------------------------------------------------------
setlocal

cd /d "%~dp0"

set "SERVE_ONLY="
set "PORT=8000"
for %%a in (%*) do (
  if /I "%%a"=="--serve" (set "SERVE_ONLY=1") else (set "PORT=%%a")
)

REM ---- locate node (needed only for the local HTTP server) ----
if not defined NODE (
  for %%p in (node.exe) do if not "%%~$PATH:p"=="" set "NODE=%%~$PATH:p"
)
if not defined NODE if exist "%ProgramFiles%\nodejs\node.exe" set "NODE=%ProgramFiles%\nodejs\node.exe"

if not defined SERVE_ONLY (
  echo.
  echo === Building release APK ===
  if exist "%~dp0gradlew.bat" (
    call "%~dp0gradlew.bat" assembleRelease
  ) else (
    where gradle >nul 2>nul
    if errorlevel 1 (
      echo.
      echo No gradlew.bat and no gradle on PATH.
      echo Install Gradle 8.7, or generate a wrapper:  gradle wrapper --gradle-version 8.7
      pause
      exit /b 1
    )
    call gradle assembleRelease
  )
  if errorlevel 1 (
    echo.
    echo BUILD FAILED - not serving.
    pause
    exit /b 1
  )
)

if not defined NODE (
  echo.
  echo Node.js not found - cannot start the LAN server.
  echo Install Node.js, or just copy the APK out of:
  echo   build\outputs\apk\release\
  pause
  exit /b 1
)

echo.
echo === Starting LAN APK server on port %PORT% ===
"%NODE%" tools\serve-apk.mjs --port %PORT%

pause
