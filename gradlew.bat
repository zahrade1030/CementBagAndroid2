@echo off
set DIR=%~dp0
if "%DIR%"=="" set DIR=.
set APP_BASE_NAME=%~n0
set APP_HOME=%DIR%
set JAVA_EXE=java.exe
"%JAVA_EXE%" -classpath "%APP_HOME%\gradle\wrapper\gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain %*