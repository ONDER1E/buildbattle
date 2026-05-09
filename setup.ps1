# ============================================================
# BuildBattle Plugin - Setup Script
# Run from INSIDE the buildbattle/ folder (where pom.xml lives):
#   cd Z:\vscode\buildbattle
#   .\setup.ps1
# Requirements: JDK 17+, Maven 3.8+ on PATH
# ============================================================

$ErrorActionPreference = "Stop"
$utf8NoBom = New-Object System.Text.UTF8Encoding $false

$ProjectRoot = $PSScriptRoot
if (-not $ProjectRoot) { $ProjectRoot = (Get-Location).Path }
Write-Host "Project root: $ProjectRoot" -ForegroundColor Cyan

if (-not (Test-Path "$ProjectRoot\pom.xml")) {
    Write-Host "ERROR: pom.xml not found. Run from inside the buildbattle/ folder." -ForegroundColor Red
    exit 1
}

# Strip BOM from every source/resource file (PowerShell UTF8 adds BOM which breaks javac)
Write-Host "Stripping BOM from all source files..." -ForegroundColor Yellow
Get-ChildItem -Path "$ProjectRoot\src" -Recurse -File | ForEach-Object {
    $bytes = [System.IO.File]::ReadAllBytes($_.FullName)
    if ($bytes.Length -ge 3 -and $bytes[0] -eq 0xEF -and $bytes[1] -eq 0xBB -and $bytes[2] -eq 0xBF) {
        $noBom = $bytes[3..($bytes.Length - 1)]
        [System.IO.File]::WriteAllBytes($_.FullName, $noBom)
        Write-Host "  BOM removed: $($_.Name)" -ForegroundColor Gray
    }
}

# Also strip BOM from pom.xml and plugin.yml/config.yml
@("pom.xml", "src\main\resources\plugin.yml", "src\main\resources\config.yml") | ForEach-Object {
    $path = Join-Path $ProjectRoot $_
    if (Test-Path $path) {
        $bytes = [System.IO.File]::ReadAllBytes($path)
        if ($bytes.Length -ge 3 -and $bytes[0] -eq 0xEF -and $bytes[1] -eq 0xBB -and $bytes[2] -eq 0xBF) {
            [System.IO.File]::WriteAllBytes($path, $bytes[3..($bytes.Length - 1)])
            Write-Host "  BOM removed: $_" -ForegroundColor Gray
        }
    }
}

Write-Host "Building with Maven (-U forces fresh dependency resolution)..." -ForegroundColor Yellow
Push-Location $ProjectRoot
mvn clean package -U
$code = $LASTEXITCODE
Pop-Location

if ($code -eq 0) {
    try {
        [xml]$pom = Get-Content -Path "$ProjectRoot\pom.xml"
        $Version = $pom.project.version
        
        if ([string]::IsNullOrWhiteSpace($Version)) { $Version = "1.0.0" }
    } catch {
        $Version = "1.0.0"
    }
    # ------------------------------------------

    $jarName = "BuildBattle-$Version.jar"
    $jar = Join-Path $ProjectRoot "target\$jarName"
    
    Write-Host ""
    Write-Host "====================================================" -ForegroundColor Green
    Write-Host " BUILD SUCCESSFUL!" -ForegroundColor Green
    Write-Host " Version detected: $Version" -ForegroundColor Gray
    Write-Host " Copy THIS JAR to your server plugins folder:" -ForegroundColor Yellow
    Write-Host " $jar" -ForegroundColor Cyan
    Write-Host " (NOT original-$jarName)" -ForegroundColor Red
    Write-Host "====================================================" -ForegroundColor Green
} else {
    Write-Host "Build FAILED. Check Maven output above." -ForegroundColor Red
}