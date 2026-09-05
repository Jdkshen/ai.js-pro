@echo off
rem ============================================================
rem  move-dotnet-to-d-DELETE.bat
rem  Runs the migration AND deletes the original C:\Program Files\dotnet
rem  after verification (frees ~5.2 GB). Place next to move-dotnet-to-d.ps1
rem ============================================================

rem --- Check for administrator privileges ---
net session >nul 2>&1
if %errorlevel% neq 0 (
    echo Requesting administrator privileges...
    powershell -NoProfile -Command "Start-Process -FilePath '%~f0' -Verb RunAs"
    exit /b
)

echo ============================================
echo  Running as administrator
echo  Migrating .NET SDK to D:\dotnet and
echo  DELETING C:\Program Files\dotnet after verify.
echo ============================================

powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0move-dotnet-to-d.ps1" -DeleteOriginal

echo.
echo Done. Please restart VS Code / terminals for the new PATH.
pause
