# demo0 API auto test (Phase 0-3) - uses curl.exe for reliable JSON on Windows
# Usage: .\scripts\run-phase.ps1 [-Phase all|0-1|2-3]

param(
    [ValidateSet('0-1', '2-3', 'all')]
    [string]$Phase = 'all'
)

$ErrorActionPreference = 'Stop'
$ApiTestDir = Split-Path -Parent $PSScriptRoot
$BaseUrl = 'http://localhost:9191'
$OutDir = Join-Path $ApiTestDir 'test-output'
$OutFile = Join-Path $OutDir 'last-run.json'
$TmpDir = Join-Path $env:TEMP 'demo0-api-test'
$Fixture = Join-Path $ApiTestDir 'cases\fixtures\pixel.png'

New-Item -ItemType Directory -Force -Path $OutDir, $TmpDir | Out-Null

function Write-JsonFile([string]$Path, [string]$Json) {
    [System.IO.File]::WriteAllText($Path, $Json, (New-Object System.Text.UTF8Encoding $false))
}

function Invoke-CurlApi {
    param(
        [string]$Name,
        [string]$Method = 'GET',
        [string]$Path,
        [string]$Token,
        [string]$JsonBody,
        [string]$FormFile
    )
    $uri = "$BaseUrl$Path"
    $args = @('-s', '-w', "`n__HTTP__:%{http_code}", '-X', $Method, $uri)
    if ($Token) { $args += @('-H', "authorization: $Token") }
    if ($JsonBody) {
        $bodyFile = Join-Path $TmpDir "$Name-body.json"
        Write-JsonFile $bodyFile $JsonBody
        $args += @('-H', 'Content-Type: application/json', '--data-binary', "@$bodyFile")
    }
    if ($FormFile) {
        $args += @('-F', "file=@$FormFile")
    }
    $raw = & curl.exe @args 2>&1
    $text = ($raw | Out-String).Trim()
    $http = $null
    $bodyText = $text
    if ($text -match '__HTTP__:(\d+)\s*$') {
        $http = [int]$Matches[1]
        $bodyText = $text -replace "`n__HTTP__:\d+\s*$", ''
    }
    $body = $null
    if ($bodyText) {
        try { $body = $bodyText | ConvertFrom-Json -ErrorAction Stop } catch {
            if ($bodyText -match '"code"\s*:\s*(\d+)') { $body = [PSCustomObject]@{ code = [int]$Matches[1]; msg = $bodyText } }
        }
    }
  return @{
        name = $Name
        http = $http
        body = $body
        raw  = $bodyText
        pass = $false
        note = ''
    }
}

$results = [System.Collections.ArrayList]@()
$envVars = @{ BASE_URL = $BaseUrl }

function Add-R([hashtable]$r) { [void]$results.Add($r) }

function Login-Token {
    $r = Invoke-CurlApi -Name 'login' -Method POST -Path '/user/login' -JsonBody '{"code":"test"}'
    if ($r.body -and $r.body.code -eq 200 -and $r.body.data.token) { return $r.body.data.token }
    return $null
}

