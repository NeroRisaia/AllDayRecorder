@echo off
chcp 65001 >nul
rem AllDayRecorder 録音 → 日々のコンテキスト 文字起こし（faster-whisper / GPU）
set "VENV=%USERPROFILE%\LocalCode\venvs\adr_whisper"
set "PYTHONUTF8=1"
"%VENV%\Scripts\python.exe" "%~dp0transcribe_daily.py" %*
echo.
echo 終了コード %ERRORLEVEL%（0=全て成功 / 1=失敗あり：_報告\transcribe_log.txt を確認）
if "%~1"=="" pause
