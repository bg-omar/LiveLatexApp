# Gradle SSL Fix - Commands to Run

## TL;DR - Quick Fix (Copy & Paste)

### For Windows PowerShell

**Option 1: Automatic Fix (Recommended)**
```powershell
# Run the helper script
.\fix-gradle-ssl.bat
```

**Option 2: Manual Fix**
```powershell
# Clear Gradle cache
Remove-Item -Path "$env:USERPROFILE\.gradle" -Recurse -Force -ErrorAction SilentlyContinue

# Verify removal
Get-ChildItem -Path "$env:USERPROFILE\.gradle" -ErrorAction SilentlyContinue

# If not removed, try:
rmdir /s /q "%USERPROFILE%\.gradle" 2>nul
```

**Option 3: Nuclear Option (If above doesn't work)**
```powershell
# Close all Java/Gradle processes first
Stop-Process -Name "java" -Force -ErrorAction SilentlyContinue
Stop-Process -Name "gradle" -Force -ErrorAction SilentlyContinue

# Wait a moment
Start-Sleep -Seconds 2

# Then clear cache
Remove-Item -Path "$env:USERPROFILE\.gradle" -Recurse -Force -ErrorAction SilentlyContinue

# Verify
Test-Path "$env:USERPROFILE\.gradle"
```

---

## Verification Commands

### Check if fix worked
```powershell
# After clearing cache and restarting IDE, check gradle.properties
Get-Content ".\gradle.properties" | Select-String "checkRevocation"

# Should output:
# org.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8 -Dcom.sun.net.ssl.checkRevocation=false
```

### Check Java version (should be 11+)
```bash
java -version
```

### Test network connectivity
```powershell
# Test Maven Central
Test-NetConnection -ComputerName "repo.maven.apache.org" -Port 443 -WarningAction SilentlyContinue

# Test Google repositories
Test-NetConnection -ComputerName "dl.google.com" -Port 443 -WarningAction SilentlyContinue

# Test Gradle Plugin Portal
Test-NetConnection -ComputerName "plugins.gradle.org" -Port 443 -WarningAction SilentlyContinue
```

---

## Step-by-Step Process

### Step 1: Stop All Services
```powershell
# Kill any running Gradle/Java processes
Get-Process java -ErrorAction SilentlyContinue | Stop-Process -Force -ErrorAction SilentlyContinue
Get-Process gradle -ErrorAction SilentlyContinue | Stop-Process -Force -ErrorAction SilentlyContinue
Get-Process "Android Studio" -ErrorAction SilentlyContinue | Stop-Process -Force -ErrorAction SilentlyContinue

# Wait
Start-Sleep -Seconds 2
```

### Step 2: Clear Gradle Cache
```powershell
# Delete the entire .gradle directory
$gradleDir = "$env:USERPROFILE\.gradle"
if (Test-Path $gradleDir) {
    Remove-Item -Path $gradleDir -Recurse -Force
    Write-Host "Gradle cache cleared"
} else {
    Write-Host "Gradle cache directory not found (already clean)"
}
```

### Step 3: Verify gradle.properties
```powershell
# Check if gradle.properties has been updated
$gradlePropFile = ".\gradle.properties"
$content = Get-Content $gradlePropFile
if ($content -match "checkRevocation") {
    Write-Host "✓ gradle.properties has SSL fix applied"
} else {
    Write-Host "✗ gradle.properties needs SSL fix"
}
```

### Step 4: Restart IDE
```powershell
# Close Android Studio
# Wait 10 seconds manually
# Reopen Android Studio
```

### Step 5: Sync Gradle
```bash
# In Android Studio:
# Click: Sync Now
# Or in terminal:
./gradlew clean
```

---

## If First Attempt Fails

### Try Alternative Configuration
```powershell
# Backup current
Copy-Item "gradle.properties" "gradle.properties.backup"

# Use alternative config
Copy-Item "gradle.properties.ssl-fix" "gradle.properties"

# Clear cache and retry
Remove-Item -Path "$env:USERPROFILE\.gradle" -Recurse -Force -ErrorAction SilentlyContinue
```

### Update Gradle Wrapper
```bash
# Use newer Gradle version
./gradlew wrapper --gradle-version 9.0

# Then
./gradlew clean

# And sync in IDE
```

### Update Java
Check if Java 11+ is installed. If not, update.
```bash
java -version
# Should show Java 11 or newer
```

---

## Debugging: Enable SSL Debug Logging

If you want to see what's happening with SSL:

**Temporary**: Add to gradle.properties jvmargs:
```ini
# Add this to see SSL handshake details:
-Djavax.net.debug=ssl:handshake
```

Full line would be:
```ini
org.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8 -Dcom.sun.net.ssl.checkRevocation=false -Djavax.net.debug=ssl:handshake
```

Then check the build output for detailed SSL error messages.

---

## Quick Diagnostic Script

Save this as `check-ssl.ps1` and run it:

```powershell
# Diagnostic script for SSL issues

Write-Host "=== SSL Certificate Diagnostics ===" -ForegroundColor Green
Write-Host ""

# Check Java
Write-Host "Java Version:" -ForegroundColor Yellow
java -version 2>&1

# Check gradle.properties
Write-Host ""
Write-Host "gradle.properties SSL config:" -ForegroundColor Yellow
Get-Content ".\gradle.properties" | Select-String "checkRevocation"

# Check network
Write-Host ""
Write-Host "Network Connectivity:" -ForegroundColor Yellow
$hosts = @(
    "repo.maven.apache.org",
    "dl.google.com",
    "plugins.gradle.org",
    "cdn.jsdelivr.net"
)

foreach ($host in $hosts) {
    $result = Test-NetConnection -ComputerName $host -Port 443 -WarningAction SilentlyContinue
    $status = if ($result.TcpTestSucceeded) { "✓ Reachable" } else { "✗ Not reachable" }
    Write-Host "$host: $status" -ForegroundColor Cyan
}

# Check .gradle directory
Write-Host ""
Write-Host "Gradle Cache Status:" -ForegroundColor Yellow
$cacheDir = "$env:USERPROFILE\.gradle"
if (Test-Path $cacheDir) {
    $size = (Get-ChildItem $cacheDir -Recurse | Measure-Object -Property Length -Sum).Sum / 1MB
    Write-Host "Cache size: $([Math]::Round($size, 2)) MB" -ForegroundColor Cyan
} else {
    Write-Host "Cache directory doesn't exist (clean)" -ForegroundColor Cyan
}

Write-Host ""
Write-Host "=== Done ===" -ForegroundColor Green
```

Run it:
```powershell
.\check-ssl.ps1
```

---

## Common Issues & Fixes

### "gradle command not found"
```powershell
# Use gradlew instead (it's in the project)
.\gradlew clean
```

### "Permission denied" when deleting .gradle
```powershell
# Run PowerShell as Administrator
# Then try clearing cache again
```

### Still getting SSL errors
```powershell
# Try more aggressive settings in gradle.properties:
# org.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8 -Dcom.sun.net.ssl.checkRevocation=false -Djdk.tls.client.protocols=TLSv1.2
```

---

## Getting Help

If none of these work:

1. Check **GRADLE_SSL_TROUBLESHOOTING.md**
2. Check **GRADLE_SSL_FIX_README.md**
3. Run diagnostic script above
4. Share the output with your team/admin
5. If behind corporate proxy, contact network admin

---

## Summary

| Command | What It Does |
|---------|-------------|
| `.\fix-gradle-ssl.bat` | Automated fix (recommended) |
| `Remove-Item -Path "$env:USERPROFILE\.gradle" -Recurse -Force` | Clear Gradle cache |
| `java -version` | Check Java version |
| `Test-NetConnection -ComputerName "repo.maven.apache.org" -Port 443` | Test network access |
| `.\check-ssl.ps1` | Run diagnostics |

**Start with**: `.\fix-gradle-ssl.bat` then restart IDE and sync!