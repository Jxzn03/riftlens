@echo off
rem Startet die App in einem eigenen Prozess.
rem "start-modern.cmd --rebuild" baut dist\league-tool-modern.jar neu.
setlocal
cd /d "%~dp0"

set "JAR=dist\league-tool-modern.jar"
if "%~1"=="--rebuild" goto build
if exist "%JAR%" goto run

:build
echo Baue die moderne Oberflaeche ...
call mvnw.cmd -q -Pmodern -DskipTests package
if errorlevel 1 (
    echo Build fehlgeschlagen.
    exit /b 1
)

:run
rem test-build wont launch program all the time
if /I "%~2"=="--no-launch" goto done

rem Startzeit: gemeinsames Klassen-Archiv wird beim ersten Lauf automatisch angelegt.
start "" javaw -XX:+UseSerialGC -XX:TieredStopAtLevel=1 -XX:+AutoCreateSharedArchive "-XX:SharedArchiveFile=%USERPROFILE%\.leaguetool\modern-ui\app.jsa" -jar "%JAR%"

:done
endlocal
