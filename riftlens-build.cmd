@echo off
rem Startet die App in einem eigenen Prozess.
rem "riftlens-build.cmd --rebuild" baut dist\league-tool-modern.jar neu.
rem "--no-launch" (an beliebiger Stelle) baut nur, ohne die App zu starten.
setlocal
cd /d "%~dp0"

set "JAR=dist\league-tool-modern.jar"
set "REBUILD="
set "NOLAUNCH="
:args
if "%~1"=="" goto argsdone
if /I "%~1"=="--rebuild" set "REBUILD=1"
if /I "%~1"=="--no-launch" set "NOLAUNCH=1"
shift
goto args
:argsdone

if defined REBUILD goto build
if exist "%JAR%" goto run

:build
echo Baue die moderne Oberflaeche ...
call "%~dp0mvnw.cmd" -q -Pmodern -DskipTests package
if errorlevel 1 (
    echo Build fehlgeschlagen.
    exit /b 1
)

:run
if defined NOLAUNCH goto done

rem Startzeit: gemeinsames Klassen-Archiv wird beim ersten Lauf automatisch angelegt.
start "" javaw -XX:+UseSerialGC -XX:TieredStopAtLevel=1 -XX:+AutoCreateSharedArchive "-XX:SharedArchiveFile=%USERPROFILE%\.leaguetool\modern-ui\app.jsa" -jar "%JAR%"

:done
endlocal
