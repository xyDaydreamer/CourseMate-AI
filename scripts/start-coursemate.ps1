[CmdletBinding()]
param(
    [ValidateSet('Run', 'Test', 'Build', 'Check')][string]$Mode = 'Run',
    [string]$IdeaHome = $env:COURSEMATE_IDEA_HOME,
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$CourseDir = $env:COURSEMATE_COURSE_FILES,
    [string]$ProjectDir = $env:COURSEMATE_PROJECT_PATH,
    [switch]$WithoutApiKey,
    [switch]$DryRun
)
$ErrorActionPreference = 'Stop'
$taskRepo = Split-Path -Parent $PSScriptRoot
$taskSettings = Join-Path $taskRepo 'local.properties'
if (-not $IdeaHome -and (Test-Path -LiteralPath $taskSettings)) {
    $taskSetting = Get-Content -LiteralPath $taskSettings -Encoding UTF8 |
        Where-Object { $_ -match '^\s*ideaLocalPath\s*[=:]' } | Select-Object -First 1
    if ($taskSetting) {
        $taskValue = ($taskSetting -split '[=:]', 2)[1].Trim()
        $IdeaHome = [regex]::Replace($taskValue, '\\u([0-9a-fA-F]{4})', {
            param($match) [string][char][Convert]::ToInt32($match.Groups[1].Value, 16)
        }).Replace('\\', '\').Replace('\:', ':')
    }
}
if (-not $IdeaHome) {
    $IdeaHome = Read-Host 'IDEA 2026.2.3 installation folder (contains product-info.json)'
}
$IdeaHome = $IdeaHome.Trim('"')
if (-not (Test-Path -LiteralPath (Join-Path $IdeaHome 'product-info.json'))) {
    throw 'IDEA installation not found. Set COURSEMATE_IDEA_HOME or local.properties ideaLocalPath.'
}
$taskProduct = Get-Content -LiteralPath (Join-Path $IdeaHome 'product-info.json') -Raw -Encoding UTF8 | ConvertFrom-Json
if ($taskProduct.version -ne '2026.2.3') { throw 'This version targets IDEA 2026.2.3. Select the matching installation.' }
if (-not $JavaHome -and (Test-Path -LiteralPath (Join-Path $IdeaHome 'jbr\bin\javac.exe'))) {
    $JavaHome = Join-Path $IdeaHome 'jbr'
}
if (-not $JavaHome -or -not (Test-Path -LiteralPath (Join-Path $JavaHome 'bin\javac.exe'))) {
    throw 'A full JDK 25 is required. Set JAVA_HOME (IDEA bundled jbr may also contain javac).'
}
$taskRelease = Join-Path $JavaHome 'release'
if (-not (Test-Path -LiteralPath $taskRelease) -or
    -not ((Get-Content -LiteralPath $taskRelease) -match '^JAVA_VERSION="25(?:\.|\")')) {
    throw 'JAVA_HOME must point to JDK 25.'
}
if (-not $CourseDir) { $CourseDir = Join-Path $taskRepo 'course_files' }
$taskSaved = @{}
foreach ($taskName in @('JAVA_HOME', 'Path', 'GRADLE_USER_HOME', 'COURSEMATE_COURSE_FILES', 'DEEPSEEK_API_KEY')) {
    $taskSaved[$taskName] = [Environment]::GetEnvironmentVariable($taskName, 'Process')
}
$taskPreviousLocation = Get-Location
try {
    Set-Location -LiteralPath $taskRepo
    $env:JAVA_HOME = $JavaHome
    $env:Path = "$JavaHome\bin;$env:Path"
    if (-not $env:GRADLE_USER_HOME) { $env:GRADLE_USER_HOME = Join-Path $taskRepo '.gradle-user-home' }
    $env:COURSEMATE_COURSE_FILES = [IO.Path]::GetFullPath($CourseDir)
    Write-Host "Project: $taskRepo"
    Write-Host "IDEA: $IdeaHome"
    Write-Host "JDK: $JavaHome"
    Write-Host "Lectures: $env:COURSEMATE_COURSE_FILES"
    Write-Host ('API Key: ' + $(if ([string]::IsNullOrWhiteSpace($env:DEEPSEEK_API_KEY)) { 'not configured' } else { 'configured (not validated)' }))
    if (-not (Test-Path -LiteralPath $env:COURSEMATE_COURSE_FILES -PathType Container)) {
        Write-Warning 'Lecture directory is missing. Add the course PDFs before course-grounded Q&A/integration tests.'
    }
    if ($Mode -eq 'Check') { return }
    if ($Mode -eq 'Run') {
        if (-not $ProjectDir) {
            $ProjectDir = Join-Path $taskRepo 'demo'
            if (-not (Test-Path -LiteralPath (Join-Path $ProjectDir 'src\CourseMateDemo.java'))) {
                throw 'Bundled demo source is missing. Restore demo/ or pass -ProjectDir with an existing Java project.'
            }
            # Generate only missing local IDE metadata; never overwrite an existing workspace.
            $taskDemoSettings = Join-Path $ProjectDir '.idea'
            New-Item -ItemType Directory -Path $taskDemoSettings -Force | Out-Null
            $taskModules = Join-Path $taskDemoSettings 'modules.xml'
            if (-not (Test-Path -LiteralPath $taskModules)) {
                [IO.File]::WriteAllText($taskModules, '<project version="4"><component name="ProjectModuleManager"><modules><module fileurl="file://$PROJECT_DIR$/coursemate-demo.iml" filepath="$PROJECT_DIR$/coursemate-demo.iml" /></modules></component></project>')
            }
            $taskModule = Join-Path $ProjectDir 'coursemate-demo.iml'
            if (-not (Test-Path -LiteralPath $taskModule)) {
                [IO.File]::WriteAllText($taskModule, '<module type="JAVA_MODULE" version="4"><component name="NewModuleRootManager" inherit-compiler-output="true"><exclude-output /><content url="file://$MODULE_DIR$"><sourceFolder url="file://$MODULE_DIR$/src" isTestSource="false" /></content><orderEntry type="inheritedJdk" /><orderEntry type="sourceFolder" forTests="false" /></component></module>')
            }
        }
        $ProjectDir = (Resolve-Path -LiteralPath $ProjectDir -ErrorAction Stop).Path
        if (-not (Test-Path -LiteralPath $ProjectDir -PathType Container)) { throw 'ProjectDir must be a project directory.' }
        Write-Host "Opening Java project: $ProjectDir"
    }
    if ($Mode -ne 'Run' -or $WithoutApiKey -or $DryRun) { Remove-Item Env:DEEPSEEK_API_KEY -ErrorAction SilentlyContinue }
    if ($Mode -eq 'Run' -and -not $WithoutApiKey -and -not $DryRun -and [string]::IsNullOrWhiteSpace($env:DEEPSEEK_API_KEY)) {
        $taskSecret = Read-Host 'DeepSeek API Key (hidden; empty input starts without API access)' -AsSecureString
        $env:DEEPSEEK_API_KEY = [Net.NetworkCredential]::new('', $taskSecret).Password
        Remove-Variable taskSecret
    }
    # switch pipeline output unwraps one item to a scalar. Always keep a typed array before += and splatting.
    [string[]]$taskArgs = @(switch ($Mode) { 'Run' { 'runIde' }; 'Test' { 'test' }; 'Build' { 'test'; 'buildPlugin' } })
    $taskArgs += @("-PideaLocalPath=$IdeaHome", '--console=plain', '--no-daemon')
    if ($Mode -eq 'Run') { $taskArgs += "-PcoursemateProjectPath=$ProjectDir" }
    if ($env:LOCALAPPDATA) {
        $taskDigest = [Security.Cryptography.SHA256]::Create()
        try { $taskId = ([BitConverter]::ToString($taskDigest.ComputeHash([Text.Encoding]::UTF8.GetBytes($taskRepo)))).Replace('-', '').Substring(0, 8) }
        finally { $taskDigest.Dispose() }
        $taskSandbox = Join-Path $env:LOCALAPPDATA "CM\s\$taskId"
        $taskArgs += "-PcoursemateSandboxRoot=$taskSandbox"
    }
    # Match the Windows Chinese native argument-file encoding; Java sources remain UTF-8.
    if ([Globalization.CultureInfo]::CurrentCulture.TextInfo.ANSICodePage -eq 936) {
        $taskArgs += '-Dorg.gradle.jvmargs=-Xmx1536m -Dfile.encoding=GBK'
    }
    if ($DryRun) { $taskArgs += '--dry-run' }
    & (Join-Path $taskRepo 'gradlew.bat') @taskArgs
    if ($LASTEXITCODE -ne 0) { throw "Gradle failed with exit code $LASTEXITCODE" }
}
finally {
    foreach ($taskName in $taskSaved.Keys) { [Environment]::SetEnvironmentVariable($taskName, $taskSaved[$taskName], 'Process') }
    Set-Location -LiteralPath $taskPreviousLocation.Path
}
