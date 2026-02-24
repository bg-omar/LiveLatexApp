# PowerShell script to fix Gradle SSL certificate issues on Windows
# This script helps resolve "unable to find valid certification path to requested target"

Write-Host "=== Gradle SSL Certificate Fix ===" -ForegroundColor Green
Write-Host ""

# Check if JAVA_HOME is set
$javaHome = $env:JAVA_HOME
if (-not $javaHome) {
    Write-Host "WARNING: JAVA_HOME environment variable is not set" -ForegroundColor Yellow
    Write-Host "Finding Java installation..."

    # Try to find Java from Android SDK
    $androidHome = $env:ANDROID_HOME
    if ($androidHome) {
        $javaHome = Join-Path $androidHome "jre"
        if (-not (Test-Path $javaHome)) {
            Write-Host "Java not found at $javaHome" -ForegroundColor Yellow
        }
    }
}

if ($javaHome) {
    Write-Host "Java Home: $javaHome" -ForegroundColor Cyan
} else {
    Write-Host "Could not locate Java installation" -ForegroundColor Red
    Write-Host "Please ensure JAVA_HOME is set to your JDK installation directory"
}

Write-Host ""
Write-Host "=== Recommended Solutions ===" -ForegroundColor Green
Write-Host ""

Write-Host "1. Clear Gradle Cache:" -ForegroundColor Yellow
Write-Host "   Run: gradle clean"
Write-Host "   Or manually delete: $env:USERPROFILE\.gradle"
Write-Host ""

Write-Host "2. Update Gradle Wrapper:" -ForegroundColor Yellow
Write-Host "   Run: gradle wrapper --gradle-version 9.0"
Write-Host ""

Write-Host "3. Check Network/Proxy:" -ForegroundColor Yellow
Write-Host "   - Verify your internet connection"
Write-Host "   - Check if you're behind a corporate proxy"
Write-Host "   - Try disabling VPN if you have one"
Write-Host ""

Write-Host "4. Import Certificates into Java (if needed):" -ForegroundColor Yellow
Write-Host "   Ensure your system's certificate store is up to date"
Write-Host ""

Write-Host "5. Try offline mode (if dependencies are cached):" -ForegroundColor Yellow
Write-Host "   Run: gradle build --offline"
Write-Host ""

Write-Host "=== Gradle Properties Configuration ===" -ForegroundColor Green
Write-Host "The gradle.properties file has been updated with SSL debugging settings"
Write-Host ""

Write-Host "=== Try This First ===" -ForegroundColor Cyan
Write-Host "1. Delete .gradle folder: Remove-Item -Path `$env:USERPROFILE\.gradle -Recurse -Force"
Write-Host "2. Restart IDE"
Write-Host "3. Sync Gradle again"
Write-Host ""