@echo off
REM Gradle SSL Certificate Fix Script for Windows
REM This script helps resolve "unable to find valid certification path to requested target"

echo ===================================
echo Gradle SSL Certificate Fix
echo ===================================
echo.

REM Check if running as administrator
net session >nul 2>&1
if %errorLevel% == 0 (
    echo Running as Administrator
) else (
    echo NOTE: Some operations may require Administrator privileges
)

echo.
echo OPTION 1: Clear Gradle Cache (Quickest Fix)
echo ==========================================
echo This will delete the .gradle folder and force Gradle to re-download dependencies
echo.
set /p clearCache="Do you want to clear Gradle cache? (y/n): "
if /i "%clearCache%"=="y" (
    if exist "%USERPROFILE%\.gradle" (
        echo Deleting %USERPROFILE%\.gradle...
        rmdir /s /q "%USERPROFILE%\.gradle"
        if %errorLevel% equ 0 (
            echo SUCCESS: Gradle cache cleared
        ) else (
            echo WARNING: Could not delete all files, some may be in use
        )
    ) else (
        echo No .gradle folder found
    )
)

echo.
echo OPTION 2: Check Java Installation
echo ==================================
where java >nul 2>&1
if %errorLevel% equ 0 (
    echo Java found:
    java -version 2>&1
) else (
    echo WARNING: Java not found in PATH
)

echo.
echo OPTION 3: Check Network Connectivity
echo =====================================
echo Testing connectivity to Maven Central...
powershell -Command "Test-NetConnection -ComputerName 'repo.maven.apache.org' -Port 443 -WarningAction SilentlyContinue" >nul 2>&1
if %errorLevel% equ 0 (
    echo Maven Central: Reachable
) else (
    echo Maven Central: May not be reachable
)

echo.
echo Testing connectivity to Google Repositories...
powershell -Command "Test-NetConnection -ComputerName 'dl.google.com' -Port 443 -WarningAction SilentlyContinue" >nul 2>&1
if %errorLevel% equ 0 (
    echo Google Repositories: Reachable
) else (
    echo Google Repositories: May not be reachable
)

echo.
echo ===================================
echo Next Steps:
echo 1. Restart your IDE (Android Studio)
echo 2. Try Gradle Sync again
echo 3. If still failing, check the troubleshooting guide:
echo    GRADLE_SSL_TROUBLESHOOTING.md
echo ===================================
echo.
pause