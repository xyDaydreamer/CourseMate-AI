@echo off
setlocal
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\start-coursemate.ps1" %*
set "COURSEMATE_EXIT_CODE=%ERRORLEVEL%"
if not "%COURSEMATE_EXIT_CODE%"=="0" pause
exit /b %COURSEMATE_EXIT_CODE%
