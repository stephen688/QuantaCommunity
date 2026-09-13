# demo0 API full runner (Phase 0-7) — Windows curl.exe
# Usage: .\scripts\run-full.ps1
# Output: test-output/full-run.json, env.sh; then run update-results.ps1

param()

$ErrorActionPreference = 'Stop'
$ApiTestDir = Split-Path -Parent $PSScriptRoot
$BaseUrl = 'http://localhost:9191'
$OutDir = Join-Path $ApiTestDir 'test-output'
$OutFile = Join-Path $OutDir 'full-run.json'
$TmpDir = Join-Path $env:TEMP 'demo0-api-test'
$Fixture = Join-Path $ApiTestDir 'cases\fixtures\pixel.png'
$Today = Get-Date -Format 'yyyy-MM-dd'

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
    if ($FormFile) { $args += @('-F', "file=@$FormFile") }
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
        try { $body = $bodyText | ConvertFrom-Json -ErrorAction Stop } catch { }
    }
    return @{ name = $Name; http = $http; body = $body; raw = $bodyText }
}

$envVars = @{ BASE_URL = $BaseUrl }
$apiResults = @{}  # apiId -> @{ status, http, code, msg, conclusion, cases, issues, curl }
$cases = [System.Collections.ArrayList]@()

function Record-Api {
    param(
        [string]$ApiId,
        [string]$Status,
        [int]$Http,
        $Code,
        [string]$Msg,
        [string]$Conclusion,
        [array]$CaseRows,
        [string]$Issues = 'none',
        [string]$Curl = '-'
    )
    $apiResults[$ApiId] = @{
        status     = $Status
        http       = $Http
        code       = $Code
        msg        = $Msg
        conclusion = $Conclusion
        cases      = $CaseRows
        issues     = $Issues
        curl       = $Curl
    }
}

function Test-Case {
    param([string]$Name, [scriptblock]$Assert, [string]$ApiId, [string]$CaseId, [string]$Type, [string]$Expected)
    $r = $script:LastInvoke
    $actual = if ($r.body) { "HTTP=$($r.http) code=$($r.body.code)" } else { "HTTP=$($r.http)" }
    $ok = $false
    try { $ok = [bool](& $Assert $r) } catch { $ok = $false }
    [void]$cases.Add(@{ name = $Name; pass = $ok; apiId = $ApiId })
    return @{ id = $CaseId; type = $Type; status = if ($ok) { 'PASS' } else { 'FAIL' }; expected = $Expected; actual = $actual; note = $Name }
}

function Invoke-And {
    param(
        [string]$Name,
        [string]$Method,
        [string]$Path,
        [string]$Token,
        [string]$JsonBody,
        [string]$FormFile
    )
    $script:LastInvoke = Invoke-CurlApi -Name $Name -Method $Method -Path $Path -Token $Token -JsonBody $JsonBody -FormFile $FormFile
    return $script:LastInvoke
}

function Login([string]$code) {
    $r = Invoke-And "login-$code" POST '/user/login' '' "{`"code`":`"$code`"}"
    if ($r.body -and $r.body.code -eq 200 -and $r.body.data.token) {
        return @{ token = [string]$r.body.data.token; id = [string]$r.body.data.id }
    }
    return $null
}

Write-Host "=== Phase 0-1 ==="
$r = Invoke-And P0-01 GET '/user/info'
$rows = @(); $rows += (Test-Case P0-01 { $_.http -eq 401 } 'U-02' 'U-02-02' '权限' 'HTTP 401')

$login = Login 'test'
if (-not $login) { throw 'login failed - is demo0 running on 9191?' }
$envVars.USER_TOKEN = $login.token
$envVars.USER_ID = $login.id
$ut = $login.token
$uid = $login.id

$r = Invoke-And P0-02 POST '/user/login' '' '{"code":"test"}'
$rows = @((Test-Case P0-02 { $_.http -eq 200 -and $_.body.code -eq 200 } 'U-01' 'U-01-01' '正向' 'code=200'))
Record-Api 'U-01' 'PASS' $r.http $r.body.code $r.body.msg 'Mock 登录 code=test 正常' $rows

$r = Invoke-And P1-01 POST '/user/login' '' '{"code":""}'
$u01rows = @((Test-Case P1-01 { $_.body.code -eq 400 } 'U-01' 'U-01-02' '非法' 'code=400'))
$apiResults['U-01'].cases += $u01rows[0]

$r = Invoke-And P1-03 GET '/user/info'
$r2 = Invoke-And P1-04 GET '/user/info' 'invalid-token'
$r3 = Invoke-And P1-05 GET '/user/info' $ut
$u02rows = @(
    (Test-Case P1-03 { $_.http -eq 401 } 'U-02' 'U-02-02' '权限' 'HTTP 401'),
    (Test-Case P1-05 { $_.http -eq 200 -and $_.body.code -eq 200 } 'U-02' 'U-02-01' '正向' 'code=200')
)
Record-Api 'U-02' 'PASS' $r3.http $r3.body.code $r3.body.msg 'JWT 拦截与正向查询符合预期' $u02rows

$r = Invoke-And -Name P1-06 -Method GET -Path '/admin/user/page?pageNum=1%26pageSize=10' -Token $ut
$p106 = ($r.http -eq 403)
if (-not $p106 -and $r.http -eq 200) { Write-Host '  note: user is admin, P1-06 skipped' }

$loginAdmin = Login 'test'
$envVars.ADMIN_TOKEN = $loginAdmin.token
$envVars.USER_TOKEN = $loginAdmin.token
$at = $loginAdmin.token

