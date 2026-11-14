@echo off
REM Builds UEMS on Windows with only a JDK (17 or newer). Maven is NOT needed.
REM Also downloads the PostgreSQL JDBC driver into lib\ the first time.
setlocal
cd /d "%~dp0"

where javac >nul 2>&1
if errorlevel 1 (
  echo ERROR: javac was not found. Install a JDK 17 or newer ^(not only a JRE^) and make sure it is on the PATH.
  pause
  exit /b 1
)

if not exist lib mkdir lib
if not exist lib\postgresql-42.7.3.jar (
  echo Downloading the PostgreSQL JDBC driver...
  powershell -NoProfile -Command "[Net.ServicePointManager]::SecurityProtocol=[Net.SecurityProtocolType]::Tls12; Invoke-WebRequest -Uri https://repo1.maven.org/maven2/org/postgresql/postgresql/42.7.3/postgresql-42.7.3.jar -OutFile lib\postgresql-42.7.3.jar"
  if errorlevel 1 (
    echo ERROR: download failed. Download postgresql-42.7.3.jar manually from https://jdbc.postgresql.org/download/ and put it into the lib folder.
    pause
    exit /b 1
  )
)

if exist out rmdir /s /q out
mkdir out\classes
powershell -NoProfile -Command "Get-ChildItem -Recurse src\main\java -Filter *.java | ForEach-Object { '\"' + $_.FullName.Replace('\','/') + '\"' } | Set-Content -Encoding ASCII out\sources.txt"
javac --release 17 -encoding UTF-8 -d out\classes @out\sources.txt
if errorlevel 1 (
  echo.
  echo BUILD FAILED - see the messages above.
  pause
  exit /b 1
)
xcopy /e /i /y src\main\resources out\classes >nul
echo.
echo BUILD OK. Next: copy uems.properties.example to uems.properties, edit it, then run:  run.bat setup --reset --demo   and then   run.bat
pause
