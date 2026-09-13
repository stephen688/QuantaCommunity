# Create Gitee repos via web session, then push admin/miniprogram.
# Requires: git credential-manager with saved gitee.com credentials.

$ErrorActionPreference = 'Stop'

function Get-GiteeCredential {
    $raw = @"
protocol=https
host=gitee.com

"@ | git credential-manager get
    $user = ($raw | Select-String '^username=').Line.Substring(9)
    $pass = ($raw | Select-String '^password=').Line.Substring(9)
    return @{ User = $user; Pass = $pass }
}

function New-GiteeRepoWeb {
    param(
        [Microsoft.PowerShell.Commands.WebRequestSession]$Session,
        [string]$Name,
        [string]$Description
    )
    $newPage = Invoke-WebRequest -Uri 'https://gitee.com/projects/new' -WebSession $Session -UseBasicParsing
    if ($newPage.Content -notmatch 'name="authenticity_token" value="([^"]+)"') {
        throw 'Cannot parse CSRF token on new project page'
    }
    $csrf = $Matches[1]
    $form = @{
        'authenticity_token'   = $csrf
        'project[name]'      = $Name
        'project[path]'      = $Name
        'project[description]' = $Description
        'project[public]'    = '1'
        'project[init_repo]' = '0'
        'commit'             = 'Create'
    }
    try {
        $result = Invoke-WebRequest -Uri 'https://gitee.com/deng-jingwen0v0/projects' -Method Post -WebSession $Session -Body $form -MaximumRedirection 10 -UseBasicParsing
        $finalUrl = $result.BaseResponse.ResponseUri.AbsoluteUri
    } catch {
        $resp = $_.Exception.Response
        if ($resp -and [int]$resp.StatusCode -eq 302) {
            $finalUrl = $resp.Headers['Location']
        } else {
            throw
        }
    }
    if ($finalUrl -match "/$([regex]::Escape($Name))(?:/|$)") {
        Write-Host "[OK] Created repo: $Name"
        return
    }
}

function Test-GiteeRepoExists([string]$Name) {
    try {
        Invoke-WebRequest -Uri "https://gitee.com/deng-jingwen0v0/$Name" -Method Head -UseBasicParsing | Out-Null
        return $true
    } catch {
        return $false
    }
}

function Push-Project([string]$Path, [string]$RepoName) {
    $remote = "git@gitee.com:deng-jingwen0v0/$RepoName.git"
    Push-Location $Path
    try {
        if (git remote | Select-String -Quiet '^origin$') {
            git remote set-url origin $remote
        } else {
            git remote add origin $remote
        }
        git push -u origin master
        Write-Host "[OK] Pushed $RepoName"
    } finally {
        Pop-Location
    }
}

$cred = Get-GiteeCredential
$session = New-Object Microsoft.PowerShell.Commands.WebRequestSession
$loginPage = Invoke-WebRequest -Uri 'https://gitee.com/login' -WebSession $session -UseBasicParsing
if ($loginPage.Content -notmatch 'name="authenticity_token" value="([^"]+)"') {
    throw 'Cannot parse CSRF token on login page'
}
$loginToken = $Matches[1]
$loginBody = @{
    'authenticity_token' = $loginToken
    'user[login]'        = $cred.User
    'user[password]'     = $cred.Pass
    'commit'             = 'Sign in'
}
Invoke-WebRequest -Uri 'https://gitee.com/login' -Method Post -WebSession $session -Body $loginBody -UseBasicParsing | Out-Null

$repos = @(
    @{ Name = 'demo0-admin'; Desc = 'Quanta admin Vue app'; Path = (Resolve-Path (Join-Path $PSScriptRoot '..\..\demo0-admin')).Path },
    @{ Name = 'demo0-miniprogram'; Desc = 'Quanta WeChat miniprogram'; Path = (Resolve-Path (Join-Path $PSScriptRoot '..\..\demo0-miniprogram')).Path }
)

foreach ($r in $repos) {
    if (-not (Test-GiteeRepoExists $r.Name)) {
        New-GiteeRepoWeb -Session $session -Name $r.Name -Description $r.Desc
    } else {
        Write-Host "[SKIP] Repo exists: $($r.Name)"
    }
    Push-Project -Path $r.Path -RepoName $r.Name
}

Write-Host 'Done.'