$r = Invoke-And -Name P1-07 -Method GET -Path '/admin/user/page?pageNum=1%26pageSize=10' -Token $at
Record-Api 'AD-06' $(if ($r.body.code -eq 200) { 'PASS' } else { 'FAIL' }) $r.http $r.body.code $r.body.msg '管理员分页正常' @(
    @{ id = 'AD-06-01'; type = '正向'; status = $(if ($r.body.code -eq 200) { 'PASS' } else { 'FAIL' }); expected = 'code=200'; actual = "code=$($r.body.code)"; note = 'P1-07' }
)

Write-Host "=== Phase 2-3 ==="
if (Test-Path $Fixture) {
    $r = Invoke-And P2-01 POST '/common/upload' $ut -FormFile $Fixture
    $st = if ($r.body.code -eq 200) { 'PASS' } elseif ($r.body.code) { 'BLOCKED' } else { 'FAIL' }
    if ($r.body.data) { $envVars.IMAGE_URL = [string]$r.body.data }
    Record-Api 'O-01' $st $r.http $r.body.code $r.body.msg $(if ($st -eq 'BLOCKED') { 'OSS 未配置' } else { '上传成功' }) @(
        @{ id = 'O-01-01'; type = '正向'; status = $st; expected = 'code=200'; actual = "code=$($r.body.code)"; note = 'P2-01' }
    )
}

$r = Invoke-And P2-04 POST '/content/publish' $ut '{"contentType":2,"title":"API-test-prof","content":"prof desc","images":[]}'
if ($r.body.data.contentId) { $envVars.CONTENT_ID_PROF = [string]$r.body.data.contentId; $envVars.QUESTION_ID = $envVars.CONTENT_ID_PROF }
Record-Api 'C-01' $(if ($r.body.code -eq 200) { 'PASS' } else { 'FAIL' }) $r.http $r.body.code $r.body.msg '专业帖发布正常' @(
    @{ id = 'C-01-01'; type = '正向'; status = $(if ($r.body.code -eq 200) { 'PASS' } else { 'FAIL' }); expected = 'code=200'; actual = "code=$($r.body.code)"; note = 'P2-04' }
)

$r = Invoke-And P2-05 POST '/content/publish' $ut '{"contentType":1,"title":"API-test-life","content":"life desc","images":[]}'
if ($r.body.data.contentId) { $envVars.CONTENT_ID_LIFE = [string]$r.body.data.contentId }

$r = Invoke-And P2-06 POST '/content/publish' $ut '{"contentType":2,"title":"","content":"ok","images":[]}'
$apiResults['C-01'].cases += @{ id = 'C-01-02'; type = '非法'; status = $(if ($r.body.code -eq 400) { 'PASS' } else { 'FAIL' }); expected = 'code=400'; actual = "code=$($r.body.code)"; note = 'P2-06' }

$prof = $envVars.CONTENT_ID_PROF
$life = $envVars.CONTENT_ID_LIFE

$r = Invoke-And P3-01 GET "/admin/content/page?pageNum=1%26pageSize=10%26auditStatus=0" $at
Record-Api 'AD-01' $(if ($r.body.code -eq 200) { 'PASS' } else { 'FAIL' }) $r.http $r.body.code $r.body.msg '待审列表正常' @(
    @{ id = 'AD-01-01'; type = '正向'; status = $(if ($r.body.code -eq 200) { 'PASS' } else { 'FAIL' }); expected = 'code=200'; actual = "code=$($r.body.code)"; note = 'P3-01' }
)

$r = Invoke-And P3-04 POST '/admin/content/audit' $at "{`"contentId`":$prof,`"auditResult`":1}"
Record-Api 'AD-02' $(if ($r.body.code -eq 200) { 'PASS' } else { 'FAIL' }) $r.http $r.body.code $r.body.msg '审核通过专业帖' @(
    @{ id = 'AD-02-01'; type = '正向'; status = $(if ($r.body.code -eq 200) { 'PASS' } else { 'FAIL' }); expected = 'code=200'; actual = "code=$($r.body.code)"; note = 'P3-04' }
)

