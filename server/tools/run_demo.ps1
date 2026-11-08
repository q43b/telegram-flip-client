# Runs a demo server from the source: one made-up account (kind mockgram, the showcase scene) and
# nothing else, on its own port, so it can sit next to the real server and touches no real data.
# It reads nothing from server\.env and has no Telegram session.
#
#   powershell -ExecutionPolicy Bypass -File server\tools\run_demo.ps1
#
# Then in the app: Settings > Network > Account > + Add an account, the PC's address (printed below),
# port 9449 and token demotoken12. Close the window (or press Ctrl+C) to stop it.
param(
    [int]$Port = 9449,
    [string]$Token = "demotoken12",
    [string]$Scene = "showcase"
)

$repo = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$python = Join-Path $repo "venv\Scripts\python.exe"
$data = Join-Path $env:TEMP "tgflip-demo"

# A fresh folder each time, so the scene starts from the top (relative times, unread counts)
if (Test-Path $data) { Remove-Item -Recurse -Force $data }
New-Item -ItemType Directory -Force (Join-Path $data "modules") | Out-Null
Copy-Item -Recurse (Join-Path $repo "modules\mockgram") (Join-Path $data "modules\mockgram")

"API_ID=1`nAPI_HASH=00000000000000000000000000000000`nAPI_TOKEN=unused`nPROXY_HOST=`nPORT=$Port`n" |
    Set-Content -Encoding ascii (Join-Path $data ".env")
$accounts = '[{"name":"demo","token":"' + $Token + '","session":"mockgram_1","local_favorites":"local_favorites_demo.json","admin":false,"kind":"mockgram","settings":{"scene":"' + $Scene + '"}}]'
Set-Content -Encoding ascii (Join-Path $data "accounts.json") $accounts

# Settings given in the environment win over any .env found from the source folder
$env:API_ID = "1"; $env:API_HASH = "0" * 32; $env:API_TOKEN = "unused"; $env:PROXY_HOST = ""
$env:PORT = "$Port"; $env:SERVER_MODE = "private"; $env:SERVER_CACHE = "forever"; $env:SERVER_CACHE_MB = ""

$addresses = Get-NetIPAddress -AddressFamily IPv4 | Where-Object { $_.IPAddress -notlike "127.*" -and $_.IPAddress -notlike "169.254.*" } |
    ForEach-Object { $_.IPAddress }
Write-Host "Demo server: port $Port, token $Token. This PC's addresses: $($addresses -join ', ')"
Set-Location (Join-Path $repo "server")
& $python run_server.py --data $data
