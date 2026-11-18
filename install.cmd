@echo off
rem Loji Notes - 更新已安装的插件（IDEA 运行时 jar 被占用，所以先等它退出）
rem 用法：关掉 IDEA 后双击本文件。
setlocal
set "SRC=%~dp0build\dist\loji-notes\lib\loji-notes.jar"
set "DEST=%APPDATA%\JetBrains\IntelliJIdea2026.2\plugins\loji-notes\lib\loji-notes.jar"

if not exist "%SRC%" (
  echo [x] 找不到 %SRC%
  echo     先在仓库目录跑一次 build.cmd
  pause
  exit /b 1
)

echo Waiting for IntelliJ IDEA to exit...
:wait
tasklist /FI "IMAGENAME eq idea64.exe" 2>nul | find /I "idea64.exe" >nul
if not errorlevel 1 (
  timeout /t 2 /nobreak >nul
  goto wait
)

copy /Y "%SRC%" "%DEST%" >nul
if errorlevel 1 (
  echo [x] copy failed - the jar is still locked, make sure IDEA is fully closed.
  pause
  exit /b 1
)

echo.
echo [ok] plugin updated:
echo      %DEST%
echo.
echo Start IDEA, open the sample folder, then open
echo   sample\src\main\java\com\demo\pay\SettlementService.java
echo You should see three comment cards.
pause
