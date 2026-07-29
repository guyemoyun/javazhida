$ErrorActionPreference = 'Stop'

Set-Location $PSScriptRoot

$env:JAVA_HOME = 'D:\jdk'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
$env:DEEPSEEK_MODEL = 'deepseek-chat'

Write-Host ''
Write-Host 'Zhida DeepSeek secure launcher' -ForegroundColor Cyan
Write-Host 'Paste a newly generated DeepSeek API key. Input is hidden and is not saved to a file.'

$secureKey = Read-Host 'DeepSeek API key' -AsSecureString
$env:DEEPSEEK_API_KEY = [Net.NetworkCredential]::new('', $secureKey).Password
Remove-Variable secureKey

if ([string]::IsNullOrWhiteSpace($env:DEEPSEEK_API_KEY)) {
    throw 'The API key cannot be empty.'
}

Write-Host ''
Write-Host 'Starting http://localhost:8081' -ForegroundColor Green
Write-Host 'Refresh the browser after Started ZhidaApplication appears. Press Ctrl+C to stop.'
Write-Host ''

try {
    & "$PSScriptRoot\mvnw.cmd" spring-boot:run '-Dspring-boot.run.arguments=--server.port=8081'
}
finally {
    Remove-Item Env:DEEPSEEK_API_KEY -ErrorAction SilentlyContinue
}
