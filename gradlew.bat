@echo off
setlocal
if not exist "%~dp0gradle\wrapper\gradle-wrapper.jar" (
    python "%~dp0scripts\bootstrap-wrapper.py"
    if errorlevel 1 exit /b 1
)
if defined JAVA_HOME (
    "%JAVA_HOME%\bin\java.exe" -Dorg.gradle.appname=gradlew -classpath "%~dp0gradle\wrapper\gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain %*
) else (
    java -Dorg.gradle.appname=gradlew -classpath "%~dp0gradle\wrapper\gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain %*
)
