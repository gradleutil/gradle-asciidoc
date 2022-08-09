@echo off
SET "DIR=%~dp0%"
set JRE_ZIP=%DIR%jre.zip
set JRE_DIR=%DIR%jre

if "%OS%"=="Windows_NT" @setlocal
if "%OS%"=="WINNT" @setlocal

set _JAVACMD=%JAVACMD%
if "%JAVA_HOME%" == "" goto noJavaHome
if not exist "%JAVA_HOME%\bin\java.exe" goto noJavaHome
if "%_JAVACMD%" == "" set _JAVACMD=%JAVA_HOME%\bin\java.exe
goto hasjava
:noJavaHome
if exist "%JRE_DIR%\bin\java.exe" (
  set JAVA_HOME=%JRE_DIR%
  set _JAVACMD=%JRE_DIR%\bin\java.exe
  goto hasjava
)

if "%_JAVACMD%" == "" (
  for %%X in (java.exe) do (set FOUND=%%~$PATH:X)
  if not defined FOUND goto nojava
)
:noJava
set downloadJRE=
set /p downloadJRE=  Download JRE? [Y]/n:
if /I '%downloadJRE%'=='n' goto hasjava
reg Query "HKLM\Hardware\Description\System\CentralProcessor\0" | find /i "x86" > NUL && set BITTYPE=32 || set BITTYPE=64
if %BITTYPE%==32 echo This is a 32bit operating system
if %BITTYPE%==64 echo This is a 64bit operating system
mkdir "%JRE_DIR%"
set JRE_URL="https://api.adoptopenjdk.net/v2/info/releases/openjdk"
echo Downloading with powershell: %JRE_URL%
echo   to %JRE_ZIP%
powershell.exe -command "$webclient = New-Object System.Net.WebClient; $url = \"%JRE_URL%\"; $file = \"%JRE_ZIP%\"; $webclient.DownloadFile($url,$file);"
echo Expanding with powershell to: %JRE_DIR%
powershell -command "$shell_app=new-object -com shell.application; $zip_file = $shell_app.namespace(\"%JRE_ZIP%\"); if (!(Test-Path \"%JRE_DIR%\")) { mkdir %JRE_DIR% }; $destination = $shell_app.namespace(\"%JRE_DIR%\"); $destination.Copyhere($zip_file.items())"
del %JRE_ZIP%
set JAVA_HOME=%JRE_DIR%
set _JAVACMD=%JRE_DIR%\bin\java.exe