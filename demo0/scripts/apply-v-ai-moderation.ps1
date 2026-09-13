# Apply V_ai_moderation.sql to MySQL (Windows-friendly, UTF-8)
param(
    [string]$DbHost = "127.0.0.1",
    [int]$Port = 3306,
    [string]$User = "root",
    [Parameter(Mandatory = $true)]
    [string]$Password,
    [string]$Database = "demo"
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$sqlFile = Join-Path $root "src\main\resources\db\V_ai_moderation.sql"

if (-not (Test-Path $sqlFile)) {
    Write-Error "SQL file not found: $sqlFile"
}

# Use cmd.exe so input redirection preserves UTF-8 from file (PowerShell pipe may corrupt comments)
$mysqlArgs = @(
    "--host=$DbHost", "--port=$Port", "--user=$User", "-p$Password",
    "--default-character-set=utf8mb4", $Database
)
Write-Host "Applying $sqlFile to database '$Database'..."
cmd /c "mysql $($mysqlArgs -join ' ') < `"$sqlFile`""
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

mysql @("--host=$DbHost", "--port=$Port", "--user=$User", "-p$Password", $Database) `
    -e "SHOW TABLES LIKE 'tb_moderation_record'; DESCRIBE tb_moderation_record;"
Write-Host "Done."
