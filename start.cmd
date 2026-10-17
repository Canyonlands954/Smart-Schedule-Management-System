@echo off
setlocal
cd /d "%~dp0"
where pwsh.exe >nul 2>&1
if errorlevel 1 (
    "%SystemRoot%\System32\WindowsPowerShell\v1.0\powershell.exe" -NoProfile -ExecutionPolicy Bypass -File "%~dp0start.ps1" %*
) else (
    pwsh.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0start.ps1" %*
)
pause
endlocal
