# Compila el APK de distribución (firmado) y lo copia a la carpeta salida\.
# Uso: clic derecho > "Ejecutar con PowerShell", o desde una terminal:  .\compilar.ps1
$ErrorActionPreference = "Stop"
$env:JAVA_HOME = "$env:LOCALAPPDATA\Programs\jdk-17.0.20.1+1"
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
$gradle = "$env:LOCALAPPDATA\Android\gradle-8.9\bin\gradle.bat"

Set-Location $PSScriptRoot
& $gradle --no-daemon assembleRelease
if ($LASTEXITCODE -ne 0) { throw "La compilación falló." }

New-Item -ItemType Directory -Force -Path "$PSScriptRoot\salida" | Out-Null
Copy-Item "$PSScriptRoot\app\build\outputs\apk\release\app-release.apk" "$PSScriptRoot\salida\ImagenesAPdf.apk" -Force
Write-Host "APK listo en: $PSScriptRoot\salida\ImagenesAPdf.apk"