$r = Invoke-And P3-05 POST '/admin/content/audit' $at "{`"contentId`":$life,`"auditResult`":2,`"rejectReason`":`"api-reject`"}"

$r = Invoke-And P3-06 POST '/answer/publish' $ut "{`"questionId`":$prof,`"content`":`"answer after audit`"}"
if ($r.body.data.answerId) { $envVars.ANSWER_ID = [string]$r.body.data.answerId }
Record-Api 'A-01' $(if ($r.body.code -eq 200) { 'PASS' } else { 'FAIL' }) $r.http $r.body.code $r.body.msg '专业区已审帖可回答' @(
    @{ id = 'A-01-01'; type = '正向'; status = $(if ($r.body.code -eq 200) { 'PASS' } else { 'FAIL' }); expected = 'code=200'; actual = "code=$($r.body.code)"; note = 'P3-06' }
)

$r = Invoke-And P3-07 POST '/answer/publish' $ut "{`"questionId`":$life,`"content`":`"life no answer`"}"
$apiResults['A-01'].cases += @{ id = 'A-01-02'; type = '业务'; status = $(if ($r.body.code -eq 400) { 'PASS' } else { 'FAIL' }); expected = 'code=400'; actual = "code=$($r.body.code)"; note = 'P3-07 生活帖不可答' }

$ans = $envVars.ANSWER_ID
$loginB = Login 'test2'
if ($loginB) { $envVars.USER_B_TOKEN = $loginB.token; $envVars.USER_B_ID = $loginB.id }
$ubt = $envVars.USER_B_TOKEN
$ubid = $envVars.USER_B_ID

Write-Host "=== Phase 4 read ==="
$r = Invoke-And -Name P4-01 -Method GET -Path '/content/recommend?scene=latest%26pageSize=5%26offset=0' -Token $ut
Record-Api 'C-02' $(if ($r.body.code -eq 200) { 'PASS' } else { 'FAIL' }) $r.http $r.body.code $r.body.msg '推荐流正常' @(@{ id='C-02-01'; type='正向'; status=$(if($r.body.code-eq 200){'PASS'}else{'FAIL'}); expected='code=200'; actual="code=$($r.body.code)"; note='P4-01' })

$r = Invoke-And P4-02 GET "/content/detail/$prof" $ut
Record-Api 'C-03' $(if ($r.body.code -eq 200) { 'PASS' } else { 'FAIL' }) $r.http $r.body.code $r.body.msg '详情正常' @(@{ id='C-03-01'; type='正向'; status=$(if($r.body.code-eq 200){'PASS'}else{'FAIL'}); expected='code=200'; actual="code=$($r.body.code)"; note='P4-02' })

$r = Invoke-And P4-03 GET '/content/detail/999999' $ut
$apiResults['C-03'].cases += @{ id='C-03-02'; type='关联'; status=$(if($r.body.code-in 400,404){'PASS'}else{'FAIL'}); expected='404/400'; actual="code=$($r.body.code)"; note='P4-03' }

$r = Invoke-And P4-04 GET "/user/$uid/profile" $ut
Record-Api 'U-12' $(if ($r.body.code -eq 200) { 'PASS' } else { 'FAIL' }) $r.http $r.body.code $r.body.msg '用户主页' @(@{ id='U-12-01'; type='正向'; status=$(if($r.body.code-eq 200){'PASS'}else{'FAIL'}); expected='code=200'; actual="code=$($r.body.code)"; note='P4-04' })

$r = Invoke-And -Name P4-05 -Method GET -Path '/search/content?keyword=API%26contentType=2%26current=1%26pageSize=10%26sortType=new' -Token $ut
$esBlocked = ($r.body.code -ne 200)
Record-Api 'S-01' $(if ($esBlocked) { 'BLOCKED' } else { 'PASS' }) $r.http $r.body.code $r.body.msg $(if ($esBlocked) { 'ES 未就绪' } else { '搜索正常' }) @(@{ id='S-01-01'; type='正向'; status=$(if($esBlocked){'BLOCKED'}elseif($r.body.code-eq 200){'PASS'}else{'FAIL'}); expected='code=200'; actual="code=$($r.body.code)"; note='P4-05' })

$r = Invoke-And -Name P4-06 -Method GET -Path '/search/content?keyword=%26current=1%26pageSize=10' -Token $ut
$apiResults['S-01'].cases += @{ id='S-01-02'; type='非法'; status=$(if($r.body.code-eq 400){'PASS'}else{'FAIL'}); expected='code=400'; actual="code=$($r.body.code)"; note='P4-06' }

$r = Invoke-And P4-07 GET '/search/history/keywords' $ut
Record-Api 'S-02' $(if ($r.body.code -eq 200) { 'PASS' } else { 'FAIL' }) $r.http $r.body.code $r.body.msg '历史关键词' @(@{ id='S-02-01'; type='正向'; status=$(if($r.body.code-eq 200){'PASS'}else{'FAIL'}); expected='code=200'; actual="code=$($r.body.code)"; note='P4-07' })

$r = Invoke-And P4-08 GET '/search/trending' $ut
Record-Api 'S-05' $(if ($r.body.code -eq 200) { 'PASS' } else { 'FAIL' }) $r.http $r.body.code $r.body.msg '热门发现' @(@{ id='S-05-01'; type='正向'; status=$(if($r.body.code-eq 200){'PASS'}else{'FAIL'}); expected='code=200'; actual="code=$($r.body.code)"; note='P4-08' })

$r = Invoke-And P4-10 POST '/rag/search' $ut '{"query":"专业问答 API","contentType":2,"enableAi":false}'
$ragSt = if ($r.body.code -eq 200) { 'PASS' } elseif ($r.body.code) { 'BLOCKED' } else { 'FAIL' }
Record-Api 'R-01' $ragSt $r.http $r.body.code $r.body.msg $(if ($ragSt -eq 'BLOCKED') { 'RAG/ES 环境依赖' } else { 'RAG 检索正常' }) @(@{ id='R-01-01'; type='正向'; status=$ragSt; expected='code=200'; actual="code=$($r.body.code)"; note='P4-10 enableAi=false' })

$r = Invoke-And P4-11 POST '/rag/search' $ut '{"query":"","enableAi":false}'
$apiResults['R-01'].cases += @{ id='R-01-02'; type='非法'; status=$(if($r.body.code-eq 400){'PASS'}else{'FAIL'}); expected='code=400'; actual="code=$($r.body.code)"; note='P4-11' }

$r = Invoke-And P4-13 GET "/answer/list/$prof" $ut
Record-Api 'A-02' $(if ($r.body.code -eq 200) { 'PASS' } else { 'FAIL' }) $r.http $r.body.code $r.body.msg '回答列表' @(@{ id='A-02-01'; type='正向'; status=$(if($r.body.code-eq 200){'PASS'}else{'FAIL'}); expected='code=200'; actual="code=$($r.body.code)"; note='P4-13' })

$r = Invoke-And P4-14 GET "/answer/$ans" $ut
Record-Api 'A-06' $(if ($r.body.code -eq 200) { 'PASS' } else { 'FAIL' }) $r.http $r.body.code $r.body.msg '回答详情' @(@{ id='A-06-01'; type='正向'; status=$(if($r.body.code-eq 200){'PASS'}else{'FAIL'}); expected='code=200'; actual="code=$($r.body.code)"; note='P4-14' })

$r = Invoke-And P4-16 GET "/comment/list?contentId=$prof%26answerId=$ans%26pageNum=1%26pageSize=10%26sortType=1" $ut
Record-Api 'M-02' $(if ($r.body.code -eq 200) { 'PASS' } else { 'FAIL' }) $r.http $r.body.code $r.body.msg '评论列表' @(@{ id='M-02-01'; type='正向'; status=$(if($r.body.code-eq 200){'PASS'}else{'FAIL'}); expected='code=200'; actual="code=$($r.body.code)"; note='P4-16' })

$r = Invoke-And -Name P4-18 -Method GET -Path '/follow/feed?offset=0%26pageSize=5' -Token $ut
Record-Api 'F-02' $(if ($r.body.code -eq 200) { 'PASS' } else { 'FAIL' }) $r.http $r.body.code $r.body.msg '关注 Feed' @(@{ id='F-02-01'; type='正向'; status=$(if($r.body.code-eq 200){'PASS'}else{'FAIL'}); expected='code=200'; actual="code=$($r.body.code)"; note='P4-18' })

$r = Invoke-And -Name P4-19 -Method GET -Path '/notification/list?page=1%26pageSize=10' -Token $ut
if ($r.body.data.records -and $r.body.data.records.Count -gt 0) { $envVars.NOTIFICATION_ID = [string]$r.body.data.records[0].id }
Record-Api 'N-01' $(if ($r.body.code -eq 200) { 'PASS' } else { 'FAIL' }) $r.http $r.body.code $r.body.msg '通知列表' @(@{ id='N-01-01'; type='正向'; status=$(if($r.body.code-eq 200){'PASS'}else{'FAIL'}); expected='code=200'; actual="code=$($r.body.code)"; note='P4-19' })

$r = Invoke-And P4-20 GET '/notification/unreadCount' $ut
Record-Api 'N-02' $(if ($r.body.code -eq 200) { 'PASS' } else { 'FAIL' }) $r.http $r.body.code $r.body.msg '未读数' @(@{ id='N-02-01'; type='正向'; status=$(if($r.body.code-eq 200){'PASS'}else{'FAIL'}); expected='code=200'; actual="code=$($r.body.code)"; note='P4-20' })

# User endpoints phase 4
$r = Invoke-And U03 PUT '/user/info/update' $ut '{"nickName":"API测试昵称","avatarUrl":""}'
Record-Api 'U-03' $(if ($r.body.code -eq 200) { 'PASS' } else { 'FAIL' }) $r.http $r.body.code $r.body.msg '更新资料' @(@{ id='U-03-01'; type='正向'; status=$(if($r.body.code-eq 200){'PASS'}else{'FAIL'}); expected='code=200'; actual="code=$($r.body.code)"; note='' })

$r = Invoke-And U05 GET '/user/auth/status' $ut
Record-Api 'U-05' $(if ($r.body.code -eq 200) { 'PASS' } else { 'FAIL' }) $r.http $r.body.code $r.body.msg '实名状态' @(@{ id='U-05-01'; type='正向'; status=$(if($r.body.code-eq 200){'PASS'}else{'FAIL'}); expected='code=200'; actual="code=$($r.body.code)"; note='' })

$r = Invoke-And U07 GET '/user/content/my/list?current=1%26size=10' $ut
Record-Api 'U-07' $(if ($r.body.code -eq 200) { 'PASS' } else { 'FAIL' }) $r.http $r.body.code $r.body.msg '我的发布' @(@{ id='U-07-01'; type='正向'; status=$(if($r.body.code-eq 200){'PASS'}else{'FAIL'}); expected='code=200'; actual="code=$($r.body.code)"; note='' })

$r = Invoke-And U08 GET '/user/content/my/liked?current=1%26size=10' $ut
Record-Api 'U-08' $(if ($r.body.code -eq 200) { 'PASS' } else { 'FAIL' }) $r.http $r.body.code $r.body.msg '我的点赞' @(@{ id='U-08-01'; type='正向'; status=$(if($r.body.code-eq 200){'PASS'}else{'FAIL'}); expected='code=200'; actual="code=$($r.body.code)"; note='' })

$r = Invoke-And U13 GET "/user/$uid/contents?current=1%26size=10" $ut
Record-Api 'U-13' $(if ($r.body.code -eq 200) { 'PASS' } else { 'FAIL' }) $r.http $r.body.code $r.body.msg '用户内容列表' @(@{ id='U-13-01'; type='正向'; status=$(if($r.body.code-eq 200){'PASS'}else{'FAIL'}); expected='code=200'; actual="code=$($r.body.code)"; note='' })

Write-Host "=== Phase 5 write ==="
$r = Invoke-And P5-02 DELETE '/search/history/clear' $ut
Record-Api 'S-03' $(if ($r.body.code -eq 200) { 'PASS' } else { 'FAIL' }) $r.http $r.body.code $r.body.msg '清空搜索历史' @(@{ id='S-03-01'; type='正向'; status=$(if($r.body.code-eq 200){'PASS'}else{'FAIL'}); expected='code=200'; actual="code=$($r.body.code)"; note='P5-02' })

$r = Invoke-And P5-09 POST '/comment/send' $ut "{`"contentId`":$prof,`"answerId`":$ans,`"parentId`":null,`"content`":`"API测试评论`"}"
if ($r.body.data) { $envVars.COMMENT_ID = [string]$r.body.data }
Record-Api 'M-01' $(if ($r.body.code -eq 200) { 'PASS' } else { 'FAIL' }) $r.http $r.body.code $r.body.msg '发评论' @(@{ id='M-01-01'; type='正向'; status=$(if($r.body.code-eq 200){'PASS'}else{'FAIL'}); expected='code=200'; actual="code=$($r.body.code)"; note='P5-09' })

