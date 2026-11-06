@echo off
rem Opens the TG Flip server's maintenance menu (add, remove or rename accounts), then the server
cd /d "%~dp0"
tgflip-server.exe --maintenancemode
