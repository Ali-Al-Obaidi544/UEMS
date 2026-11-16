@echo off
REM UEMS launcher for Windows.
REM   run.bat                       starts the application
REM   run.bat setup --reset --demo  creates the database schema (+ demo data); see README.md
setlocal
cd /d "%~dp0"

where java >nul 2>&1
if errorlevel 1 (
  echo ERROR: java was not found. Install JDK 17 or newer and make sure it is on the PATH.
  pause
  exit /b 1
)
if not exist uems.properties (
  echo ERROR: uems.properties was not found in %cd%
  echo Copy uems.properties.example to uems.properties and edit the database settings.
  pause
  exit /b 1
)

set CP=
if exist out\classes (
  set CP=out\classes
) else if exist target\classes (
  set CP=target\classes;target\lib\*
) else (
  echo ERROR: the application is not built yet. Run build.bat first.
  pause
  exit /b 1
)
set JDBC=
for %%j in (lib\postgresql*.jar) do set JDBC=%%j
if defined JDBC set CP=%CP%;%JDBC%

if /i "%1"=="setup" (
  java -Dfile.encoding=UTF-8 -cp "%CP%" uems.DbSetup --config uems.properties %2 %3 %4
) else (
  java -Dfile.encoding=UTF-8 -cp "%CP%" uems.UemsApp uems.properties
)
if errorlevel 1 (
  echo.
  echo UEMS stopped with an error - see the messages above.
  pause
)