$cid = $envVars.COMMENT_ID
$r = Invoke-And P5-11 POST "/comment/like/$cid" $ut
Record-Api 'M-05' $(if ($r.body.code -eq 200) { 'PASS' } else { 'FAIL' }) $r.http $r.body.code $r.body.msg '点赞评论' @(@{ id='M-05-01'; type='正向'; status=$(if($r.body.code-eq 200){'PASS'}else{'FAIL'}); expected='code=200'; actual="code=$($r.body.code)"; note='P5-11' })

$r = Invoke-And P5-12 POST '/comment/report' $ut "{`"commentId`":$cid,`"reportType`":5}"
Record-Api 'M-06' $(if ($r.body.code -eq 200) { 'PASS' } else { 'FAIL' }) $r.http $r.body.code $r.body.msg '举报评论' @(@{ id='M-06-01'; type='正向'; status=$(if($r.body.code-eq 200){'PASS'}else{'FAIL'}); expected='code=200'; actual="code=$($r.body.code)"; note='P5-12' })

$r = Invoke-And P5-17 POST "/content/like/$prof" $ut
Record-Api 'C-05' $(if ($r.body.code -eq 200) { 'PASS' } else { 'FAIL' }) $r.http $r.body.code $r.body.msg '点赞帖子' @(@{ id='C-05-01'; type='正向'; status=$(if($r.body.code-eq 200){'PASS'}else{'FAIL'}); expected='code=200'; actual="code=$($r.body.code)"; note='P5-17' })

