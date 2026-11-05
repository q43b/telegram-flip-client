# Builds the packaged server: dist\tgflip-server-<version>-win64.zip
# Run from anywhere:  powershell -ExecutionPolicy Bypass -File server\build_server.ps1
# Needs the venv with requirements.txt and requirements-build.txt installed.
param([string]$Version = "0.2.0-alpha")
$ErrorActionPreference = "Stop"

$server = $PSScriptRoot
$root = Split-Path $server
$python = Join-Path $root "venv\Scripts\python.exe"
$work = Join-Path $root "build\server"   # PyInstaller's working files (git-ignored)
$out = Join-Path $work "dist\tgflip-server"
$zip = Join-Path $root "dist\tgflip-server-$Version-win64.zip"

# One folder with tgflip-server.exe and its libraries. uvicorn loads parts of itself by
# name at runtime, which PyInstaller cannot see, so all of it is collected explicitly.
& $python -m PyInstaller --noconfirm --clean --onedir --console `
    --name tgflip-server `
    --distpath (Join-Path $work "dist") --workpath (Join-Path $work "work") --specpath $work `
    --paths $server `
    --hidden-import server `
    --collect-submodules uvicorn `
    --add-data "$server\static;static" `
    (Join-Path $server "run_server.py")
if ($LASTEXITCODE -ne 0) { throw "PyInstaller failed" }

Copy-Item (Join-Path $server "packaging\README.txt") $out
Copy-Item (Join-Path $server ".env.example") $out

# Never ship secrets: settings and the Telegram login are created on the user's PC
$secrets = Get-ChildItem $out -Recurse -Force -Include ".env", "*.session", "*.session-journal"
if ($secrets) { throw "Refusing to pack secrets: $($secrets.FullName -join ', ')" }

New-Item -ItemType Directory -Force (Split-Path $zip) | Out-Null
if (Test-Path $zip) { Remove-Item $zip }
Compress-Archive -Path $out -DestinationPath $zip
Write-Host "Built $zip ($([math]::Round((Get-Item $zip).Length / 1MB, 1)) MB)"
