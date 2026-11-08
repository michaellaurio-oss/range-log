@echo off
rem Builds the personal and share versions of Range Log and installs both on the phone.
rem Shows each step here; the full details go to build-and-install.log in this folder.
title Range Log - build and install
cd /d "%~dp0"
set LOG=%~dp0build-and-install.log
set JAVA_HOME=D:\Users\micha\Android\jdk-17
set ADB=D:\Users\micha\Android\sdk\platform-tools\adb.exe
set PROBLEMS=0
echo === %date% %time% === > "%LOG%"

echo.
echo  Range Log: build and install both apps
echo  ======================================
echo.

call :step "1/6  Getting the latest code from GitHub (branch main)..."
git fetch origin >> "%LOG%" 2>&1
git checkout main >> "%LOG%" 2>&1
git pull --ff-only >> "%LOG%" 2>&1
if errorlevel 1 (call :bad "Couldn't update the code. Building what's here.") else (
  for /f "delims=" %%C in ('git log --oneline -1') do call :ok "Latest: %%C"
)

call :step "2/6  Setting up the share app's signing key..."
copy /y "share-signing-key\keystore.properties" "keystore.properties" >> "%LOG%" 2>&1
if errorlevel 1 (call :bad "Key not found. The share app will use the test key.") else (call :ok "Key ready")

call :step "3/6  Building both apps. This takes 3 to 4 minutes, the window stays quiet meanwhile..."
call gradlew.bat --no-daemon assemblePersonalDebug assembleShareRelease >> "%LOG%" 2>&1
if errorlevel 1 (
  call :bad "Build failed. See build-and-install.log."
  goto :end
)
call :ok "Build finished"

call :step "4/6  Looking for the phone..."
"%ADB%" devices -l >> "%LOG%" 2>&1
"%ADB%" get-state >nul 2>&1
if errorlevel 1 (
  call :bad "No phone found. Plug it in with USB, allow USB debugging, then run this again."
  goto :end
)
call :ok "Phone connected"

call :step "5/6  Backing up the database of your app on the phone..."
if not exist phone-db-backup mkdir phone-db-backup
for /f "delims=" %%T in ('powershell -NoProfile -Command "Get-Date -Format yyyyMMdd-HHmm"') do set STAMP=%%T
"%ADB%" exec-out run-as com.laurio.rangelog cat databases/rangelog.db > "phone-db-backup\rangelog-%STAMP%.db" 2>> "%LOG%"
for %%F in ("phone-db-backup\rangelog-%STAMP%.db") do set SIZE=%%~zF
if "%SIZE%"=="0" (call :bad "Backup came out empty.") else (call :ok "Saved phone-db-backup\rangelog-%STAMP%.db")

call :step "6/6  Installing on the phone..."
"%ADB%" install -r "app\build\outputs\apk\personal\debug\app-personal-debug.apk" >> "%LOG%" 2>&1
if errorlevel 1 (call :bad "Your app (personal) didn't install. See the log.") else (call :ok "Your app (personal) updated, data kept")
"%ADB%" install -r "app\build\outputs\apk\share\release\app-share-release.apk" >> "%LOG%" 2>&1
if errorlevel 1 (call :bad "Share app didn't install. See the log.") else (call :ok "Share app installed")

:end
echo.
if "%PROBLEMS%"=="0" (echo  All done.) else (echo  Finished with %PROBLEMS% problem^(s^). Details are in build-and-install.log)
echo === DONE (problems: %PROBLEMS%) === >> "%LOG%"
echo.
pause
exit /b

:step
echo  %~1
echo --- %~1 >> "%LOG%"
exit /b
:ok
echo        OK: %~1
echo OK: %~1 >> "%LOG%"
exit /b
:bad
echo        PROBLEM: %~1
echo PROBLEM: %~1 >> "%LOG%"
set /a PROBLEMS+=1
exit /b
