[CmdletBinding()]
param(
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$PluginZip,
    [string]$CourseDir,
    [string]$Output
)
$ErrorActionPreference = 'Stop'
# Resolve script-relative defaults in the body for Windows PowerShell 5.1 compatibility.
if (-not $PluginZip) { $PluginZip = Join-Path (Split-Path -Parent $PSScriptRoot) 'build\distributions\coursemate-ai-0.5.2.zip' }
if (-not $CourseDir) { $CourseDir = Join-Path (Split-Path -Parent $PSScriptRoot) 'course_files' }
if (-not $Output) { $Output = Join-Path (Split-Path -Parent $PSScriptRoot) '.coursemate\acceptance\live-acceptance.json' }
if (-not $JavaHome) {
    $taskPortable = Join-Path (Split-Path -Parent (Split-Path -Parent $PSScriptRoot)) 'tools\coursemate\jdk-25\jdk-25.0.4.1+1'
    if (Test-Path -LiteralPath (Join-Path $taskPortable 'bin\java.exe')) { $JavaHome = $taskPortable }
}
if (-not $JavaHome -or -not (Test-Path -LiteralPath (Join-Path $JavaHome 'bin\java.exe'))) { throw 'Pass -JavaHome with a JDK 25 folder.' }
if (-not (Test-Path -LiteralPath $PluginZip)) { throw 'Build the 0.5.2 plugin first or pass -PluginZip.' }
$taskOriginalKey = [Environment]::GetEnvironmentVariable('DEEPSEEK_API_KEY', 'Process')
$taskScratch = Join-Path ([IO.Path]::GetTempPath()) ('cm-live-libs-' + [Guid]::NewGuid().ToString('N'))
try {
    if ([string]::IsNullOrWhiteSpace($taskOriginalKey)) {
        $taskSecret = Read-Host 'DeepSeek API key (hidden; at most 6 calls, no retries)' -AsSecureString
        $env:DEEPSEEK_API_KEY = [System.Net.NetworkCredential]::new('', $taskSecret).Password
        Remove-Variable taskSecret
    }
    New-Item -ItemType Directory -Path $taskScratch | Out-Null
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $taskArchive = [IO.Compression.ZipFile]::OpenRead((Resolve-Path -LiteralPath $PluginZip).Path)
    try {
        foreach ($taskEntry in $taskArchive.Entries) {
            if ($taskEntry.FullName -match '/lib/[^/]+\.jar$') { [IO.Compression.ZipFileExtensions]::ExtractToFile($taskEntry, (Join-Path $taskScratch $taskEntry.Name)) }
        }
    } finally { $taskArchive.Dispose() }
    & (Join-Path $JavaHome 'bin\java.exe') '-Dfile.encoding=UTF-8' -cp (Join-Path $taskScratch '*') (Join-Path $PSScriptRoot 'LiveAcceptance.java') $CourseDir $Output
    if ($LASTEXITCODE -ne 0) { throw "Live acceptance failed: $LASTEXITCODE" }
} finally {
    [Environment]::SetEnvironmentVariable('DEEPSEEK_API_KEY', $taskOriginalKey, 'Process')
    $taskResolved = [IO.Path]::GetFullPath($taskScratch)
    $taskTemp = [IO.Path]::GetFullPath([IO.Path]::GetTempPath())
    if ($taskResolved.StartsWith($taskTemp, [StringComparison]::OrdinalIgnoreCase) -and [IO.Path]::GetFileName($taskResolved).StartsWith('cm-live-libs-') -and (Test-Path -LiteralPath $taskResolved)) {
        Remove-Item -LiteralPath $taskResolved -Recurse -Force
    }
}
