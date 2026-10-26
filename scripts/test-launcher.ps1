# End-to-end Windows PowerShell 5.1/CMD argument regression, using a fake Gradle (no IDE/network/API).
$ErrorActionPreference = 'Stop'
$taskRepo = Split-Path -Parent $PSScriptRoot
$taskTemporary = Join-Path ([IO.Path]::GetTempPath()) ('CM-launcher-test-' + [Guid]::NewGuid().ToString('N'))
$taskFixture = Join-Path $taskTemporary ('repo with spaces ' + [char]0x4E2D + [char]0x6587)
$taskIdea = Join-Path $taskTemporary ('IDE with spaces ' + [char]0x4E2D + [char]0x6587)
$taskJava = Join-Path $taskIdea 'jbr'
$taskResultPath = Join-Path $taskTemporary 'captured.json'
$taskSaved = @{}
foreach ($taskName in @('CM_LAUNCHER_TEST_OUTPUT', 'DEEPSEEK_API_KEY')) {
    $taskSaved[$taskName] = [Environment]::GetEnvironmentVariable($taskName, 'Process')
}
function Assert-Launcher($condition, $message) {
    if (-not $condition) { throw $message }
}
try {
    New-Item -ItemType Directory -Path (Join-Path $taskFixture 'scripts'), (Join-Path $taskFixture 'course_files'), (Join-Path $taskJava 'bin') -Force | Out-Null
    Copy-Item -LiteralPath (Join-Path $taskRepo 'start-coursemate.cmd') -Destination $taskFixture
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'start-coursemate.ps1') -Destination (Join-Path $taskFixture 'scripts')
    New-Item -ItemType Directory -Path (Join-Path $taskFixture 'demo\src') | Out-Null
    Copy-Item -LiteralPath (Join-Path $taskRepo 'demo\src\CourseMateDemo.java') -Destination (Join-Path $taskFixture 'demo\src')
    [IO.File]::WriteAllText((Join-Path $taskIdea 'product-info.json'), '{"version":"2026.2.3"}')
    [IO.File]::WriteAllText((Join-Path $taskJava 'release'), 'JAVA_VERSION="25.0.4"')
    [IO.File]::WriteAllBytes((Join-Path $taskJava 'bin\javac.exe'), [byte[]]@())
    $taskCapture = @'