if ($Phase -in @('0-1', 'all')) {
    $r = Invoke-CurlApi -Name 'P0-01' -Path '/user/info'
    $r.pass = ($r.http -eq 401)
    Add-R $r

    $r = Invoke-CurlApi -Name 'P0-02' -Method POST -Path '/user/login' -JsonBody '{"code":"test"}'
    $r.pass = ($r.http -eq 200 -and $r.body.code -eq 200 -and $r.body.data.token)
    if ($r.pass) {
        $envVars.USER_TOKEN = $r.body.data.token
        $envVars.USER_ID = [string]$r.body.data.id
    }
    Add-R $r
    $userToken = $envVars.USER_TOKEN

    $r = Invoke-CurlApi -Name 'P1-01' -Method POST -Path '/user/login' -JsonBody '{"code":""}'
    $r.pass = ($r.body.code -eq 400)
    Add-R $r

    $r = Invoke-CurlApi -Name 'P1-03' -Path '/user/info'
    $r.pass = ($r.http -eq 401)
    Add-R $r

    $r = Invoke-CurlApi -Name 'P1-04' -Path '/user/info' -Token 'invalid-token'
    $r.pass = ($r.http -eq 401)
    Add-R $r

    $r = Invoke-CurlApi -Name 'P1-05' -Path '/user/info' -Token $userToken
    $r.pass = ($r.http -eq 200 -and $r.body.code -eq 200)
    Add-R $r

    $r = Invoke-CurlApi -Name 'P1-06' -Path '/admin/user/page?pageNum=1&pageSize=10' -Token $userToken
    $r.pass = ($r.http -eq 403)
    if (-not $r.pass -and $r.http -eq 200) {
        $r.note = 'test user is_admin=1; run SQL is_admin=0 before P1-06 or use USER_B'
    }
    Add-R $r

    # Admin login invalidates Redis token for same user; run after P1-06 and refresh both tokens
    $r = Invoke-CurlApi -Name 'P0-03' -Method POST -Path '/user/login' -JsonBody '{"code":"test"}'
    $r.pass = ($r.body.code -eq 200 -and $r.body.data.token)
    if ($r.pass) {
        $envVars.ADMIN_TOKEN = $r.body.data.token
        $envVars.USER_TOKEN = $r.body.data.token
    }
    else { $r.note = 'need: UPDATE tb_user SET is_admin=1 WHERE openid=test_openid_123456' }
    Add-R $r

    $adminToken = $envVars.ADMIN_TOKEN
    if ($adminToken) {
        $r = Invoke-CurlApi -Name 'P1-07' -Path '/admin/user/page?pageNum=1&pageSize=10' -Token $adminToken
        $r.pass = ($r.http -eq 200 -and $r.body.code -eq 200)
        Add-R $r
    }
}

