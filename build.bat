@echo off
echo =========================================
echo Building NeoNvidium 1.21.1 Jar
echo =========================================
echo Cleaning project...
call gradlew.bat clean
if %ERRORLEVEL% neq 0 goto error

echo Building Mod Jar...
call gradlew.bat build
if %ERRORLEVEL% neq 0 goto error

echo =========================================
echo Build Succeeded! Jar is in build/libs/
echo =========================================
exit /b 0

:error
echo =========================================
echo Build Failed!
echo =========================================
exit /b 1
