@echo off
title Range Logger - new signing key
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0make-new-share-key.ps1"
if errorlevel 1 echo  PROBLEM: something went wrong. The old key is still in share-signing-key\old-*.
echo.
pause
