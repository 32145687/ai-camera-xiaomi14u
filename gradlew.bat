@rem Gradle startup script for Windows
@if "%DEBUG%"=="" @echo off
set DEFAULT_JVM_OPTS="-Xmx64m" "-Xms64m"
set CLASSPATH=%~dp0\gradle\wrapper\gradle-wrapper.jar
java %DEFAULT_JVM_OPTS% %JAVA_OPTS% -classpath "%CLASSPATH%" org.gradle.wrapper.GradleWrapperMain %*