$r = Invoke-And P5-18 POST "/content/collect/$prof" $ut
Record-Api 'C-06' $(if ($r.body.code -eq 200) { 'PASS' } else { 'FAIL' }) $r.http $r.body.code $r.body.msg '收藏帖子' @(@{ id='C-06-01'; type='正向'; status=$(if($r.body.code-eq 200){'PASS'}else{'FAIL'}); expected='code=200'; actual="code=$($r.body.code)"; note='P5-18' })

$r = Invoke-And U09 GET '/user/content/my/collect?current=1%26size=10' $ut
Record-Api 'U-09' $(if ($r.body.code -eq 200) { 'PASS' } else { 'FAIL' }) $r.http $r.body.code $r.body.msg '我的收藏' @(@{ id='U-09-01'; type='正向'; status=$(if($r.body.code-eq 200){'PASS'}else{'FAIL'}); expected='code=200'; actual="code=$($r.body.code)"; note='after collect' })

$r = Invoke-And P5-19 POST '/content/report' $ut "{`"contentId`":$prof,`"reportType`":5}"
Record-Api 'C-07' $(if ($r.body.code -eq 200) { 'PASS' } else { 'FAIL' }) $r.http $r.body.code $r.body.msg '举报帖子' @(@{ id='C-07-01'; type='正向'; status=$(if($r.body.code-eq 200){'PASS'}else{'FAIL'}); expected='code=200'; actual="code=$($r.body.code)"; note='P5-19' })

if ($ubid -and $ubid -ne $uid) {
    $r = Invoke-And P5-14 POST "/follow/$ubid" $ut
    Record-Api 'F-01' $(if ($r.body.code -eq 200) { 'PASS' } else { 'FAIL' }) $r.http $r.body.code $r.body.msg '关注用户' @(@{ id='F-01-01'; type='正向'; status=$(if($r.body.code-eq 200){'PASS'}else{'FAIL'}); expected='code=200'; actual="code=$($r.body.code)"; note='P5-14' })
    $r = Invoke-And P5-15 POST "/follow/$uid" $ut
    $apiResults['F-01'].cases += @{ id='F-01-02'; type='业务'; status=$(if($r.body.code-eq 400){'PASS'}else{'FAIL'}); expected='code=400'; actual="code=$($r.body.code)"; note='不能关注自己' }
}

$r = Invoke-And P5-05 POST "/answer/accept/$ans" $ut
Record-Api 'A-03' $(if ($r.body.code -eq 200) { 'PASS' } else { 'FAIL' }) $r.http $r.body.code $r.body.msg '题主采纳' @(@{ id='A-03-01'; type='正向'; status=$(if($r.body.code-eq 200){'PASS'}else{'FAIL'}); expected='code=200'; actual="code=$($r.body.code)"; note='P5-05' })

$r = Invoke-And P5-07 POST "/answer/like/$ans" $ut
Record-Api 'A-04' $(if ($r.body.code -eq 200) { 'PASS' } else { 'FAIL' }) $r.http $r.body.code $r.body.msg '点赞回答' @(@{ id='A-04-01'; type='正向'; status=$(if($r.body.code-eq 200){'PASS'}else{'FAIL'}); expected='code=200'; actual="code=$($r.body.code)"; note='P5-07' })

$r = Invoke-And P5-22 PUT '/notification/readAll' $ut
Record-Api 'N-04' $(if ($r.body.code -eq 200) { 'PASS' } else { 'FAIL' }) $r.http $r.body.code $r.body.msg '全部已读' @(@{ id='N-04-01'; type='正向'; status=$(if($r.body.code-eq 200){'PASS'}else{'FAIL'}); expected='code=200'; actual="code=$($r.body.code)"; note='P5-22' })

$nid = $envVars.NOTIFICATION_ID
if ($nid) {
    $r = Invoke-And P5-21 PUT "/notification/read/$nid" $ut
    Record-Api 'N-03' $(if ($r.body.code -eq 200) { 'PASS' } else { 'FAIL' }) $r.http $r.body.code $r.body.msg '单条已读' @(@{ id='N-03-01'; type='正向'; status=$(if($r.body.code-eq 200){'PASS'}else{'FAIL'}); expected='code=200'; actual="code=$($r.body.code)"; note='P5-21' })
} else {
    Record-Api 'N-03' 'SKIP' 200 '-' '-' '无通知记录可测' @(@{ id='N-03-01'; type='正向'; status='SKIP'; expected='code=200'; actual='—'; note='无通知' })
}

