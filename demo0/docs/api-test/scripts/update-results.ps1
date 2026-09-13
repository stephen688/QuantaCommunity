# Apply test-output/full-run.json to RESULTS.md (index + sections + summary)
param(
    [string]$JsonPath = (Join-Path (Split-Path -Parent $PSScriptRoot) 'test-output\full-run.json'),
    [string]$ResultsPath = (Join-Path (Split-Path -Parent $PSScriptRoot) 'RESULTS.md')
)

$data = Get-Content -Raw -Path $JsonPath | ConvertFrom-Json
$apis = @{}
$data.apis.PSObject.Properties | ForEach-Object { $apis[$_.Name] = $_.Value }

$indexMap = @{
    'U-01'='POST | /user/login'; 'U-02'='GET | /user/info'; 'U-03'='PUT | /user/info/update'
    'U-04'='POST | /user/auth/add'; 'U-05'='GET | /user/auth/status'; 'U-06'='GET | /user/auth/detail'
    'U-07'='GET | /user/content/my/list'; 'U-08'='GET | /user/content/my/liked'; 'U-09'='GET | /user/content/my/collect'
    'U-10'='GET | /user/content/my/browseHistory'; 'U-11'='DELETE | /user/browse/history/clear'
    'U-12'='GET | /user/{userId}/profile'; 'U-13'='GET | /user/{userId}/contents'
    'C-01'='POST | /content/publish'; 'C-02'='GET | /content/recommend'; 'C-03'='GET | /content/detail/{contentId}'
    'C-04'='DELETE | /content/delete/{contentId}'; 'C-05'='POST | /content/like/{contentId}'; 'C-06'='POST | /content/collect/{contentId}'
    'C-07'='POST | /content/report'
    'A-01'='POST | /answer/publish'; 'A-02'='GET | /answer/list/{questionId}'; 'A-03'='POST | /answer/accept/{answerId}'
    'A-04'='POST | /answer/like/{answerId}'; 'A-05'='DELETE | /answer/{answerId}'; 'A-06'='GET | /answer/{answerId}'
    'M-01'='POST | /comment/send'; 'M-02'='GET | /comment/list'; 'M-03'='GET | /comment/replyList'
    'M-04'='DELETE | /comment/delete/{commentId}'; 'M-05'='POST | /comment/like/{commentId}'; 'M-06'='POST | /comment/report'
    'N-01'='GET | /notification/list'; 'N-02'='GET | /notification/unreadCount'; 'N-03'='PUT | /notification/read/{id}'
    'N-04'='PUT | /notification/readAll'
    'S-01'='GET | /search/content'; 'S-02'='GET | /search/history/keywords'; 'S-03'='DELETE | /search/history/clear'
    'S-04'='DELETE | /search/history/deleteOne/{id}'; 'S-05'='GET | /search/trending'
    'F-01'='POST | /follow/{id}'; 'F-02'='GET | /follow/feed'
    'R-01'='POST | /rag/search'; 'O-01'='POST | /common/upload'
    'AD-01'='GET | /admin/content/page'; 'AD-02'='POST | /admin/content/audit'; 'AD-03'='DELETE | /admin/content/{contentId}'
    'AD-04'='GET | /admin/content/report/page'; 'AD-05'='POST | /admin/content/report/handle'
    'AD-06'='GET | /admin/user/page'; 'AD-07'='GET | /admin/user/{id}'; 'AD-08'='POST | /admin/user/ban/{userId}'
    'AD-09'='POST | /admin/user/unban/{userId}'; 'AD-10'='GET | /admin/comment/page'; 'AD-11'='DELETE | /admin/comment/{commentId}'
    'AD-12'='GET | /admin/comment/report/page'; 'AD-13'='POST | /admin/comment/report/handle'
    'AD-14'='GET | /admin/answer/page'; 'AD-15'='DELETE | /admin/answer/{answerId}'; 'AD-16'='POST | /admin/answer/audit'
    'AD-17'='GET | /admin/identityExam/page'; 'AD-18'='GET | /admin/identityExam/userAuth/detail/{authId}'
    'AD-19'='POST | /admin/identityExam/audit'
}

