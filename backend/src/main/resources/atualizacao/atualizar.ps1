# Troca a pasta do Corretor Zap pela versão nova depois que o app fecha, e abre o app de novo.
# Quem chama é o próprio app (AtualizacaoService), que já baixou, conferiu e extraiu a versão nova em "$Atual.nova".
# Se qualquer passo falhar, a versão antiga volta para o lugar e é ela que abre.
param(
    [Parameter(Mandatory)][int]$Processo,
    [Parameter(Mandatory)][string]$Atual,
    [Parameter(Mandatory)][string]$Nova,
    [Parameter(Mandatory)][string]$Exe,
    [Parameter(Mandatory)][string]$Log,
    [string]$Argumentos = '',
    [switch]$NaoAbrir
)
$ErrorActionPreference = 'Stop'

function Registrar([string]$mensagem) {
    Add-Content -LiteralPath $Log -Value ('{0:yyyy-MM-ddTHH:mm:ss} {1}' -f (Get-Date), $mensagem) -Encoding UTF8
}

# o Windows (antivírus, indexador) às vezes segura um arquivo por alguns instantes depois que o processo fecha
function MoverComPaciencia([string]$de, [string]$para) {
    for ($i = 1; $i -le 20; $i++) {
        try {
            Move-Item -LiteralPath $de -Destination $para
            return
        } catch {
            if ($i -eq 20) { throw }
            Start-Sleep -Milliseconds 500
        }
    }
}

$antiga = "$Atual.antiga"
try {
    Registrar "esperando o app (processo $Processo) fechar"
    # Wait-Process desiste na hora quando o processo esperado é quem abriu este script; por isso o laço
    $limite = (Get-Date).AddSeconds(60)
    while ((Get-Process -Id $Processo -ErrorAction SilentlyContinue) -and (Get-Date) -lt $limite) {
        Start-Sleep -Milliseconds 200
    }
    if (Get-Process -Id $Processo -ErrorAction SilentlyContinue) { throw 'o app não fechou em 60 s' }
    Registrar 'o app fechou'
    if (-not (Test-Path -LiteralPath (Join-Path $Nova $Exe))) { throw "a versão nova não tem $Exe" }

    if (Test-Path -LiteralPath $antiga) { Remove-Item -LiteralPath $antiga -Recurse -Force }
    MoverComPaciencia $Atual $antiga
    try {
        MoverComPaciencia $Nova $Atual
    } catch {
        Registrar "não deu para colocar a versão nova, voltando a antiga: $_"
        MoverComPaciencia $antiga $Atual
        throw
    }
    Remove-Item -LiteralPath $antiga -Recurse -Force -ErrorAction SilentlyContinue
    Registrar 'versão nova instalada'
} catch {
    Registrar "ERRO: $_"
    if (Test-Path -LiteralPath $Nova) { Remove-Item -LiteralPath $Nova -Recurse -Force -ErrorAction SilentlyContinue }
}

if (-not $NaoAbrir) {
    $lista = @()
    if ($Argumentos -and (Test-Path -LiteralPath $Argumentos)) {
        $lista = @(Get-Content -LiteralPath $Argumentos -Encoding UTF8 | Where-Object { $_ } | ForEach-Object { '"' + $_.Replace('"', '\"') + '"' })
    }
    $exeFinal = Join-Path $Atual $Exe
    if ($lista.Count -gt 0) { Start-Process -FilePath $exeFinal -ArgumentList $lista } else { Start-Process -FilePath $exeFinal }
    Registrar 'app aberto de novo'
}
