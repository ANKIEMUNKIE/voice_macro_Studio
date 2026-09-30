@echo off
set "EMULATOR=C:\Users\DELL\AppData\Local\Android\Sdk\emulator\emulator.exe"
set "ADB=C:\Users\DELL\AppData\Local\Android\Sdk\platform-tools\adb.exe"

echo Starting VoiceMacro_API36...
start "Voice Macro Android 16" "%EMULATOR%" -avd VoiceMacro_API36
echo.
echo Wait for the Android home screen. First startup after a cold shutdown can take a few minutes.
timeout /t 15 /nobreak >nul
echo.
"%ADB%" devices -l
echo.
echo The emulator is ready for testing when emulator-5554 says: device
pause
