@echo off
rem Opens the TG Flip server's maintenance menu (accounts, modules, settings), then the server.
rem The menu runs from a fresh copy of the program in the maintenance folder, so the program
rem itself isn't in use and can be updated from the menu. Full paths: some Windows setups don't
rem run programs from the current folder by name.
cd /d "%~dp0"
"%~dp0tgflip-server.exe" --prepare-maintenance
if errorlevel 1 (
  pause
  exit /b 1
)
"%~dp0maintenance\tgflip-server.exe" --maintenancemode --data "%~dp0."
