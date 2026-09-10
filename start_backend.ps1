$backendPath = Join-Path $PSScriptRoot "backend"
$pythonPath = Join-Path $backendPath ".venv\Scripts\python.exe"

# Check whether FastAPI is already running
$existing = Get-NetTCPConnection `
    -LocalPort 8000 `
    -State Listen `
    -ErrorAction SilentlyContinue

if ($existing) {
    Write-Host "FastAPI is already running on port 8000."
    exit 0
}

Write-Host "Starting Discogs FastAPI backend..."

# Start Uvicorn directly with the virtual environment's Python
Start-Process `
    -FilePath $pythonPath `
    -WorkingDirectory $backendPath `
    -ArgumentList @(
        "-m",
        "uvicorn",
        "main:app",
        "--host",
        "0.0.0.0",
        "--port",
        "8000",
        "--reload"
    )

Write-Host "Waiting for FastAPI..."

# Wait up to about 15 seconds for port 8000
$started = $false

for ($i = 0; $i -lt 30; $i++) {

    Start-Sleep -Milliseconds 500

    $connection = Get-NetTCPConnection `
        -LocalPort 8000 `
        -State Listen `
        -ErrorAction SilentlyContinue

    if ($connection) {
        $started = $true
        break
    }
}

if ($started) {
    Write-Host "FastAPI started successfully on port 8000."
    exit 0
}

Write-Error "FastAPI failed to start."
exit 1