$taskReport = [ordered]@{ Arguments = @($args); KeyPresent = -not [string]::IsNullOrWhiteSpace($env:DEEPSEEK_API_KEY) }
[IO.File]::WriteAllText($env:CM_LAUNCHER_TEST_OUTPUT, ($taskReport | ConvertTo-Json -Depth 5), [Text.UTF8Encoding]::new($false))
exit 0
'@
    [IO.File]::WriteAllText((Join-Path $taskFixture 'capture.ps1'), $taskCapture, [Text.UTF8Encoding]::new($false))
    [IO.File]::WriteAllText((Join-Path $taskFixture 'gradlew.bat'), "@echo off`r`npowershell.exe -NoProfile -ExecutionPolicy Bypass -File `"%~dp0capture.ps1`" %*`r`nexit /b %ERRORLEVEL%`r`n", [Text.Encoding]::ASCII)
    $env:CM_LAUNCHER_TEST_OUTPUT = $taskResultPath
    $env:DEEPSEEK_API_KEY = 'launcher-test-synthetic-not-a-real-key'
    $taskPassed = 0
    foreach ($taskMode in @('Run', 'Test', 'Build')) {
        & (Join-Path $taskFixture 'start-coursemate.cmd') -Mode $taskMode -IdeaHome $taskIdea -JavaHome $taskJava -WithoutApiKey
        Assert-Launcher ($LASTEXITCODE -eq 0) "$taskMode wrapper failed"
        $taskReport = Get-Content -LiteralPath $taskResultPath -Raw -Encoding UTF8 | ConvertFrom-Json
        $taskExpected = if ($taskMode -eq 'Run') { 'runIde' } else { 'test' }
        Assert-Launcher ($taskReport.Arguments[0] -ceq $taskExpected) "$taskMode task was split or concatenated"
        if ($taskMode -eq 'Build') { Assert-Launcher ($taskReport.Arguments[1] -ceq 'buildPlugin') 'Build lost its second task' }
        Assert-Launcher ($taskReport.Arguments -ccontains "-PideaLocalPath=$taskIdea") 'IDE path with spaces/Unicode was split'
        Assert-Launcher ($taskReport.Arguments -contains '--console=plain') 'Console flag was split'
        Assert-Launcher ($taskReport.Arguments -contains '--no-daemon') 'No-daemon flag was split'
        if ($taskMode -eq 'Run') {
            Assert-Launcher ($taskReport.Arguments -ccontains "-PcoursemateProjectPath=$(Join-Path $taskFixture 'demo')") 'Run did not open its bundled demo'
            Assert-Launcher (Test-Path -LiteralPath (Join-Path $taskFixture 'demo\.idea\modules.xml')) 'Demo was not initialized as an IDE project'
        }
        if ([Globalization.CultureInfo]::CurrentCulture.TextInfo.ANSICodePage -eq 936) {
            Assert-Launcher ($taskReport.Arguments -contains '-Dorg.gradle.jvmargs=-Xmx1536m -Dfile.encoding=GBK') 'JVM argument with spaces was split'
        }
        Assert-Launcher (-not $taskReport.KeyPresent) 'Offline test inherited an API credential'
        $taskPassed++
    }
    # Check must return before Gradle invocation.
    Remove-Item -LiteralPath $taskResultPath
    & (Join-Path $taskFixture 'start-coursemate.cmd') -Mode Check -IdeaHome $taskIdea -JavaHome $taskJava
    Assert-Launcher ($LASTEXITCODE -eq 0 -and -not (Test-Path -LiteralPath $taskResultPath)) 'Check invoked Gradle'
    $taskPassed++
    # A Run dry-run needs no credential and must pass its flag as a separate argument.
    & (Join-Path $taskFixture 'start-coursemate.cmd') -Mode Run -IdeaHome $taskIdea -JavaHome $taskJava -DryRun
    $taskReport = Get-Content -LiteralPath $taskResultPath -Raw -Encoding UTF8 | ConvertFrom-Json
    Assert-Launcher ($LASTEXITCODE -eq 0 -and $taskReport.Arguments[0] -ceq 'runIde') 'Dry-run task was split'
    Assert-Launcher ($taskReport.Arguments -contains '--dry-run') 'Dry-run flag missing'
    Assert-Launcher (-not $taskReport.KeyPresent) 'Dry-run inherited API credential'
    $taskPassed++
    # Explicit projects are forwarded intact and are not modified by demo initialization.
    $taskCustomProject = Join-Path $taskTemporary ('custom project ' + [char]0x4E2D + [char]0x6587)
    New-Item -ItemType Directory -Path $taskCustomProject | Out-Null
    & (Join-Path $taskFixture 'start-coursemate.cmd') -Mode Run -IdeaHome $taskIdea -JavaHome $taskJava -WithoutApiKey -ProjectDir $taskCustomProject
    $taskReport = Get-Content -LiteralPath $taskResultPath -Raw -Encoding UTF8 | ConvertFrom-Json
    Assert-Launcher ($taskReport.Arguments -ccontains "-PcoursemateProjectPath=$taskCustomProject") 'Explicit project path was split'
    Assert-Launcher (-not (Test-Path -LiteralPath (Join-Path $taskCustomProject '.idea'))) 'Explicit project was modified'
    $taskPassed++
    Assert-Launcher ($env:DEEPSEEK_API_KEY -eq 'launcher-test-synthetic-not-a-real-key') 'Caller environment was not restored'
    Write-Host "Launcher regression: $taskPassed/6 passed (Run/Test/Build/Check/DryRun/CustomProject; no real Gradle or API)."
}
finally {
    foreach ($taskName in $taskSaved.Keys) { [Environment]::SetEnvironmentVariable($taskName, $taskSaved[$taskName], 'Process') }
    $taskResolved = [IO.Path]::GetFullPath($taskTemporary)
    $taskTempRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath())
    if ($taskResolved.StartsWith($taskTempRoot, [StringComparison]::OrdinalIgnoreCase) -and
        [IO.Path]::GetFileName($taskResolved).StartsWith('CM-launcher-test-')) {
        Remove-Item -LiteralPath $taskResolved -Recurse -Force
    }
}
