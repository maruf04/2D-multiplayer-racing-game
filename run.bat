@echo off
rem ============================================================================
rem  Net Racer - run script (Windows)
rem  Runs the compiled bytecode in .\out. If Java 17+ is missing it is installed
rem  first (via winget). If the game is not compiled yet, it is compiled here.
rem
rem  Usage:
rem    run.bat                          launcher window (host or join a game)
rem    run.bat host [port] [name]       host and play straight away
rem    run.bat join ^<ip^> [port] [name]  join a friend's game
rem    run.bat server [port]            dedicated server, no window
rem ============================================================================
setlocal EnableExtensions EnableDelayedExpansion
pushd "%~dp0"
set MIN_JAVA=17

rem ---- 1. Make sure a JDK (java + javac) 17 or newer is available -----------
call :check_java
if "!JAVA_OK!"=="1" goto :java_ready

echo ^>^> Java %MIN_JAVA%+ not found. Installing Eclipse Temurin JDK 21...
where winget >nul 2>nul
if errorlevel 1 (
    echo !! winget is not available on this PC.
    echo    Please install JDK 21 manually from https://adoptium.net/temurin/releases/
    echo    then run this script again.
    goto :fail
)
winget install --id EclipseAdoptium.Temurin.21.JDK -e --silent --accept-package-agreements --accept-source-agreements
rem The new PATH is not visible to this window yet, so look for the JDK directly.
for /d %%D in ("%ProgramFiles%\Eclipse Adoptium\jdk-21*") do set "PATH=%%D\bin;!PATH!"
call :check_java
if not "!JAVA_OK!"=="1" (
    echo !! Java was installed but could not be found. Close this window and run run.bat again.
    goto :fail
)

:java_ready
for /f "tokens=*" %%V in ('java -version 2^>^&1 ^| findstr /i "version"') do echo ^>^> Using %%V

rem ---- 2. Compile if needed ---------------------------------------------------
if exist "out\racer\Main.class" goto :run
echo ^>^> Game is not compiled yet - compiling...
if exist out rmdir /s /q out
mkdir out
set SOURCES=
for %%F in (src\racer\*.java) do set SOURCES=!SOURCES! %%F
javac --release %MIN_JAVA% -encoding UTF-8 -d out !SOURCES!
if errorlevel 1 (
    echo !! Compilation failed.
    goto :fail
)
echo ^>^> Compiled successfully into .\out

rem ---- 3. Run (256 MB max heap, simple GC for low-end machines) -----------------
:run
set JVM_OPTS=-Xms64m -Xmx256m -XX:+UseSerialGC
if /i "%~1"=="server" set JVM_OPTS=-Xms32m -Xmx96m -XX:+UseSerialGC -Djava.awt.headless=true
java %JVM_OPTS% -cp out racer.Main %*
popd
endlocal
exit /b %errorlevel%

:fail
pause
popd
endlocal
exit /b 1

rem ---- Sets JAVA_OK=1 when both java and javac exist and are version 17+ -----
:check_java
set JAVA_OK=0
where java >nul 2>nul || exit /b 0
where javac >nul 2>nul || exit /b 0
set JV=
for /f "tokens=2" %%A in ('javac -version 2^>^&1') do if not defined JV set JV=%%A
if not defined JV exit /b 0
for /f "tokens=1,2 delims=." %%A in ("!JV!") do (
    set MAJ=%%A
    if "%%A"=="1" set MAJ=%%B
)
if !MAJ! GEQ %MIN_JAVA% set JAVA_OK=1
exit /b 0
