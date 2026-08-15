[CmdletBinding()]
param(
    [string]$NdkVersion = '29.0.14206865',
    [string]$CMakeVersion = '3.22.1'
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$quickJsTag = 'v0.16.1'
$quickJsCommit = '954dc53628e36891f93c359aa60895c2ae3dac6b'
$quickJsRepository = 'https://github.com/quickjs-ng/quickjs.git'
$androidApi = 24
$abis = @('arm64-v8a', 'armeabi-v7a', 'x86', 'x86_64')

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$androidSdk = Join-Path $env:LOCALAPPDATA 'Android\Sdk'
$ndkRoot = Join-Path $androidSdk "ndk\$NdkVersion"
$cmakeRoot = Join-Path $androidSdk "cmake\$CMakeVersion"
$cmake = Join-Path $cmakeRoot 'bin\cmake.exe'
$ninja = Join-Path $cmakeRoot 'bin\ninja.exe'
$toolchain = Join-Path $ndkRoot 'build\cmake\android.toolchain.cmake'
$strip = Join-Path $ndkRoot 'toolchains\llvm\prebuilt\windows-x86_64\bin\llvm-strip.exe'

foreach ($requiredTool in @($cmake, $ninja, $toolchain, $strip)) {
    if (-not (Test-Path -LiteralPath $requiredTool -PathType Leaf)) {
        throw "Required Android build tool is missing: $requiredTool"
    }
}

$tempBase = [IO.Path]::GetTempPath().TrimEnd('\')
$workRoot = Join-Path $tempBase ("seal-quickjs-" + [Guid]::NewGuid().ToString('N'))
$sourceRoot = Join-Path $workRoot 'source'
$buildRoot = Join-Path $workRoot 'build'

try {
    New-Item -ItemType Directory -Path $workRoot | Out-Null
    & git clone --quiet --depth 1 --branch $quickJsTag $quickJsRepository $sourceRoot
    if ($LASTEXITCODE -ne 0) { throw 'QuickJS-NG clone failed.' }

    $actualCommit = (& git -C $sourceRoot rev-parse HEAD).Trim()
    if ($actualCommit -ne $quickJsCommit) {
        throw "QuickJS-NG tag resolved to unexpected commit: $actualCommit"
    }

    foreach ($abi in $abis) {
        $abiBuildRoot = Join-Path $buildRoot $abi
        $destinationRoot = Join-Path $repoRoot "app\src\main\jniLibs\$abi"
        New-Item -ItemType Directory -Path $abiBuildRoot -Force | Out-Null
        New-Item -ItemType Directory -Path $destinationRoot -Force | Out-Null

        & $cmake -S $sourceRoot -B $abiBuildRoot -G Ninja `
            "-DCMAKE_MAKE_PROGRAM=$ninja" `
            "-DCMAKE_TOOLCHAIN_FILE=$toolchain" `
            "-DANDROID_ABI=$abi" `
            "-DANDROID_PLATFORM=android-$androidApi" `
            '-DCMAKE_BUILD_TYPE=Release' `
            '-DQJS_BUILD_CLI_STATIC=ON' `
            '-DQJS_BUILD_EXAMPLES=OFF' `
            '-DQJS_BUILD_TESTS=OFF'
        if ($LASTEXITCODE -ne 0) { throw "QuickJS-NG configure failed for $abi." }

        & $cmake --build $abiBuildRoot --target qjs
        if ($LASTEXITCODE -ne 0) { throw "QuickJS-NG build failed for $abi." }

        $builtExecutable = Join-Path $abiBuildRoot 'qjs'
        if (-not (Test-Path -LiteralPath $builtExecutable -PathType Leaf)) {
            throw "QuickJS-NG executable was not produced for $abi."
        }

        & $strip $builtExecutable
        if ($LASTEXITCODE -ne 0) { throw "QuickJS-NG strip failed for $abi." }

        $destination = Join-Path $destinationRoot 'libqjs.so'
        Copy-Item -LiteralPath $builtExecutable -Destination $destination -Force
        Get-FileHash -LiteralPath $destination -Algorithm SHA256 |
            Select-Object Path, Hash
    }
}
finally {
    $resolvedTempBase = [IO.Path]::GetFullPath($tempBase + '\')
    $resolvedWorkRoot = [IO.Path]::GetFullPath($workRoot)
    if ($resolvedWorkRoot.StartsWith($resolvedTempBase, [StringComparison]::OrdinalIgnoreCase) -and
        (Test-Path -LiteralPath $resolvedWorkRoot)) {
        Remove-Item -LiteralPath $resolvedWorkRoot -Recurse -Force
    }
}
