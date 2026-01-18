@echo off
setlocal EnableExtensions

cd /d "%~dp0"

set "APP_HOME=%~dp0"
set "JAR=%APP_HOME%template-generator.jar"
set "PS1=%APP_HOME%install.ps1"
set "LOG_DIR=%APP_HOME%logs"
set "INSTALL_LOG=%LOG_DIR%\install.log"

if not exist "%LOG_DIR%" mkdir "%LOG_DIR%" >nul 2>&1

set "PSMSG=powershell -NoProfile -ExecutionPolicy Bypass -Command"

if exist "%JAR%" goto :run

if not exist "%PS1%" (
  %PSMSG% "Add-Type -AssemblyName System.Windows.Forms; [System.Windows.Forms.MessageBox]::Show('install.ps1 not found next to run.bat','Template Generator')" >nul 2>&1
  exit /b 2
)

powershell -NoProfile -ExecutionPolicy Bypass -Command "Unblock-File -Path '%PS1%' -ErrorAction SilentlyContinue" >nul 2>&1

powershell -NoProfile -ExecutionPolicy Bypass -File "%PS1%"
set "EC=%ERRORLEVEL%"

if not "%EC%"=="0" (
  %PSMSG% "Add-Type -AssemblyName System.Windows.Forms; [System.Windows.Forms.MessageBox]::Show('Installation failed. The log will be opened.','Template Generator')" >nul 2>&1
  if exist "%INSTALL_LOG%" start "" notepad "%INSTALL_LOG%"
  exit /b %EC%
)

if not exist "%JAR%" (
  %PSMSG% "Add-Type -AssemblyName System.Windows.Forms; [System.Windows.Forms.MessageBox]::Show('Install finished but template-generator.jar not found. The log will be opened.','Template Generator')" >nul 2>&1
  if exist "%INSTALL_LOG%" start "" notepad "%INSTALL_LOG%"
  exit /b 5
)

goto :run

:run
where javaw >nul 2>&1
if errorlevel 1 (
  where java >nul 2>&1
  if errorlevel 1 (
    %PSMSG% "Add-Type -AssemblyName System.Windows.Forms; [System.Windows.Forms.MessageBox]::Show('Java not found. Install Java 17+','Template Generator')" >nul 2>&1
    exit /b 3
  )
  start "" /b java -Xms256m -Xmx1024m -Dfile.encoding=UTF-8 -jar "%JAR%"
) else (
  start "" /b javaw -Xms256m -Xmx1024m -Dfile.encoding=UTF-8 -jar "%JAR%"
)

exit /b 0
