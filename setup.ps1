# ============================================================
# BuildBattle Plugin - Setup Script
# Run this from INSIDE the buildbattle/ folder:
#   cd path\to\buildbattle
#   .\setup.ps1
# Requirements: JDK 17+, Maven 3.8+ on PATH
# ============================================================

$ErrorActionPreference = "Stop"

# UTF-8 without BOM — PowerShell's default UTF8 adds a BOM which breaks javac
$utf8NoBom = New-Object System.Text.UTF8Encoding $false

# Script must live next to pom.xml
$ProjectRoot = $PSScriptRoot
if (-not $ProjectRoot) { $ProjectRoot = (Get-Location).Path }
Write-Host "Project root: $ProjectRoot" -ForegroundColor Cyan

if (-not (Test-Path "$ProjectRoot\pom.xml")) {
    Write-Host "ERROR: pom.xml not found. Run this script from inside the buildbattle/ folder." -ForegroundColor Red
    exit 1
}

# Re-write pom.xml clean (no BOM)
$pom = @'
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <groupId>dev.onder1e</groupId>
    <artifactId>BuildBattle</artifactId>
    <version>1.0.0</version>
    <packaging>jar</packaging>
    <name>BuildBattle</name>
    <properties>
        <java.version>17</java.version>
        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
    </properties>
    <repositories>
        <repository>
            <id>papermc</id>
            <url>https://repo.papermc.io/repository/maven-public/</url>
        </repository>
        <repository>
            <id>enginehub</id>
            <url>https://maven.enginehub.org/repo/</url>
        </repository>
        <repository>
            <id>dmulloy2-repo</id>
            <url>https://repo.dmulloy2.net/repository/public/</url>
        </repository>
    </repositories>
    <dependencies>
        <dependency>
            <groupId>io.papermc.paper</groupId>
            <artifactId>paper-api</artifactId>
            <version>1.20.4-R0.1-SNAPSHOT</version>
            <scope>provided</scope>
        </dependency>
        <dependency>
            <groupId>com.sk89q.worldedit</groupId>
            <artifactId>worldedit-bukkit</artifactId>
            <version>7.3.0-SNAPSHOT</version>
            <scope>provided</scope>
        </dependency>
        <dependency>
            <groupId>com.comphenix.protocol</groupId>
            <artifactId>ProtocolLib</artifactId>
            <version>5.3.0</version>
            <scope>provided</scope>
        </dependency>
    </dependencies>
    <build>
        <plugins>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-compiler-plugin</artifactId>
                <version>3.11.0</version>
                <configuration>
                    <source>${java.version}</source>
                    <target>${java.version}</target>
                </configuration>
            </plugin>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-shade-plugin</artifactId>
                <version>3.5.0</version>
                <executions>
                    <execution>
                        <phase>package</phase>
                        <goals><goal>shade</goal></goals>
                        <configuration>
                            <createDependencyReducedPom>false</createDependencyReducedPom>
                        </configuration>
                    </execution>
                </executions>
            </plugin>
        </plugins>
        <resources>
            <resource>
                <directory>src/main/resources</directory>
                <filtering>true</filtering>
            </resource>
        </resources>
    </build>
</project>
'@
[System.IO.File]::WriteAllText("$ProjectRoot\pom.xml", $pom, $utf8NoBom)
Write-Host "pom.xml written (UTF-8 NoBOM)." -ForegroundColor Green

# Strip BOM from every Java source and resource file
Write-Host "Stripping BOM from all source files..." -ForegroundColor Yellow
Get-ChildItem -Path "$ProjectRoot\src" -Recurse -File | ForEach-Object {
    $bytes = [System.IO.File]::ReadAllBytes($_.FullName)
    # BOM is EF BB BF at start of file
    if ($bytes.Length -ge 3 -and $bytes[0] -eq 0xEF -and $bytes[1] -eq 0xBB -and $bytes[2] -eq 0xBF) {
        $noBom = $bytes[3..($bytes.Length - 1)]
        [System.IO.File]::WriteAllBytes($_.FullName, $noBom)
        Write-Host "  BOM removed: $($_.Name)" -ForegroundColor Gray
    }
}

Write-Host "Building with Maven (-U to force refresh)..." -ForegroundColor Yellow
Push-Location $ProjectRoot
mvn clean package -U
$code = $LASTEXITCODE
Pop-Location

if ($code -eq 0) {
    Write-Host ""
    Write-Host "====================================================" -ForegroundColor Green
    Write-Host " BUILD SUCCESSFUL!" -ForegroundColor Green
    Write-Host " JAR: $ProjectRoot\target\BuildBattle-1.0.0.jar" -ForegroundColor Green
    Write-Host "====================================================" -ForegroundColor Green
    Write-Host "Copy the JAR + WorldEdit + ProtocolLib into plugins\" -ForegroundColor Yellow
} else {
    Write-Host "Build FAILED. Check Maven output above." -ForegroundColor Red
}
