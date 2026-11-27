@rem Gradle startup script for Windows
@echo off
setlocal

set APP_HOME=%~dp0
set CLASSPATH=%APP_HOME%\gradle\wrapper\gradle-wrapper.jar

@rem Fallback: auto-locate an installed JDK if JAVA_HOME is unset and java is not on PATH.
if not defined JAVA_HOME (
  if exist "C:\Users\q1062\.jdks\openjdk-27.0.1.0.3\bin\java.exe" set "JAVA_HOME=C:\Users\q1062\.jdks\openjdk-27.0.1.0.3"
)
if not defined JAVA_HOME (
  if exist "C:\Users\q1062\AppData\Local\Temp\hello-server-jdk17\jdk-17.0.2\bin\java.exe" set "JAVA_HOME=C:\Users\q1062\AppData\Local\Temp\hello-server-jdk17\jdk-17.0.2"
)
if defined JAVA_HOME goto findJavaFromJavaHome
set JAVA_EXE=java.exe
%JAVA_EXE% -version >NUL 2>&1
if %ERRORLEVEL% EQU 0 goto execute

echo ERROR: JAVA_HOME is not set and no java command could be found in PATH. 1>&2
exit /b 1

:findJavaFromJavaHome
set JAVA_HOME=%JAVA_HOME:"=%
set JAVA_EXE=%JAVA_HOME%\bin\java.exe
if exist "%JAVA_EXE%" goto execute

echo ERROR: JAVA_HOME points to an invalid directory: %JAVA_HOME% 1>&2
exit /b 1

:execute
"%JAVA_EXE%" -classpath "%CLASSPATH%" org.gradle.wrapper.GradleWrapperMain %*
set GRADLE_EXIT_CODE=%ERRORLEVEL%
endlocal & exit /b %GRADLE_EXIT_CODE%
