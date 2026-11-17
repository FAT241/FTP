param(
    [ValidateSet("setup", "server", "account", "test")]
    [string]$Mode = "server",
    [string]$HostAddress = "127.0.0.1",
    [int]$Port = 2121
)

$ErrorActionPreference = "Stop"
$root = $PSScriptRoot
$python = Join-Path $root ".venv/Scripts/python.exe"
$configDir = Join-Path $env:LOCALAPPDATA "Lab2FTP"
$dbFile = Join-Path $configDir "database.credential.xml"
$ftpFile = Join-Path $configDir "ftp.credential.xml"

function New-LocalCredential([string]$Name, [string]$Path) {
    if (Test-Path -LiteralPath $Path) { return }
    $secret = ConvertTo-SecureString ([guid]::NewGuid().ToString("N")) -AsPlainText -Force
    [pscredential]::new($Name, $secret) | Export-Clixml -LiteralPath $Path
}

if ($Mode -eq "setup") {
    if (!(Test-Path -LiteralPath $python)) {
        py -3.11 -m venv (Join-Path $root ".venv")
        if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    }
    & $python -m pip install --disable-pip-version-check -r (Join-Path $root "requirements.txt")
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    New-Item -ItemType Directory -Force $configDir | Out-Null
    New-LocalCredential "lab2_ftp" $dbFile
    New-LocalCredential "ftp_demo" $ftpFile
}
if (!(Test-Path -LiteralPath $dbFile) -or !(Test-Path -LiteralPath $ftpFile)) {
    throw "Chạy ./run.ps1 setup trước."
}

$ftp = Import-Clixml -LiteralPath $ftpFile
if ($Mode -eq "account") {
    Write-Output "Username: $($ftp.UserName)"
    Write-Output "Password: $($ftp.GetNetworkCredential().Password)"
    return
}

$db = Import-Clixml -LiteralPath $dbFile
$priorDb = $env:LAB2_DB_PASSWORD
$priorFtp = $env:LAB2_FTP_PASSWORD
$priorAdmin = $env:LAB2_PG_ADMIN_PASSWORD
try {
    if ($Mode -ne "test") {
        $env:LAB2_DB_PASSWORD = $db.GetNetworkCredential().Password
    }
    if ($Mode -ne "server") {
        $env:LAB2_FTP_PASSWORD = $ftp.GetNetworkCredential().Password
    }
    switch ($Mode) {
        "setup" {
            if (!$env:LAB2_PG_ADMIN_PASSWORD) {
                $secret = Read-Host "Mật khẩu quản trị PostgreSQL (postgres)" -AsSecureString
                $admin = [pscredential]::new("postgres", $secret)
                $env:LAB2_PG_ADMIN_PASSWORD = $admin.GetNetworkCredential().Password
            }
            & $python (Join-Path $root "ftp_server.py") setup
        }
        "server" {
            & $python (Join-Path $root "ftp_server.py") server --port $Port
        }
        "test" {
            $env:LAB2_DB_PASSWORD = $db.GetNetworkCredential().Password
            & $python (Join-Path $root "smoke_test.py") $HostAddress $Port
        }
    }
    exit $LASTEXITCODE
} finally {
    $env:LAB2_DB_PASSWORD = $priorDb
    $env:LAB2_FTP_PASSWORD = $priorFtp
    $env:LAB2_PG_ADMIN_PASSWORD = $priorAdmin
}