function Format-CaseTable($caseList) {
    $lines = @('| 用例ID | 类型 | 状态 | 预期 | 实际 | 备注 |', '|--------|------|------|------|------|------|')
    foreach ($c in $caseList) {
        $lines += "| $($c.id) | $($c.type) | $($c.status) | $($c.expected) | $($c.actual) | $($c.note) |"
    }
    return ($lines -join "`n")
}

function Update-Section {
    param([string]$Content, [string]$ApiId, $Api)
    $st = $Api.status
    $http = if ($Api.http) { $Api.http } else { '—' }
    $code = if ($null -ne $Api.code -and $Api.code -ne '') { $Api.code } else { '—' }
    $msg = if ($Api.msg) { $Api.msg } else { '—' }
    $conclusion = $Api.conclusion
    $issues = $Api.issues
    $caseTable = Format-CaseTable @($Api.cases)
    $date = $data.date

    $pattern = "(?s)(### $([regex]::Escape($ApiId))[^\n]*\n\n\| 项 \| 内容 \|\n\|----\|------\|\n)(\| \*\*接口状态\*\* \| `)[^`]+(` \|\n\| 最后执行 \| )[^|]+(\| \n\| HTTP \| )[^|]+(\| \n\| body\.code \| )[^|]+(\| \n\| body\.msg \| )[^|]+(\| \n\| \*\*结论\*\* \| )[^|]+(\| \n\n#### 用例明细\n\n)(\| 用例ID \| 类型 \| 状态 \| 预期 \| 实际 \| 备注 \|\n\|--------\|------\|------\|------\|------\|------\|\n)(?:\|[^\n]+\n)*(\n#### 问题与修复\n\n)[^\n]+(\n\n#### 请求/响应摘录\n\n)[^\n]*"

    $replacement = "`${1}`$st`${2}$date`${3}$http`${4}$code`${5}$msg`${6}$conclusion`${7}$caseTable`${8}$issues`${9}—"
    return [regex]::Replace($Content, $pattern, $replacement, 1)
}

$md = Get-Content -Raw -Path $ResultsPath -Encoding UTF8
$date = $data.date

foreach ($id in $apis.Keys) {
    if ($md -match "### $id ") {
        $md = Update-Section -Content $md -ApiId $id -Api $apis[$id]
    }
}

# Update index rows
foreach ($id in $apis.Keys) {
    $st = $apis[$id].status
    $md = [regex]::Replace($md, "(\| $([regex]::Escape($id)) \| [^|]+ \| [^|]+ \| )PENDING( \| — \|)", "`${1}$st( | $date |)", 1)
    $md = [regex]::Replace($md, "(\| $([regex]::Escape($id)) \| [^|]+ \| [^|]+ \| )(PASS|FAIL|BLOCKED|SKIP)( \| )[^|]+( \|)", "`${1}$st`${3}$date`${5}", 1)
}

$pass = ($apis.Values | Where-Object { $_.status -eq 'PASS' }).Count
$fail = ($apis.Values | Where-Object { $_.status -eq 'FAIL' }).Count
$blocked = ($apis.Values | Where-Object { $_.status -eq 'BLOCKED' }).Count
$skip = ($apis.Values | Where-Object { $_.status -eq 'SKIP' }).Count
$pending = 64 - $pass - $fail - $blocked - $skip
$ts = Get-Date -Format 'yyyy-MM-dd HH:mm'

$md = [regex]::Replace($md, '\| PASS \| \d+ \|', "| PASS | $pass |", 1)
$md = [regex]::Replace($md, '\| FAIL \| \d+ \|', "| FAIL | $fail |", 1)
$md = [regex]::Replace($md, '\| BLOCKED \| \d+ \|', "| BLOCKED | $blocked |", 1)
$md = [regex]::Replace($md, '\| SKIP \| \d+ \|', "| SKIP | $skip |", 1)
$md = [regex]::Replace($md, '\| PENDING \| \d+ \|', "| PENDING | $pending |", 1)
$md = [regex]::Replace($md, '\| 最后更新 \| [^|]+ \|', "| 最后更新 | $ts（Phase 0-7 全量执行） |", 1)

[System.IO.File]::WriteAllText($ResultsPath, $md, (New-Object System.Text.UTF8Encoding $false))
Write-Host "Updated $ResultsPath — PASS=$pass FAIL=$fail BLOCKED=$blocked SKIP=$skip PENDING=$pending"
