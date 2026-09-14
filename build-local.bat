@echo off
setlocal
set "JAVA_HOME=E:\Program Files\Android\Android Studio\jbr"
set "ANDROID_HOME=E:\AndroidSDK"
set "GRADLE_USER_HOME=E:\Program Files\Android\GradleUserHome"
call "%~dp0gradlew.bat" assembleDebug %*
exit /b %errorlevel%