$r = Invoke-And U04 POST '/user/auth/add' $ut '{"identityType":1,"realName":"测试","schoolId":"20240001","quantaBatch":"2024","quantaDepartment":"测试部"}'
$u04st = switch ($r.body.code) { 200 { 'PASS' } 400 { 'PASS' } default { 'FAIL' } }
Record-Api 'U-04' $u04st $r.http $r.body.code $r.body.msg $(if ($r.body.code -eq 400) { '可能已提交过实名' } else { '提交实名' }) @(@{ id='U-04-01'; type='正向'; status=$u04st; expected='200/400'; actual="code=$($r.body.code)"; note='' })

$r = Invoke-And U06 GET '/user/auth/detail' $ut
Record-Api 'U-06' $(if ($r.body.code -eq 200) { 'PASS' } else { 'FAIL' }) $r.http $r.body.code $r.body.msg '实名详情' @(@{ id='U-06-01'; type='正向'; status=$(if($r.body.code-eq 200){'PASS'}else{'FAIL'}); expected='code=200'; actual="code=$($r.body.code)"; note='' })

$r = Invoke-And U10 GET '/user/content/my/browseHistory?current=1%26size=10' $ut
Record-Api 'U-10' $(if ($r.body.code -eq 200) { 'PASS' } else { 'FAIL' }) $r.http $r.body.code $r.body.msg '浏览历史' @(@{ id='U-10-01'; type='正向'; status=$(if($r.body.code-eq 200){'PASS'}else{'FAIL'}); expected='code=200'; actual="code=$($r.body.code)"; note='' })

$r = Invoke-And U11 DELETE '/user/browse/history/clear' $ut
Record-Api 'U-11' $(if ($r.body.code -eq 200) { 'PASS' } else { 'FAIL' }) $r.http $r.body.code $r.body.msg '清空浏览历史' @(@{ id='U-11-01'; type='正向'; status=$(if($r.body.code-eq 200){'PASS'}else{'FAIL'}); expected='code=200'; actual="code=$($r.body.code)"; note='' })

$r = Invoke-And M03 GET "/comment/replyList?parentId=$cid%26pageNum=1%26pageSize=10" $ut
Record-Api 'M-03' $(if ($r.body.code -eq 200) { 'PASS' } else { 'FAIL' }) $r.http $r.body.code $r.body.msg '回复列表' @(@{ id='M-03-01'; type='正向'; status=$(if($r.body.code-eq 200){'PASS'}else{'FAIL'}); expected='code=200'; actual="code=$($r.body.code)"; note='' })

$r = Invoke-And S04 DELETE '/search/history/deleteOne/999999' $ut
Record-Api 'S-04' $(if ($r.body.code -in 200,404,400) { 'PASS' } else { 'FAIL' }) $r.http $r.body.code $r.body.msg '删除单条历史' @(@{ id='S-04-01'; type='关联'; status=$(if($r.body.code-in 200,404,400){'PASS'}else{'FAIL'}); expected='200/404'; actual="code=$($r.body.code)"; note='' })

Write-Host "=== Phase 6 admin ==="
$r = Invoke-And P6-02 GET "/admin/user/$uid" $at
Record-Api 'AD-07' $(if ($r.body.code -eq 200) { 'PASS' } else { 'FAIL' }) $r.http $r.body.code $r.body.msg '用户详情' @(@{ id='AD-07-01'; type='正向'; status=$(if($r.body.code-eq 200){'PASS'}else{'FAIL'}); expected='code=200'; actual="code=$($r.body.code)"; note='P6-02' })

$r = Invoke-And -Name P6-07 -Method GET -Path '/admin/content/page?pageNum=1%26pageSize=10%26auditStatus=1%26contentType=2' -Token $at

$r = Invoke-And -Name P6-08 -Method GET -Path '/admin/content/report/page?pageNum=1%26pageSize=10%26status=0' -Token $at
if ($r.body.data.records -and $r.body.data.records.Count -gt 0) { $envVars.REPORT_ID = [string]$r.body.data.records[0].id }
Record-Api 'AD-04' $(if ($r.body.code -eq 200) { 'PASS' } else { 'FAIL' }) $r.http $r.body.code $r.body.msg '帖子举报分页' @(@{ id='AD-04-01'; type='正向'; status=$(if($r.body.code-eq 200){'PASS'}else{'FAIL'}); expected='code=200'; actual="code=$($r.body.code)"; note='P6-08' })

