@echo off
rem Opens the TG Flip server's maintenance menu (accounts, modules, settings), then the server.
rem The menu runs from a fresh copy of the program in the maintenance folder, so the program
rem itself isn't in use and can be updated from the menu. Full paths: some Windows setups don't
rem run programs from the current folder by name.
cd /d "%~dp0"
rem No program here: an update was cut off halfway. Last time's copy runs the menu, which puts
rem the version from before the update back.
if not exist "%~dp0tgflip-server.exe" if exist "%~dp0maintenance\tgflip-server.exe" goto menu
"%~dp0tgflip-server.exe" --prepare-maintenance
if errorlevel 1 (
  pause
  exit /b 1
)
:menu
rem One line with exit: Windows has read it whole before it runs, so a newer bat put in place
rem meanwhile can't mix in
"%~dp0maintenance\tgflip-server.exe" --maintenancemode --data "%~dp0." & exit /b
