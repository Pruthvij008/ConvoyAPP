@echo off
REM Builds the Convoy debug APK from the command line.
REM
REM Uses JDK 11 because the Gradle daemon cannot establish its loopback
REM connection under JDK 17 in a sandboxed shell ("Unable to establish
REM loopback connection"). Android Studio builds the same project with
REM JDK 17 via org.gradle.java.home in gradle.properties; this script
REM overrides that for command-line builds only.
REM
REM The module targets Java 8 bytecode, so JDK 11 can compile it.

setlocal
set "JAVA_HOME=D:\Android\jdk-11"
set "ANDROID_HOME=D:\Android\sdk"
set "GRADLE_USER_HOME=D:\Android\gradle-home"

cd /d "%~dp0"
call "D:\Android\gradle-7.6.4\bin\gradle.bat" --no-daemon "-Dorg.gradle.java.home=D:\Android\jdk-11" :app:assembleDebug %*

echo.
echo APK output: %~dp0app\build\outputs\apk\debug\app-debug.apk
endlocal
