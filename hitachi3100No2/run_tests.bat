@echo off
chcp 65001 > nul
cd /d "%~dp0"
if not exist bin mkdir bin
dir /s /b src\*.java test\*.java > sources.txt
javac -encoding UTF-8 -d bin @sources.txt
if %ERRORLEVEL% NEQ 0 (
    del sources.txt
    pause
    exit /b %ERRORLEVEL%
)
del sources.txt
java -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -cp bin com.hitachi3100.ProtocolAndSystemTest
pause
