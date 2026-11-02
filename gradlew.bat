@echo off
setlocal
set "APP_HOME=%~dp0"
set "WRAPPER_JAR=%APP_HOME%gradle\wrapper\gradle-wrapper.jar"
set "JAVA_EXE=java.exe"

rem The bundled wrapper JAR in some source archives is only a placeholder.
rem Detect that case and bootstrap the pinned Gradle distribution instead.
powershell -NoProfile -ExecutionPolicy Bypass -Command "$j=[IO.Compression.ZipFile]::OpenRead('%WRAPPER_JAR%'); try { $ok=$null -ne $j.GetEntry('org/gradle/wrapper/GradleWrapperMain.class'); if ($ok) { exit 0 } else { exit 1 } } catch { exit 1 } finally { if ($j) { $j.Dispose() } }"
if not errorlevel 1 goto use_wrapper

where gradle >nul 2>nul
if not errorlevel 1 (
  gradle %*
  exit /b %ERRORLEVEL%
)

set "DIST_ROOT=%USERPROFILE%\.gradle\wrapper\dists\anivault-bootstrap\gradle-8.7"
set "GRADLE_EXE=%DIST_ROOT%\gradle-8.7\bin\gradle.bat"
if exist "%GRADLE_EXE%" goto run_bootstrapped

if not exist "%DIST_ROOT%" mkdir "%DIST_ROOT%"
powershell -NoProfile -ExecutionPolicy Bypass -Command "$ErrorActionPreference='Stop'; $zip=Join-Path '%DIST_ROOT%' 'gradle-8.7-bin.zip'; Invoke-WebRequest -Uri 'https://services.gradle.org/distributions/gradle-8.7-bin.zip' -OutFile $zip; Expand-Archive -LiteralPath $zip -DestinationPath '%DIST_ROOT%' -Force; Remove-Item $zip"
if errorlevel 1 exit /b %ERRORLEVEL%
if not exist "%GRADLE_EXE%" (
  echo ERROR: Gradle 8.7 bootstrap failed.
  exit /b 1
)

:run_bootstrapped
call "%GRADLE_EXE%" %*
exit /b %ERRORLEVEL%

:use_wrapper
"%JAVA_EXE%" -classpath "%WRAPPER_JAR%" org.gradle.wrapper.GradleWrapperMain %*
exit /b %ERRORLEVEL%
