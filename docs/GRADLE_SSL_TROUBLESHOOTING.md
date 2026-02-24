# Gradle SSL Certificate Validation Error - Troubleshooting Guide

## Problem
The error "unable to find valid certification path to requested target" appears during Gradle sync when trying to download dependencies from remote repositories.

## Root Causes

This error typically occurs when:
1. **Outdated Java certificates** - The JVM's certificate store is missing or outdated
2. **Network/Proxy issues** - Corporate firewall or proxy blocking HTTPS connections
3. **System certificate store issues** - Missing trusted root certificates on Windows
4. **SSL/TLS version mismatch** - Java version too old for modern TLS versions

## Solutions (in order of preference)

### Solution 1: Clear Gradle Cache and Resync (Try This First)
This is the quickest fix in many cases:

```powershell
# PowerShell command to delete gradle cache
Remove-Item -Path "$env:USERPROFILE\.gradle" -Recurse -Force -ErrorAction SilentlyContinue

# Then restart your IDE and sync Gradle again
```

### Solution 2: Update Gradle Wrapper
Use a newer version of Gradle with better SSL support:

```bash
./gradlew wrapper --gradle-version 9.0
# or
./gradlew wrapper --gradle-version 8.10
```

### Solution 3: Update Java/JDK
Ensure you're using a recent Java version (Java 11 or newer recommended):

```powershell
# Check Java version
java -version

# Update to Java 21 LTS if using older version
```

### Solution 4: Import Windows Certificates into Java
If you're behind a corporate proxy with SSL inspection:

```powershell
# This updates Java to use Windows' certificate store
# Windows 11 / Windows 10
$javaPath = "C:\Program Files\Android\Android Studio\jre\bin"
# or your JAVA_HOME path

# For Java 11+, you can use:
# Set JAVA_OPTS=-Dcom.sun.net.ssl.checkRevocation=false
```

### Solution 5: Modify gradle.properties
The following settings have been added to your `gradle.properties`:

```ini
# Already configured in your project:
org.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8 -Djavax.net.debug=ssl:handshake
org.gradle.internal.publish.checksums.insecure=true
```

If the above doesn't work, try adding SSL debugging to see the exact error:

```ini
org.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8 -Djavax.net.debug=ssl:handshake -Dcom.sun.net.ssl.checkRevocation=false
```

### Solution 6: Use HTTP Mirrors (Temporary)
As a temporary workaround (NOT recommended for production), you can modify `settings.gradle.kts` to use HTTP mirrors, but this is insecure.

### Solution 7: Disable Gradle Offline Mode Check
Make sure offline mode is not enabled:

```powershell
# In Android Studio:
# File > Settings > Build, Execution, Deployment > Gradle
# Uncheck "Offline mode"
```

### Solution 8: Check Network Configuration
```powershell
# Test connectivity to Maven Central
Test-NetConnection -ComputerName "repo.maven.apache.org" -Port 443

# Test connectivity to Google repositories
Test-NetConnection -ComputerName "dl.google.com" -Port 443
```

## Recommended Fix Sequence

1. **First**: Try Solution 1 (Clear Cache)
   ```powershell
   Remove-Item -Path "$env:USERPROFILE\.gradle" -Recurse -Force -ErrorAction SilentlyContinue
   ```

2. **Second**: Restart IDE and try syncing again

3. **Third**: If still failing, try Solution 2 (Update Gradle)
   ```bash
   ./gradlew wrapper --gradle-version 9.0
   ```

4. **Fourth**: Check if behind corporate proxy and disable revocation checking in gradle.properties:
   ```ini
   org.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8 -Dcom.sun.net.ssl.checkRevocation=false
   ```

5. **Fifth**: Update Java to latest LTS version (Java 21 or 17)

## Environment Variables to Check

```powershell
# Verify these are set correctly:
echo $env:JAVA_HOME
echo $env:ANDROID_HOME
echo $env:GRADLE_HOME

# If any are missing, set them:
[System.Environment]::SetEnvironmentVariable("JAVA_HOME", "C:\path\to\jdk", "User")
```

## Debugging: Enable SSL Handshake Logging

To see detailed SSL error messages:

1. Add to gradle.properties:
```ini
org.gradle.jvmargs=-Xmx2048m -Djavax.net.debug=ssl:handshake
```

2. Run Gradle sync and check the error logs

3. Look for specific error messages like:
   - "unable to find valid certification path" - Certificate issue
   - "Unsupported protocol" - TLS version issue
   - "Certificate verify failed" - Proxy/firewall issue

## If All Else Fails

Contact your system administrator if:
- You're behind a corporate firewall/proxy
- Your system has SSL inspection enabled
- Your certificates are outdated or revoked

They may need to:
- Whitelist the Maven Central and Google repositories
- Provide a custom certificate for SSL inspection
- Update system certificates

## Additional Resources

- [Gradle Official Documentation on SSL](https://docs.gradle.org/current/userguide/build_environment.html#gradle_properties_file)
- [Java SSL Debugging Guide](https://docs.oracle.com/en/java/javase/17/security-guide/java-secure-socket-extension-jsse-reference-guide.html)
- [Android Studio Gradle Sync Issues](https://developer.android.com/studio/troubleshoot)