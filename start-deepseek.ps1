# Compatibility entry point. The unified launcher now handles every provider.
& "$PSScriptRoot\start.ps1" -Port 8081
exit $LASTEXITCODE
