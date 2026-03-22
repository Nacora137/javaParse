@rem Gradle wrapper script for Windows
@echo off
setlocal

set APP_HOME=%~dp0

set JAVA_EXE=java.exe
%JAVA_EXE% -version >NUL 2>&1
if %ERRORLEVEL% == 0 goto init
echo ERROR: JAVA_HOME is not set and no 'java' command could be found in your PATH.
goto fail

:init
set GRADLE_OPTS=%GRADLE_OPTS% "-Xmx64m" "-Xms64m"

:execute
"%JAVA_EXE%" %GRADLE_OPTS% -classpath "%APP_HOME%\gradle\wrapper\gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain %*

:fail
exit /b 1
