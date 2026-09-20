[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string] $ResultRoot,

    [Parameter(Mandatory = $true)]
    [ValidateSet('baseline', 'candidate')]
    [string] $Variant,

    [string] $OutputCsv,

    [string] $OutputMarkdown
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Get-DisplayPath {
    param([Parameter(Mandatory = $true)][string] $Path)

    $resolvedPath = (Resolve-Path -LiteralPath $Path).Path
    $workingRoot = (Get-Location).Path.TrimEnd('\', '/')
    if ($resolvedPath.StartsWith($workingRoot, [System.StringComparison]::OrdinalIgnoreCase)) {
        return $resolvedPath.Substring($workingRoot.Length).TrimStart('\', '/')
    }
    return $resolvedPath
}

function Get-RequiredNumber {
    param(
        [Parameter(Mandatory = $true)] $Object,
        [Parameter(Mandatory = $true)][string] $Name,
        [Parameter(Mandatory = $true)][string] $Path
    )

    $property = $Object.PSObject.Properties[$Name]
    if ($null -eq $property -or $null -eq $property.Value -or [string]::IsNullOrWhiteSpace([string] $property.Value)) {
        throw "statistics.json is missing a non-empty Total.$Name field: $Path"
    }
    return [double] $property.Value
}

function Get-ThreeRoundMedian {
    param([Parameter(Mandatory = $true)][double[]] $Values)

    if ($Values.Count -ne 3) {
        throw "Expected exactly three measured rounds, got $($Values.Count)."
    }
    $sorted = @($Values | Sort-Object)
    return $sorted[1]
}

function Get-JtlDurationSeconds {
    param([Parameter(Mandatory = $true)][string] $Path)

    $firstTwoLines = @(Get-Content -LiteralPath $Path -TotalCount 2 -Encoding UTF8)
    if ($firstTwoLines.Count -ne 2) {
        throw "JTL must contain a header and at least one sample: $Path"
    }
    $lastLine = Get-Content -LiteralPath $Path -Tail 1 -Encoding UTF8
    $firstSample = @($firstTwoLines[0], $firstTwoLines[1]) | ConvertFrom-Csv | Select-Object -First 1
    $lastSample = @($firstTwoLines[0], $lastLine) | ConvertFrom-Csv | Select-Object -First 1
    $startMillis = [double] $firstSample.timeStamp
    $endMillis = [double] $lastSample.timeStamp + [double] $lastSample.elapsed
    return ($endMillis - $startMillis) / 1000.0
}

$root = (Resolve-Path -LiteralPath $ResultRoot).Path
$rounds = @()
$requiredFields = @(
    'sampleCount',
    'errorCount',
    'errorPct',
    'throughput',
    'minResTime',
    'maxResTime',
    'meanResTime',
    'medianResTime',
    'pct1ResTime',
    'pct2ResTime',
    'pct3ResTime',
    'receivedKBytesPerSec',
    'sentKBytesPerSec'
)

foreach ($round in 1..3) {
    $reportDirectory = Join-Path $root "$Variant-r$round-report"
    $statisticsPath = Join-Path $reportDirectory 'statistics.json'
    $jtlPath = Join-Path $root "$Variant-r$round.jtl"

    if (-not (Test-Path -LiteralPath $statisticsPath -PathType Leaf)) {
        throw "Missing JMeter statistics.json for ${Variant}-r${round}: $statisticsPath"
    }
    if (-not (Test-Path -LiteralPath $jtlPath -PathType Leaf)) {
        throw "Missing JTL for ${Variant}-r${round}: $jtlPath"
    }

    $statistics = Get-Content -LiteralPath $statisticsPath -Raw -Encoding UTF8 | ConvertFrom-Json
    $total = $statistics.PSObject.Properties['Total']
    if ($null -eq $total -or $null -eq $total.Value) {
        throw "statistics.json must contain a Total object: $statisticsPath"
    }
    $total = $total.Value
    foreach ($field in $requiredFields) {
        [void] (Get-RequiredNumber -Object $total -Name $field -Path $statisticsPath)
    }

    $hash = (Get-FileHash -LiteralPath $jtlPath -Algorithm SHA256).Hash
    $rounds += [pscustomobject] [ordered]@{
        Variant = $Variant
        Round = $round
        StatisticsPath = Get-DisplayPath -Path $statisticsPath
        JtlPath = Get-DisplayPath -Path $jtlPath
        JtlSha256 = $hash
        SampleCount = [int64] (Get-RequiredNumber -Object $total -Name 'sampleCount' -Path $statisticsPath)
        ErrorCount = [int64] (Get-RequiredNumber -Object $total -Name 'errorCount' -Path $statisticsPath)
        ErrorPct = Get-RequiredNumber -Object $total -Name 'errorPct' -Path $statisticsPath
        DurationSeconds = Get-JtlDurationSeconds -Path $jtlPath
        ThroughputRps = Get-RequiredNumber -Object $total -Name 'throughput' -Path $statisticsPath
        MinMs = Get-RequiredNumber -Object $total -Name 'minResTime' -Path $statisticsPath
        MaxMs = Get-RequiredNumber -Object $total -Name 'maxResTime' -Path $statisticsPath
        MeanMs = Get-RequiredNumber -Object $total -Name 'meanResTime' -Path $statisticsPath
        MedianMs = Get-RequiredNumber -Object $total -Name 'medianResTime' -Path $statisticsPath
        P90Ms = Get-RequiredNumber -Object $total -Name 'pct1ResTime' -Path $statisticsPath
        P95Ms = Get-RequiredNumber -Object $total -Name 'pct2ResTime' -Path $statisticsPath
        P99Ms = Get-RequiredNumber -Object $total -Name 'pct3ResTime' -Path $statisticsPath
        ReceivedKBps = Get-RequiredNumber -Object $total -Name 'receivedKBytesPerSec' -Path $statisticsPath
        SentKBps = Get-RequiredNumber -Object $total -Name 'sentKBytesPerSec' -Path $statisticsPath
    }
}

if ([string]::IsNullOrWhiteSpace($OutputCsv)) {
    $OutputCsv = Join-Path $root "$Variant-summary.csv"
}
$rounds | Export-Csv -LiteralPath $OutputCsv -NoTypeInformation -Encoding UTF8

$median = [pscustomobject] [ordered]@{
    Variant = $Variant
    Round = 'median'
    SampleCount = Get-ThreeRoundMedian -Values ([double[]] $rounds.SampleCount)
    ErrorCount = Get-ThreeRoundMedian -Values ([double[]] $rounds.ErrorCount)
    ErrorPct = Get-ThreeRoundMedian -Values ([double[]] $rounds.ErrorPct)
    DurationSeconds = Get-ThreeRoundMedian -Values ([double[]] $rounds.DurationSeconds)
    ThroughputRps = Get-ThreeRoundMedian -Values ([double[]] $rounds.ThroughputRps)
    MinMs = Get-ThreeRoundMedian -Values ([double[]] $rounds.MinMs)
    MaxMs = Get-ThreeRoundMedian -Values ([double[]] $rounds.MaxMs)
    MeanMs = Get-ThreeRoundMedian -Values ([double[]] $rounds.MeanMs)
    MedianMs = Get-ThreeRoundMedian -Values ([double[]] $rounds.MedianMs)
    P90Ms = Get-ThreeRoundMedian -Values ([double[]] $rounds.P90Ms)
    P95Ms = Get-ThreeRoundMedian -Values ([double[]] $rounds.P95Ms)
    P99Ms = Get-ThreeRoundMedian -Values ([double[]] $rounds.P99Ms)
    ReceivedKBps = Get-ThreeRoundMedian -Values ([double[]] $rounds.ReceivedKBps)
    SentKBps = Get-ThreeRoundMedian -Values ([double[]] $rounds.SentKBps)
}

if ([string]::IsNullOrWhiteSpace($OutputMarkdown)) {
    $OutputMarkdown = Join-Path $root "$Variant-summary.md"
}
$markdown = @(
    "# $Variant trending cache summary",
    '',
    '| Round | Samples | Errors | Error % | Duration s | Throughput req/s | Min ms | Max ms | Mean ms | Median ms | P90 ms | P95 ms | P99 ms | Received KB/s | Sent KB/s | JTL SHA-256 |',
    '|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---|'
)
foreach ($row in $rounds) {
    $markdown += "| $($row.Round) | $($row.SampleCount) | $($row.ErrorCount) | $($row.ErrorPct) | $($row.DurationSeconds) | $($row.ThroughputRps) | $($row.MinMs) | $($row.MaxMs) | $($row.MeanMs) | $($row.MedianMs) | $($row.P90Ms) | $($row.P95Ms) | $($row.P99Ms) | $($row.ReceivedKBps) | $($row.SentKBps) | $($row.JtlSha256) |"
}
$markdown += "| median | $($median.SampleCount) | $($median.ErrorCount) | $($median.ErrorPct) | $($median.DurationSeconds) | $($median.ThroughputRps) | $($median.MinMs) | $($median.MaxMs) | $($median.MeanMs) | $($median.MedianMs) | $($median.P90Ms) | $($median.P95Ms) | $($median.P99Ms) | $($median.ReceivedKBps) | $($median.SentKBps) | n/a |"
$markdown | Set-Content -LiteralPath $OutputMarkdown -Encoding UTF8

[pscustomobject] [ordered]@{
    Variant = $Variant
    RoundCount = $rounds.Count
    CsvPath = Get-DisplayPath -Path $OutputCsv
    MarkdownPath = Get-DisplayPath -Path $OutputMarkdown
    Rounds = $rounds
    Median = $median
} | ConvertTo-Json -Depth 5
