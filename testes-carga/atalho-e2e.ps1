# Teste de ponta a ponta do atalho global: abre uma janelinha própria com uma caixa de texto, digita um
# texto com erros, aperta Ctrl+Shift+Espaço, aceita a prévia (se estiver ligada) e confere se o texto foi
# corrigido na própria caixa.
# Só envia teclas enquanto a janela de teste está em foco; se perder o foco, aborta sem digitar nada.
# Uso: powershell -ExecutionPolicy Bypass -File testes-carga\atalho-e2e.ps1   (com o Corretor Zap rodando)
Add-Type -AssemblyName System.Windows.Forms
Add-Type -Namespace CorretorZap -Name Janela -MemberDefinition @'
[DllImport("user32.dll")] public static extern System.IntPtr GetForegroundWindow();
[DllImport("user32.dll", CharSet=CharSet.Unicode)] public static extern int GetWindowText(System.IntPtr h, System.Text.StringBuilder s, int n);
[DllImport("user32.dll")] public static extern bool SetForegroundWindow(System.IntPtr h);
[DllImport("user32.dll")] public static extern bool BringWindowToTop(System.IntPtr h);
[DllImport("user32.dll")] public static extern int GetWindowThreadProcessId(System.IntPtr h, System.IntPtr pid);
[DllImport("user32.dll")] public static extern int GetWindowThreadProcessId(System.IntPtr h, out int pid);
[DllImport("user32.dll")] public static extern bool AttachThreadInput(int de, int para, bool anexar);
[DllImport("kernel32.dll")] public static extern int GetCurrentThreadId();
'@
# o Windows não deixa um processo em segundo plano tomar o foco; anexar a entrada à thread em foco libera
function TrazerParaFrente([System.IntPtr]$janela) {
    $outra = [CorretorZap.Janela]::GetWindowThreadProcessId([CorretorZap.Janela]::GetForegroundWindow(), [System.IntPtr]::Zero)
    $esta = [CorretorZap.Janela]::GetCurrentThreadId()
    $anexou = ($outra -ne $esta) -and [CorretorZap.Janela]::AttachThreadInput($esta, $outra, $true)
    [void][CorretorZap.Janela]::BringWindowToTop($janela)
    [void][CorretorZap.Janela]::SetForegroundWindow($janela)
    if ($anexou) { [void][CorretorZap.Janela]::AttachThreadInput($esta, $outra, $false) }
}
# a prévia é uma janela do próprio Corretor Zap (o processo que escuta a porta 8765)
$processoDoApp = [int]((netstat -ano | Select-String ":8765 .*LISTENING" | Select-Object -First 1).ToString().Trim() -split "\s+")[-1]
function PreviaEmPrimeiroPlano {
    $dono = 0
    [void][CorretorZap.Janela]::GetWindowThreadProcessId([CorretorZap.Janela]::GetForegroundWindow(), [ref]$dono)
    return $dono -eq $processoDoApp
}

$clipboardDoUsuario = Get-Clipboard -Raw
$marcador = "corretor-zap-e2e-" + [guid]::NewGuid()
Set-Clipboard -Value $marcador

$entrada = "voce nao vai acreditar"
$esperado = "voc$([char]0xEA) n$([char]0xE3)o vai acreditar."   # "você não vai acreditar." sem depender da codificação do .ps1

$form = New-Object System.Windows.Forms.Form
$form.Text = "Corretor Zap - teste do atalho"
$form.Width = 520
$form.Height = 140
$form.TopMost = $true
$form.StartPosition = "CenterScreen"
$caixa = New-Object System.Windows.Forms.RichTextBox
$caixa.Dock = "Fill"
$caixa.Font = New-Object System.Drawing.Font("Segoe UI", 14)
$form.Controls.Add($caixa)

$script:passo = 0
$script:aceitouPrevia = $false
$script:resultado = $null
$script:erro = $null
$timer = New-Object System.Windows.Forms.Timer
$timer.Interval = 400

function Enviar([string]$teclas) {
    # precisa ser a janela em primeiro plano do Windows (é para ela que o SendKeys manda), não só a ativa do processo
    if ([CorretorZap.Janela]::GetForegroundWindow() -ne $form.Handle -or -not $caixa.Focused) {
        $script:erro = "a janela de teste perdeu o foco; nenhuma tecla foi enviada"
        $form.Close()
        return $false
    }
    [System.Windows.Forms.SendKeys]::SendWait($teclas)
    return $true
}

$timer.Add_Tick({
    $script:passo++
    switch ($script:passo) {
        1 { TrazerParaFrente $form.Handle; $form.Activate(); $caixa.Focus() | Out-Null }
        2 { Enviar $entrada | Out-Null }
        3 { Enviar "^+ " | Out-Null }          # Ctrl+Shift+Espaço
        { $_ -ge 4 -and $_ -le 10 } {
            # a prévia abre por cima; o Enter só é enviado se ela estiver mesmo em primeiro plano
            if (-not $script:aceitouPrevia -and (PreviaEmPrimeiroPlano)) {
                Start-Sleep -Milliseconds 600   # deixa a animação da prévia terminar
                [System.Windows.Forms.SendKeys]::SendWait("{ENTER}")
                $script:aceitouPrevia = $true
            }
        }
        14 { $script:resultado = $caixa.Text.Trim(); $form.Close() }
    }
})
$form.Add_Shown({ $timer.Start() })
[void]$form.ShowDialog()
$timer.Stop()
$clipboardDepois = Get-Clipboard -Raw
if ($clipboardDoUsuario) { Set-Clipboard -Value $clipboardDoUsuario }

Write-Output "digitado : $entrada"
if ($script:erro) { Write-Output "ABORTADO: $($script:erro)"; exit 2 }
Write-Output "na caixa : $($script:resultado)"
Write-Output "prévia   : $(if ($script:aceitouPrevia) { 'apareceu e foi aceita com Enter' } else { 'não apareceu (modo direto)' })"
$ok = $true
if ($script:resultado -eq $esperado) { Write-Output "OK: o atalho corrigiu o texto no app em foco" }
else { Write-Output "FALHOU: o texto nao foi corrigido"; $ok = $false }
if ("$clipboardDepois".Trim() -eq $marcador) { Write-Output "OK: a area de transferencia foi restaurada" }
else { Write-Output "FALHOU: a area de transferencia nao voltou ao conteudo anterior"; $ok = $false }
if ($ok) { exit 0 } else { exit 1 }
