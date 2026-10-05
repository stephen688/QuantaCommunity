[CmdletBinding()]
param(
    [string]$UserId,
    [string]$Username
)

$ErrorActionPreference = 'Stop'
$scriptRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$projectRoot = Split-Path -Parent $scriptRoot
$classpathFile = [System.IO.Path]::GetTempFileName()
$targetClasses = Join-Path $projectRoot 'target/classes'
$exitCode = 0
$locationPushed = $false

try {
    if (-not (Get-Command java -ErrorAction SilentlyContinue)) {
        throw '找不到 java，请先安装 JDK 17 或更高版本并加入 PATH。'
    }
    if (-not (Get-Command mvn -ErrorAction SilentlyContinue)) {
        throw '找不到 mvn，请先安装 Maven 并加入 PATH。'
    }
    if ([string]::IsNullOrWhiteSpace($env:DB_PASSWORD)) {
        throw '未设置 DB_PASSWORD；脚本不会把数据库密码作为命令行参数传递。'
    }

    Push-Location $projectRoot
    $locationPushed = $true

    & mvn '-q' '-DskipTests' 'compile' 'dependency:build-classpath' "-Dmdep.outputFile=$classpathFile"
    if ($LASTEXITCODE -ne 0) {
        throw 'Maven 编译或依赖 classpath 准备失败，未执行凭据写入。'
    }
    if (-not (Test-Path -LiteralPath $targetClasses)) {
        throw '未找到 target/classes，无法启动初始化工具。'
    }

    $dependencyClasspath = (Get-Content -Raw -LiteralPath $classpathFile).Trim()
    if ([string]::IsNullOrWhiteSpace($dependencyClasspath)) {
        throw 'Maven 未生成依赖 classpath，未执行凭据写入。'
    }

    $javaClasspath = $targetClasses + [System.IO.Path]::PathSeparator + $dependencyClasspath
    $javaArguments = @(
        '-cp',
        $javaClasspath,
        'com.quanta.demo0.platform.security.bootstrap.AdminCredentialBootstrap'
    )
    if (-not [string]::IsNullOrWhiteSpace($UserId)) {
        $javaArguments += @('--user-id', $UserId)
    }
    if (-not [string]::IsNullOrWhiteSpace($Username)) {
        $javaArguments += @('--username', $Username)
    }

    & java @javaArguments
    $exitCode = $LASTEXITCODE
} catch {
    Write-Error $_.Exception.Message
    $exitCode = 1
} finally {
    if ($locationPushed) {
        Pop-Location
    }
    if (Test-Path -LiteralPath $classpathFile) {
        Remove-Item -LiteralPath $classpathFile -Force -ErrorAction SilentlyContinue
    }
}

exit $exitCode