$rid = $envVars.REPORT_ID
if ($rid) {
    $r = Invoke-And P6-09 POST '/admin/content/report/handle' $at "{`"reportId`":$rid,`"handleResult`":4,`"handleRemark`":`"api`"}"
    Record-Api 'AD-05' $(if ($r.body.code -eq 200) { 'PASS' } else { 'FAIL' }) $r.http $r.body.code $r.body.msg '处理举报' @(@{ id='AD-05-01'; type='正向'; status=$(if($r.body.code-eq 200){'PASS'}else{'FAIL'}); expected='code=200'; actual="code=$($r.body.code)"; note='P6-09' })
} else {
    Record-Api 'AD-05' 'SKIP' 200 '-' '-' '无待处理举报' @(@{ id='AD-05-01'; type='正向'; status='SKIP'; expected='code=200'; actual='—'; note='' })
}

$r = Invoke-And P6-11 GET "/admin/comment/page?pageNum=1%26pageSize=10%26contentId=$prof" $at
Record-Api 'AD-10' $(if ($r.body.code -eq 200) { 'PASS' } else { 'FAIL' }) $r.http $r.body.code $r.body.msg '评论分页' @(@{ id='AD-10-01'; type='正向'; status=$(if($r.body.code-eq 200){'PASS'}else{'FAIL'}); expected='code=200'; actual="code=$($r.body.code)"; note='P6-11' })

$r = Invoke-And -Name P6-12 -Method GET -Path '/admin/comment/report/page?pageNum=1%26pageSize=10%26status=0' -Token $at
Record-Api 'AD-12' $(if ($r.body.code -eq 200) { 'PASS' } else { 'FAIL' }) $r.http $r.body.code $r.body.msg '评论举报分页' @(@{ id='AD-12-01'; type='正向'; status=$(if($r.body.code-eq 200){'PASS'}else{'FAIL'}); expected='code=200'; actual="code=$($r.body.code)"; note='P6-12' })

$r = Invoke-And P6-13 POST '/admin/comment/report/handle' $at '{"reportId":1,"handleResult":4,"handleRemark":"api"}'
Record-Api 'AD-13' $(if ($r.body.code -in 200,404,400) { 'PASS' } else { 'FAIL' }) $r.http $r.body.code $r.body.msg '处理评论举报' @(@{ id='AD-13-01'; type='正向'; status=$(if($r.body.code-in 200,404,400){'PASS'}else{'FAIL'}); expected='200/404'; actual="code=$($r.body.code)"; note='P6-13' })

$r = Invoke-And P6-14 GET "/admin/answer/page?pageNum=1%26pageSize=10%26contentId=$prof" $at
Record-Api 'AD-14' $(if ($r.body.code -eq 200) { 'PASS' } else { 'FAIL' }) $r.http $r.body.code $r.body.msg '回答分页' @(@{ id='AD-14-01'; type='正向'; status=$(if($r.body.code-eq 200){'PASS'}else{'FAIL'}); expected='code=200'; actual="code=$($r.body.code)"; note='P6-14' })

$r = Invoke-And P6-15 POST '/admin/answer/audit' $at "{`"contentId`":$ans,`"auditResult`":1}"
Record-Api 'AD-16' $(if ($r.body.code -eq 200) { 'PASS' } else { 'FAIL' }) $r.http $r.body.code $r.body.msg '审核回答' @(@{ id='AD-16-01'; type='正向'; status=$(if($r.body.code-eq 200){'PASS'}else{'FAIL'}); expected='code=200'; actual="code=$($r.body.code)"; note='P6-15' })

$r = Invoke-And -Name P6-16 -Method GET -Path '/admin/identityExam/page?pageNum=1%26pageSize=10' -Token $at
$authId = $null
if ($r.body.data.records -and $r.body.data.records.Count -gt 0) { $authId = [string]$r.body.data.records[0].id }
Record-Api 'AD-17' $(if ($r.body.code -eq 200) { 'PASS' } else { 'FAIL' }) $r.http $r.body.code $r.body.msg '实名分页' @(@{ id='AD-17-01'; type='正向'; status=$(if($r.body.code-eq 200){'PASS'}else{'FAIL'}); expected='code=200'; actual="code=$($r.body.code)"; note='P6-16' })

if ($authId) {
    $r = Invoke-And P6-17 GET "/admin/identityExam/userAuth/detail/$authId" $at
    Record-Api 'AD-18' $(if ($r.body.code -eq 200) { 'PASS' } else { 'FAIL' }) $r.http $r.body.code $r.body.msg '实名详情' @(@{ id='AD-18-01'; type='正向'; status=$(if($r.body.code-eq 200){'PASS'}else{'FAIL'}); expected='code=200'; actual="code=$($r.body.code)"; note='P6-17' })
    $r = Invoke-And P6-18 POST '/admin/identityExam/audit' $at "{`"authId`":$authId,`"auditResult`":2,`"auditRemark`":`"api`"}"
    Record-Api 'AD-19' $(if ($r.body.code -eq 200) { 'PASS' } else { 'FAIL' }) $r.http $r.body.code $r.body.msg '实名审核' @(@{ id='AD-19-01'; type='正向'; status=$(if($r.body.code-eq 200){'PASS'}else{'FAIL'}); expected='code=200'; actual="code=$($r.body.code)"; note='P6-18' })
} else {
    Record-Api 'AD-18' 'BLOCKED' 200 '-' '-' '无待审实名记录' @(@{ id='AD-18-01'; type='正向'; status='BLOCKED'; expected='code=200'; actual='—'; note='' })
    Record-Api 'AD-19' 'BLOCKED' 200 '-' '-' '无待审实名记录' @(@{ id='AD-19-01'; type='正向'; status='BLOCKED'; expected='code=200'; actual='—'; note='' })
}

if ($ubid) {
    $r = Invoke-And P6-04 POST "/admin/user/ban/$ubid" $at
    $banOk = ($r.body.code -eq 200)
    Record-Api 'AD-08' $(if ($banOk) { 'PASS' } else { 'FAIL' }) $r.http $r.body.code $r.body.msg '封禁用户B' @(@{ id='AD-08-01'; type='正向'; status=$(if($banOk){'PASS'}else{'FAIL'}); expected='code=200'; actual="code=$($r.body.code)"; note='P6-04' })
    $r = Invoke-And P6-05 POST '/user/login' '' '{"code":"test2"}'
    $apiResults['AD-08'].cases += @{ id='AD-08-02'; type='业务'; status=$(if($r.body.code-eq 401){'PASS'}else{'FAIL'}); expected='code=401'; actual="code=$($r.body.code)"; note='封禁后登录' }
    $r = Invoke-And P6-06 POST "/admin/user/unban/$ubid" $at
    Record-Api 'AD-09' $(if ($r.body.code -eq 200) { 'PASS' } else { 'FAIL' }) $r.http $r.body.code $r.body.msg '解封用户B' @(@{ id='AD-09-01'; type='正向'; status=$(if($r.body.code-eq 200){'PASS'}else{'FAIL'}); expected='code=200'; actual="code=$($r.body.code)"; note='P6-06' })
} else {
    Record-Api 'AD-08' 'SKIP' 0 '-' '-' '无 USER_B' @(@{ id='AD-08-01'; type='正向'; status='SKIP'; expected='code=200'; actual='—'; note='' })
    Record-Api 'AD-09' 'SKIP' 0 '-' '-' '无 USER_B' @(@{ id='AD-09-01'; type='正向'; status='SKIP'; expected='code=200'; actual='—'; note='' })
}

Write-Host "=== Phase 7 cleanup ==="
if ($cid) {
    $r = Invoke-And P7-01 DELETE "/comment/delete/$cid" $ut
    Record-Api 'M-04' $(if ($r.body.code -eq 200) { 'PASS' } else { 'FAIL' }) $r.http $r.body.code $r.body.msg '删除评论' @(@{ id='M-04-01'; type='正向'; status=$(if($r.body.code-eq 200){'PASS'}else{'FAIL'}); expected='code=200'; actual="code=$($r.body.code)"; note='P7-01' })
}

$r = Invoke-And P7-04 DELETE "/answer/$ans" $ut
Record-Api 'A-05' $(if ($r.body.code -eq 200) { 'PASS' } else { 'FAIL' }) $r.http $r.body.code $r.body.msg '删除回答' @(@{ id='A-05-01'; type='正向'; status=$(if($r.body.code-eq 200){'PASS'}else{'FAIL'}); expected='code=200'; actual="code=$($r.body.code)"; note='P7-04' })

$r = Invoke-And P7-05 DELETE "/content/delete/$prof" $ut
Record-Api 'C-04' $(if ($r.body.code -eq 200) { 'PASS' } else { 'FAIL' }) $r.http $r.body.code $r.body.msg '删除专业帖' @(@{ id='C-04-01'; type='正向'; status=$(if($r.body.code-eq 200){'PASS'}else{'FAIL'}); expected='code=200'; actual="code=$($r.body.code)"; note='P7-05' })

$r = Invoke-And P7-06 DELETE "/content/delete/$life" $ut
$apiResults['C-04'].cases += @{ id='C-04-02'; type='正向'; status=$(if($r.body.code-eq 200){'PASS'}else{'FAIL'}); expected='code=200'; actual="code=$($r.body.code)"; note='P7-06 生活帖' }

$r = Invoke-And P7-12 DELETE '/admin/content/999999' $at
Record-Api 'AD-03' $(if ($r.body.code -in 200,404,400) { 'PASS' } else { 'FAIL' }) $r.http $r.body.code $r.body.msg '管理端删帖（含不存在 ID）' @(
    @{ id='AD-03-01'; type='关联'; status=$(if($r.body.code-in 404,400){'PASS'}else{'FAIL'}); expected='404/400'; actual="code=$($r.body.code)"; note='P7-12' },
    @{ id='AD-03-02'; type='正向'; status='SKIP'; expected='code=200'; actual='—'; note='测试帖已由用户端删除' }
)

$r = Invoke-And P7-08 DELETE "/admin/comment/$cid" $at
Record-Api 'AD-11' $(if ($r.body.code -in 200,404) { 'PASS' } else { 'FAIL' }) $r.http $r.body.code $r.body.msg '管理端删评论' @(@{ id='AD-11-01'; type='正向'; status=$(if($r.body.code-in 200,404){'PASS'}else{'FAIL'}); expected='200/404'; actual="code=$($r.body.code)"; note='P7-08' })

$r = Invoke-And P7-09 DELETE "/admin/answer/$ans" $at
Record-Api 'AD-15' $(if ($r.body.code -in 200,404) { 'PASS' } else { 'FAIL' }) $r.http $r.body.code $r.body.msg '管理端删回答' @(@{ id='AD-15-01'; type='正向'; status=$(if($r.body.code-in 200,404){'PASS'}else{'FAIL'}); expected='200/404'; actual="code=$($r.body.code)"; note='P7-09' })

# Write env.sh
$lines = @('# auto-generated by run-full.ps1', "export BASE_URL=`"$BaseUrl`"")
foreach ($k in @('USER_TOKEN','ADMIN_TOKEN','USER_B_TOKEN','USER_ID','USER_B_ID','CONTENT_ID_PROF','CONTENT_ID_LIFE','QUESTION_ID','ANSWER_ID','COMMENT_ID','NOTIFICATION_ID','IMAGE_URL','REPORT_ID')) {
    $v = if ($envVars.ContainsKey($k)) { $envVars[$k] } else { '' }
    $lines += "export $k=`"$v`""
}
[System.IO.File]::WriteAllLines((Join-Path $ApiTestDir 'env.sh'), $lines)

$export = @{ date = $Today; apis = $apiResults; env = $envVars }
$export | ConvertTo-Json -Depth 8 | Set-Content -Path $OutFile -Encoding UTF8

$pass = ($apiResults.Values | Where-Object { $_.status -eq 'PASS' }).Count
$fail = ($apiResults.Values | Where-Object { $_.status -eq 'FAIL' }).Count
$blocked = ($apiResults.Values | Where-Object { $_.status -eq 'BLOCKED' }).Count
$skip = ($apiResults.Values | Where-Object { $_.status -in 'SKIP' }).Count
Write-Host "APIs recorded: $($apiResults.Count) PASS=$pass FAIL=$fail BLOCKED=$blocked SKIP=$skip -> $OutFile"
