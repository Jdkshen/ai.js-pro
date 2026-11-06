@echo off
rem Remote chess engine service (LAN assist) - phone sends "my move" search requests here.
rem Usage: double-click this file. Keep the window OPEN (closing it stops the service;
rem        the phone will then silently fall back to its local engine).
chcp 65001 >nul
title Chess remote engine (port 8787)
cd /d "%~dp0"
echo [start] remote engine service on port 8787  (phone: http://^<this-PC-IP^>:8787)
echo [note]  PC must NOT sleep; if the PC IP changes, update remoteEngine.url in the phone autorun
echo.
python "%~dp0remote-engine-server.py" --host 0.0.0.0 --threads 8 --hash 1024
echo.
echo [exit] service stopped
pause
