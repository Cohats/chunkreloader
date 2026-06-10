@rem Gradle startup script for Windows
@echo off
setlocal enabledelayedexpansion

set DIRNAME=%~dp0
if "%DIRNAME%"=="" set DIRNAME=.
set APP_BASE_NAME=%~n0
set APP_HOME=%DIRNAME%

set WRAPPER_JAR=%APP_HOME%\gradle\wrapper\gradle-wrapper.jar
set WRAPPER_PROPS=%APP_HOME%\gradle\wrapper\gradle-wrapper.properties

set JAVA_HOME=C:\jdk21\PFiles64\Microsoft\jdk-21.0.7.6-hotspot
set JAVA_EXE=%JAVA_HOME%\bin\java.exe

if exist "%JAVA_EXE%" goto javaFound
echo ERROR: JAVA_HOME is not set and no 'java' command could be found.
echo.
echo Please set the JAVA_HOME variable in your environment to match the
echo location of your Java installation.
goto fail

:javaFound
cd /d "%DIRNAME%"
set CLASSPATH=%WRAPPER_JAR%
"%JAVA_EXE%" %DEFAULT_JVM_OPTS% %JAVA_OPTS% %GRADLE_OPTS% -classpath "%CLASSPATH%" org.gradle.wrapper.GradleWrapperMain %*
goto end

:fail
exit /b 1

:end
endlocal