if ($Phase -in @('2-3', 'all')) {
    $fresh = Login-Token
    if ($fresh) {
        $envVars.USER_TOKEN = $fresh
        $envVars.ADMIN_TOKEN = $fresh
    }
    $userToken = $envVars.USER_TOKEN
    $adminToken = $envVars.ADMIN_TOKEN

    if (Test-Path $Fixture) {
        $r = Invoke-CurlApi -Name 'P2-01' -Method POST -Path '/common/upload' -Token $userToken -FormFile $Fixture
        $r.pass = ($r.body -and $r.body.code -eq 200 -and $r.body.data)
        if ($r.pass) { $envVars.IMAGE_URL = [string]$r.body.data }
        else { $r.note = 'BLOCKED: OSS' }
        Add-R $r
    }

    $r = Invoke-CurlApi -Name 'P2-04' -Method POST -Path '/content/publish' -Token $userToken `
        -JsonBody '{"contentType":2,"title":"API-test-prof","content":"prof desc","images":[]}'
    $r.pass = ($r.body -and $r.body.code -eq 200 -and $r.body.data.contentId)
    if ($r.pass) {
        $envVars.CONTENT_ID_PROF = [string]$r.body.data.contentId
        $envVars.QUESTION_ID = [string]$r.body.data.contentId
    }
    elseif ($r.body.code -eq 500) { $r.note = 'server 500 - restart app after collectCount fix' }
    Add-R $r

    $r = Invoke-CurlApi -Name 'P2-05' -Method POST -Path '/content/publish' -Token $userToken `
        -JsonBody '{"contentType":1,"title":"API-test-life","content":"life desc","images":[]}'
    $r.pass = ($r.body -and $r.body.code -eq 200 -and $r.body.data.contentId)
    if ($r.pass) { $envVars.CONTENT_ID_LIFE = [string]$r.body.data.contentId }
    Add-R $r

    $r = Invoke-CurlApi -Name 'P2-06' -Method POST -Path '/content/publish' -Token $userToken `
        -JsonBody '{"contentType":2,"title":"","content":"ok","images":[]}'
    $r.pass = ($r.body.code -eq 400)
    Add-R $r

    $profId = $envVars.CONTENT_ID_PROF
    $lifeId = $envVars.CONTENT_ID_LIFE

    if ($adminToken -and $profId) {
        $r = Invoke-CurlApi -Name 'P3-01' -Path "/admin/content/page?pageNum=1&pageSize=10&auditStatus=0" -Token $adminToken
        $r.pass = ($r.body.code -eq 200)
        Add-R $r

        $r = Invoke-CurlApi -Name 'P3-04' -Method POST -Path '/admin/content/audit' -Token $adminToken `
            -JsonBody "{`"contentId`":$profId,`"auditResult`":1}"
        $r.pass = ($r.body.code -eq 200)
        Add-R $r
    }

    if ($adminToken -and $lifeId) {
        $r = Invoke-CurlApi -Name 'P3-05' -Method POST -Path '/admin/content/audit' -Token $adminToken `
            -JsonBody "{`"contentId`":$lifeId,`"auditResult`":2,`"rejectReason`":`"api-reject`"}"
        $r.pass = ($r.body.code -eq 200)
        Add-R $r
    }

    if ($profId) {
        $r = Invoke-CurlApi -Name 'P3-06' -Method POST -Path '/answer/publish' -Token $userToken `
            -JsonBody "{`"questionId`":$profId,`"content`":`"answer after audit`"}"
        $r.pass = ($r.body.code -eq 200)
        if ($r.pass -and $r.body.data.answerId) { $envVars.ANSWER_ID = [string]$r.body.data.answerId }
        Add-R $r
    }

    if ($lifeId) {
        $r = Invoke-CurlApi -Name 'P3-07' -Method POST -Path '/answer/publish' -Token $userToken `
            -JsonBody "{`"questionId`":$lifeId,`"content`":`"life no answer`"}"
        $r.pass = ($r.body.code -eq 400)
        Add-R $r
    }
}

$envPath = Join-Path $ApiTestDir 'env.sh'
$lines = New-Object System.Collections.Generic.List[string]
[void]$lines.Add('# auto-generated by run-phase.ps1')
[void]$lines.Add("export BASE_URL=`"$BaseUrl`"")
foreach ($k in @('USER_TOKEN','ADMIN_TOKEN','USER_ID','CONTENT_ID_PROF','CONTENT_ID_LIFE','QUESTION_ID','ANSWER_ID','IMAGE_URL')) {
    $v = if ($envVars.ContainsKey($k)) { $envVars[$k] } else { '' }
    [void]$lines.Add("export $k=`"$v`"")
}
[System.IO.File]::WriteAllLines($envPath, $lines)

$summary = @{
    phase     = $Phase
    timestamp = (Get-Date -Format 'yyyy-MM-dd HH:mm:ss')
    passCount = @($results | Where-Object { $_.pass }).Count
    failCount = @($results | Where-Object { -not $_.pass }).Count
    env       = $envVars
    results   = @($results | ForEach-Object {
        @{ name = $_.name; pass = $_.pass; http = $_.http; code = if ($_.body) { $_.body.code } else { $null }; msg = if ($_.body) { $_.body.msg } else { $_.note } }
    })
}
$summary | ConvertTo-Json -Depth 6 | Set-Content -Path $OutFile -Encoding UTF8

Write-Host "PASS=$($summary.passCount) FAIL=$($summary.failCount) -> $OutFile"
foreach ($t in $results) {
    $s = if ($t.pass) { 'PASS' } else { 'FAIL' }
    $c = if ($t.body) { $t.body.code } else { '-' }
    Write-Host "  [$s] $($t.name) HTTP=$($t.http) code=$c $($t.note)"
}
