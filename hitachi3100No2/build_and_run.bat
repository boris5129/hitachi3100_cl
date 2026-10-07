@echo off
chcp 65001 > nul
echo ========================================================
echo   Hitachi 3100 자동 분석기 제어 소프트웨어 빌드 및 실행
echo ========================================================

cd /d "%~dp0"

if not exist bin mkdir bin
if not exist data mkdir data

echo [1/2] Java 소스 컴파일 중 (Java 21 이상)...
dir /s /b src\*.java > sources.txt
javac -encoding UTF-8 -d bin @sources.txt

if %ERRORLEVEL% NEQ 0 (
    echo [오류] 컴파일 실패!
    del sources.txt
    pause
    exit /b %ERRORLEVEL%
)
del sources.txt

echo [2/2] 프로그램 실행 중...
rem lib 폴더에 jSerialComm-*.jar 를 넣으면 실제 RS-232C 포트(COMx)를 사용할 수 있습니다.
java -cp "bin;lib\*" com.hitachi3100.Main
