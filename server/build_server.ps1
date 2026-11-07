# Builds the packaged server: dist\tgflip-server-<version>-win64.zip
# Run from anywhere:  powershell -ExecutionPolicy Bypass -File server\build_server.ps1
# Needs the venv with requirements.txt and requirements-build.txt installed.
param([string]$Version,  # default: VERSION in server.py
      [string]$Venv = "venv")  # the virtual environment (in the repo) to build with
$ErrorActionPreference = "Stop"

$server = $PSScriptRoot
if (-not $Version) {
    $line = Select-String -Path (Join-Path $server "tgflip\settings.py") -Pattern '^VERSION = "(.+)"' | Select-Object -First 1
    if (-not $line) { throw "VERSION not found in tgflip\settings.py" }
    $Version = $line.Matches[0].Groups[1].Value
}
$root = Split-Path $server
$python = Join-Path $root "$Venv\Scripts\python.exe"
$work = Join-Path $root "build\server"   # PyInstaller's working files (git-ignored)
$out = Join-Path $work "dist\tgflip-server"
$zip = Join-Path $root "dist\tgflip-server-$Version-win64.zip"

# Modules' compiled libraries built for Python's stable ABI (abi3, docs/modules-plan.md) need
# python3.dll, which PyInstaller leaves out when none of the server's own libraries use it
$python3dll = Join-Path (& $python -c "import sys; print(sys.base_prefix)") "python3.dll"
if (-not (Test-Path $python3dll)) { throw "python3.dll not found at $python3dll" }

# One folder with tgflip-server.exe and its libraries. uvicorn loads parts of itself by
# name at runtime, which PyInstaller cannot see, so all of it is collected explicitly.
# Modules use the server's Pillow, and may need parts the server itself doesn't import
# (the animated module 1.1.0's ImageStat was missing from the exes up to 1.0.0), so all of
# Pillow is collected too. The icon (since 1.1.0) is the owner's drawing, art/server-icon.png.
& $python -m PyInstaller --noconfirm --clean --onedir --console `
    --name tgflip-server `
    --icon (Join-Path $server "packaging\tgflip-server.ico") `
    --distpath (Join-Path $work "dist") --workpath (Join-Path $work "work") --specpath $work `
    --paths $server `
    --hidden-import server `
    --collect-submodules uvicorn `
    --collect-submodules PIL `
    --add-data "$server\static;static" `
    --add-binary "$python3dll;." `
    (Join-Path $server "run_server.py")
if ($LASTEXITCODE -ne 0) { throw "PyInstaller failed" }

Copy-Item (Join-Path $server "packaging\README.txt") $out
Copy-Item (Join-Path $server "packaging\Maintenance mode.bat") $out
Copy-Item (Join-Path $server ".env.example") $out

# Never ship secrets or personal data: settings, the accounts list, the Telegram logins and the
# local favourite stickers are created on the user's PC
$secrets = Get-ChildItem $out -Recurse -Force -Include ".env", "*.session", "*.session-journal", "local_favorites*.json", "accounts.json*"
if ($secrets) { throw "Refusing to pack secrets: $($secrets.FullName -join ', ')" }

New-Item -ItemType Directory -Force (Split-Path $zip) | Out-Null
if (Test-Path $zip) { Remove-Item $zip }
Compress-Archive -Path $out -DestinationPath $zip
# Its SHA-256 next to it (the same format as sha256sum), so updating can check the download
$hash = (Get-FileHash $zip -Algorithm SHA256).Hash.ToLower()
Set-Content -Path "$zip.sha256" -Value "$hash  $(Split-Path $zip -Leaf)" -Encoding ascii
Write-Host "Built $zip ($([math]::Round((Get-Item $zip).Length / 1MB, 1)) MB)"
Write-Host "sha256 $hash"
