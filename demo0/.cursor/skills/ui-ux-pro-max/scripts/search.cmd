@echo off
setlocal
chcp 65001 >nul 2>&1
cd /d "%~dp0"
python "%~dp0search.py" %*
exit /b %ERRORLEVEL%
