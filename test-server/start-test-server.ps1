$serverRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location -LiteralPath $serverRoot

if (-not (Test-Path -LiteralPath '.\paper.jar')) {
    Write-Error 'paper.jar fehlt. Baue das Projekt erneut oder lade Paper 26.2 herunter.'
    exit 1
}

$eula = Get-Content -LiteralPath '.\eula.txt' -Raw -ErrorAction SilentlyContinue
if ($eula -notmatch '(?m)^eula=true\s*$') {
    Write-Host 'Bitte lies die Minecraft-EULA und setze eula=true in test-server/eula.txt, bevor du den Testserver startest.' -ForegroundColor Yellow
    exit 1
}

& java -Xms1G -Xmx2G -jar '.\paper.jar' --nogui

