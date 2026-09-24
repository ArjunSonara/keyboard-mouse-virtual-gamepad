@echo off
:: Batch file to uninstall Interception driver and restore physical mouse
echo ========================================================
echo     Uninstalling Interception Kernel Driver...
echo ========================================================
echo.

cd /d "%~dp0files\interception_driver\extracted\Interception\command line installer"

if not exist "install-interception.exe" (
    echo Error: install-interception.exe not found!
    pause
    exit /b 1
)

install-interception.exe /uninstall

echo.
echo ========================================================
echo [SUCCESS] Interception driver has been uninstalled!
echo.
echo IMPORTANT: You MUST restart your PC now for Windows to
echo restore your mouse driver (mouclass).
echo ========================================================
echo.
pause
