# Quick admin API smoke test (requires backend on VITE_DEV_PROXY_TARGET, default :9191)
# Usage: from demo0-admin root:  powershell -File .\scripts\check-admin-api.ps1
param(
  [string]$BaseUrl = "http://localhost:9191"
)

Set-Location $PSScriptRoot\..
$ErrorActionPreference = "Stop"

function Invoke-Api {
  param(
    [string]$Method = "GET",
    [string]$Path,
    [object]$Body = $null,
    [string]$Token = $null
  )
  $uri = "$BaseUrl$Path"
  $headers = @{ "Content-Type" = "application/json" }
  if ($Token) { $headers["authorization"] = $Token }
  $params = @{
    Uri         = $uri
    Method      = $Method
    Headers     = $headers
    UseBasicParsing = $true
  }
  if ($null -ne $Body) {
    $params.Body = ($Body | ConvertTo-Json -Compress)
  }
  $resp = Invoke-WebRequest @params
  return $resp.Content | ConvertFrom-Json
}

function Assert-Ok {
  param($Result, [string]$Label)
  if ($Result.code -ne 200) {
    throw "$Label failed: code=$($Result.code) msg=$($Result.msg)"
  }
}

$failures = @()
$passed = 0

function Test-Step {
  param([string]$Name, [scriptblock]$Action)
  try {
    & $Action
    Write-Host "[OK] $Name" -ForegroundColor Green
    $script:passed++
  } catch {
    Write-Host "[FAIL] $Name — $($_.Exception.Message)" -ForegroundColor Red
    $script:failures += $Name
  }
}

Write-Host "Admin API smoke test → $BaseUrl`n"

$token = $null

Test-Step "POST /user/login (code=test)" {
  $r = Invoke-Api -Method POST -Path "/user/login" -Body @{ code = "test" }
  Assert-Ok $r "login"
  if (-not $r.data.token) { throw "no token in response" }
  $script:token = $r.data.token
}

Test-Step "GET /user/info" {
  $r = Invoke-Api -Path "/user/info" -Token $token
  Assert-Ok $r "user/info"
}

Test-Step "GET /admin/user/page" {
  $r = Invoke-Api -Path "/admin/user/page?pageNum=1&pageSize=1" -Token $token
  Assert-Ok $r "user/page"
  if ($null -eq $r.data.total) { throw "missing total" }
}

Test-Step "GET /admin/content/page" {
  $r = Invoke-Api -Path "/admin/content/page?pageNum=1&pageSize=1&auditStatus=0" -Token $token
  Assert-Ok $r "content/page"
}

Test-Step "GET /admin/answer/page" {
  $r = Invoke-Api -Path "/admin/answer/page?pageNum=1&pageSize=1&auditStatus=0" -Token $token
  Assert-Ok $r "answer/page"
}

Test-Step "GET /admin/comment/page" {
  $r = Invoke-Api -Path "/admin/comment/page?pageNum=1&pageSize=1" -Token $token
  Assert-Ok $r "comment/page"
}

Test-Step "GET /admin/content/report/page" {
  $r = Invoke-Api -Path "/admin/content/report/page?pageNum=1&pageSize=1&status=0" -Token $token
  Assert-Ok $r "content/report/page"
}

Test-Step "GET /admin/comment/report/page" {
  $r = Invoke-Api -Path "/admin/comment/report/page?pageNum=1&pageSize=1&status=0" -Token $token
  Assert-Ok $r "comment/report/page"
}

Test-Step "GET /admin/identityExam/page" {
  $r = Invoke-Api -Path "/admin/identityExam/page?pageNum=1&pageSize=1&auditStatus=0" -Token $token
  Assert-Ok $r "identityExam/page"
}

Test-Step "GET /content/detail (public read)" {
  $cp = Invoke-Api -Path "/admin/content/page?pageNum=1&pageSize=5" -Token $token
  Assert-Ok $cp "content/page for detail probe"
  $cid = $null
  foreach ($row in $cp.data.records) {
    $probe = Invoke-Api -Path "/content/detail/$($row.contentId)" -Token $token
    if ($probe.code -eq 200) { $cid = $row.contentId; break }
  }
  if (-not $cid) { throw "no content detail available for smoke test" }
}

Write-Host "`nPassed: $passed | Failed: $($failures.Count)"
if ($failures.Count -gt 0) {
  Write-Host "Failures: $($failures -join ', ')" -ForegroundColor Red
  exit 1
}
exit 0
