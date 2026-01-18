$ErrorActionPreference = "Stop"

$appHome = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $appHome

$logDir  = Join-Path $appHome "logs"
$logFile = Join-Path $logDir "install.log"
if (!(Test-Path $logDir)) { New-Item -ItemType Directory -Path $logDir | Out-Null }

Set-Content -Path $logFile -Value ("==== install.ps1 START " + (Get-Date) + " ====") -Encoding UTF8

function Write-Log([string]$msg) {
    $ts = Get-Date -Format "yyyy-MM-dd HH:mm:ss.fff"
    Add-Content -Path $logFile -Value "[$ts] $msg" -Encoding UTF8
}

function Show-Error([string]$msg) {
    Add-Type -AssemblyName System.Windows.Forms | Out-Null
    [System.Windows.Forms.MessageBox]::Show(
        $msg,
        "Template Generator",
        [System.Windows.Forms.MessageBoxButtons]::OK,
        [System.Windows.Forms.MessageBoxIcon]::Error
    ) | Out-Null
}

try {
    Write-Log "Install started"
    Write-Log ("AppHome: " + $appHome)

    $gradlew = Join-Path $appHome "gradlew.bat"
    Write-Log ("Gradle wrapper path: " + $gradlew)

    if (!(Test-Path $gradlew)) {
        Write-Log "ERROR: gradlew.bat not found"
        Show-Error "gradlew.bat not found рядом с run.bat. Установка невозможна."
        exit 2
    }

    Add-Type -AssemblyName System.Windows.Forms | Out-Null
    Add-Type -AssemblyName System.Drawing | Out-Null

    $form = New-Object System.Windows.Forms.Form
    $form.Text = "Template Generator - Setup"
    $form.FormBorderStyle = "FixedDialog"
    $form.MaximizeBox = $false
    $form.MinimizeBox = $false
    $form.StartPosition = "CenterScreen"
    $form.ClientSize = New-Object System.Drawing.Size(460, 130)
    $form.TopMost = $true

    $label = New-Object System.Windows.Forms.Label
    $label.Location = New-Object System.Drawing.Point(12, 12)
    $label.Size = New-Object System.Drawing.Size(430, 44)
    $label.Text = "Installing application (first run).`r`nPlease wait..."
    $form.Controls.Add($label)

    $bar = New-Object System.Windows.Forms.ProgressBar
    $bar.Location = New-Object System.Drawing.Point(12, 70)
    $bar.Size = New-Object System.Drawing.Size(430, 18)
    $bar.Style = "Marquee"
    $bar.MarqueeAnimationSpeed = 30
    $form.Controls.Add($bar)

    $outTmp = Join-Path $env:TEMP ("tg-gradle-out-" + [Guid]::NewGuid().ToString("N") + ".log")
    $errTmp = Join-Path $env:TEMP ("tg-gradle-err-" + [Guid]::NewGuid().ToString("N") + ".log")

    Write-Log ("Starting Gradle: " + $gradlew + " clean bootJar")
    Write-Log ("Gradle stdout: " + $outTmp)
    Write-Log ("Gradle stderr: " + $errTmp)

    $proc = Start-Process -FilePath $gradlew -ArgumentList "clean","bootJar" -WorkingDirectory $appHome `
        -NoNewWindow -PassThru -RedirectStandardOutput $outTmp -RedirectStandardError $errTmp

    $form.Tag = 1

    $timer = New-Object System.Windows.Forms.Timer
    $timer.Interval = 400
    $timer.Add_Tick({
        if ($proc.HasExited) {
            $timer.Stop()
            $form.Tag = $proc.ExitCode
            $form.Close()
        }
    })
    $timer.Start()

    [void]$form.ShowDialog()

    $exitCode = [int]$form.Tag
    Write-Log ("Gradle exit code: " + $exitCode)

    if (Test-Path $outTmp) { Get-Content $outTmp | ForEach-Object { Write-Log $_ } }
    if (Test-Path $errTmp) { Get-Content $errTmp | ForEach-Object { Write-Log ("ERR: " + $_) } }

    Remove-Item $outTmp -ErrorAction SilentlyContinue
    Remove-Item $errTmp -ErrorAction SilentlyContinue

    if ($exitCode -ne 0) {
        Show-Error "Installation failed. See logs\install.log"
        exit 4
    }

    $libs = Join-Path $appHome "build\libs"
    if (!(Test-Path $libs)) {
        Show-Error "Installation failed. See logs\install.log"
        exit 5
    }

    $jar = Get-ChildItem -Path $libs -Filter *.jar | Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if ($null -eq $jar) {
        Show-Error "Installation failed. See logs\install.log"
        exit 5
    }

    $targetJar = Join-Path $appHome "template-generator.jar"
    Copy-Item $jar.FullName $targetJar -Force
    Write-Log ("Copied template-generator.jar to: " + $targetJar)

    [System.Windows.Forms.MessageBox]::Show(
        "Installation completed. The application will now start.",
        "Template Generator"
    ) | Out-Null
    exit 0
}
catch {
    try {
        Write-Log ("FATAL: " + $_.Exception.GetType().FullName)
        Write-Log ("FATAL: " + $_.Exception.Message)
        Write-Log ($_.ScriptStackTrace)
    } catch { }

    Show-Error "Installation failed. See logs\install.log"
    exit 1
}
