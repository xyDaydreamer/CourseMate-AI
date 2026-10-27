[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$PluginZip,
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$CourseDir,
    [string]$Output,
    [string]$Label = 'after-0.5.3'
)
$ErrorActionPreference = 'Stop'
# Resolve script-relative defaults in the body for Windows PowerShell 5.1 compatibility.
if (-not $CourseDir) { $CourseDir = Join-Path (Split-Path -Parent $PSScriptRoot) 'course_files' }
if (-not $Output) { $Output = Join-Path (Split-Path -Parent $PSScriptRoot) '.coursemate\evaluation\latest.json' }
if (-not $JavaHome -or -not (Test-Path -LiteralPath (Join-Path $JavaHome 'bin\java.exe'))) {
    throw 'Set JAVA_HOME to JDK 25 or pass -JavaHome.'
}
$taskZip = (Resolve-Path -LiteralPath $PluginZip).Path
$taskOutputPath = [IO.Path]::GetFullPath($Output)
New-Item -ItemType Directory -Path (Split-Path -Parent $taskOutputPath) -Force | Out-Null
$taskScratch = Join-Path ([IO.Path]::GetTempPath()) ('cm-eval-' + [Guid]::NewGuid().ToString('N'))
$taskKey = [Environment]::GetEnvironmentVariable('DEEPSEEK_API_KEY', 'Process')
New-Item -ItemType Directory -Path $taskScratch | Out-Null
try {
    Remove-Item Env:DEEPSEEK_API_KEY -ErrorAction SilentlyContinue
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $taskArchive = [IO.Compression.ZipFile]::OpenRead($taskZip)
    try {
        foreach ($taskEntry in $taskArchive.Entries) {
            if ($taskEntry.FullName -match '/lib/[^/]+\.jar$') {
                [IO.Compression.ZipFileExtensions]::ExtractToFile($taskEntry, (Join-Path $taskScratch $taskEntry.Name))
            }
        }
    } finally { $taskArchive.Dispose() }
    & (Join-Path $JavaHome 'bin\java.exe') '-Dfile.encoding=UTF-8' -cp (Join-Path $taskScratch '*') `
        (Join-Path $PSScriptRoot 'RagEvaluation.java') $CourseDir (Join-Path $PSScriptRoot 'questions.json') $Output $Label
    if ($LASTEXITCODE -ne 0) { throw "Offline evaluation failed: $LASTEXITCODE" }
    $taskResult = Get-Content -LiteralPath $Output -Raw -Encoding UTF8 | ConvertFrom-Json
    $taskResult | Add-Member -NotePropertyName pluginZipSha256 -NotePropertyValue (Get-FileHash -LiteralPath $taskZip -Algorithm SHA256).Hash.ToLowerInvariant() -Force
    $taskResult | Add-Member -NotePropertyName runnerSha256 -NotePropertyValue (Get-FileHash -LiteralPath (Join-Path $PSScriptRoot 'RagEvaluation.java') -Algorithm SHA256).Hash.ToLowerInvariant() -Force
    [IO.File]::WriteAllText([IO.Path]::GetFullPath($Output), ($taskResult | ConvertTo-Json -Depth 30), [Text.UTF8Encoding]::new($false))
}
finally {
    [Environment]::SetEnvironmentVariable('DEEPSEEK_API_KEY', $taskKey, 'Process')
    # Delete only the freshly created, verified evaluation directory, using one shell throughout.
    $taskResolved = [IO.Path]::GetFullPath($taskScratch)
    $taskTempRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath())
    if ($taskResolved.StartsWith($taskTempRoot, [StringComparison]::OrdinalIgnoreCase) -and
        [IO.Path]::GetFileName($taskResolved).StartsWith('cm-eval-')) {
        Remove-Item -LiteralPath $taskResolved -Recurse -Force
    }
}
