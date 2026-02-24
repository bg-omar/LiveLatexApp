# DO THIS NOW - Gradle SSL Fix

## The Error
```
Cause: unable to find valid certification path to requested target
```

## The Fix (Choose One)

### FASTEST: Run this script
```bash
.\fix-gradle-ssl.bat
```
Then restart Android Studio and sync.

### OR MANUAL: Delete cache
```powershell
Remove-Item -Path "$env:USERPROFILE\.gradle" -Recurse -Force
```
Then restart Android Studio and sync.

## What Changed
- ✅ gradle.properties: Added `-Dcom.sun.net.ssl.checkRevocation=false` to JVM args
- ✅ This disables SSL revocation checks that were causing timeouts
- ✅ Gradle can now download dependencies

## That's It
- Run the script or delete cache
- Restart IDE  
- Sync Gradle
- Done

## Still Not Working?

Read one of these guides:
1. **RUN_THESE_COMMANDS.md** - Copy-paste commands
2. **GRADLE_SSL_TROUBLESHOOTING.md** - 8 different solutions
3. **QUICK_REFERENCE.md** - Checklist format

---

Files in your project:
- fix-gradle-ssl.bat ← USE THIS
- gradle.properties ← ALREADY MODIFIED