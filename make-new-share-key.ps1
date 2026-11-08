# Makes a new release key for the share app (Range Logger) on this PC.
# The password is random and is only written to files in share-signing-key\ (never shown on screen).
$ErrorActionPreference = 'Continue'  # keytool writes progress to stderr; failures are checked by hand
Set-Location $PSScriptRoot
$keytool = 'D:\Users\micha\Android\jdk-17\bin\keytool.exe'
$dir = Join-Path $PSScriptRoot 'share-signing-key'
$stamp = Get-Date -Format 'yyyyMMdd-HHmm'

Write-Host ''
Write-Host ' Range Logger: making a new signing key'
Write-Host ' ======================================'
Write-Host ''

Write-Host ' 1/4  Moving the old key aside...'
$old = Join-Path $dir "old-$stamp"
New-Item -ItemType Directory -Force -Path $old | Out-Null
Get-ChildItem $dir -File | Move-Item -Destination $old
if (Test-Path (Join-Path $old 'README.txt')) { Copy-Item (Join-Path $old 'README.txt') $dir }
Write-Host "       OK: old key kept in share-signing-key\old-$stamp"

Write-Host ' 2/4  Making the new key...'
$bytes = New-Object byte[] 18
[System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
$pw = -join ($bytes | ForEach-Object { $_.ToString('x2') })
$jks = Join-Path $dir 'share-release.jks'
& $keytool -genkeypair -keystore $jks -storetype PKCS12 -alias rangelog-share -keyalg RSA -keysize 2048 `
    -validity 10000 -storepass $pw -keypass $pw -dname 'CN=Michael Laurio, O=Range Log, C=US' 2>&1 | Out-Null
if (-not (Test-Path $jks)) { Write-Host '       PROBLEM: keytool did not create the key'; exit 1 }
Write-Host '       OK'

Write-Host ' 3/4  Writing the settings files...'
$props = @"
# Release signing for Range Logger (the share app). Keep this file and share-release.jks private and backed up.
# Every update must be signed with this same key, or phones won't install it over the old one.
storeFile=share-signing-key/share-release.jks
storePassword=$pw
keyAlias=rangelog-share
keyPassword=$pw
"@
Set-Content -Path (Join-Path $dir 'keystore.properties') -Value $props -Encoding ascii
Copy-Item (Join-Path $dir 'keystore.properties') (Join-Path $PSScriptRoot 'keystore.properties') -Force
$b64 = [Convert]::ToBase64String([IO.File]::ReadAllBytes($jks))
Set-Content -Path (Join-Path $dir 'share-release.jks.base64.txt') -Value $b64 -Encoding ascii -NoNewline

$secrets = @"
Update these 3 secrets on GitHub (the 4th, SHARE_KEY_ALIAS, stays rangelog-share).
For each one: click the pencil next to its name, paste the value, click "Update secret".

SHARE_STORE_PASSWORD   (paste this line)
$pw

SHARE_KEY_PASSWORD     (same value)
$pw

SHARE_KEYSTORE_BASE64  (a long single line, it's in share-release.jks.base64.txt, which opens next)
"@
$secretsFile = Join-Path $dir 'github-secrets-to-paste.txt'
Set-Content -Path $secretsFile -Value $secrets -Encoding ascii

# Public fingerprint only (safe to share), so Claude can check GitHub signs with the new key.
$fp = (& $keytool -list -v -keystore $jks -storepass $pw 2>$null | Select-String 'SHA256:' | Select-Object -First 1).ToString().Trim()
Set-Content -Path (Join-Path $dir 'fingerprint.txt') -Value $fp -Encoding ascii
Write-Host "       OK: $fp"

Write-Host ' 4/4  Opening the values to paste into GitHub...'
Start-Process notepad.exe $secretsFile
Start-Process notepad.exe (Join-Path $dir 'share-release.jks.base64.txt')
Start-Process 'https://github.com/michaellaurio-oss/range-log/settings/secrets/actions'
Write-Host '       OK'
Write-Host ''
Write-Host ' All done. Paste the 3 values into GitHub, then close Notepad.'
Write-Host ' When the GitHub secrets are updated, delete github-secrets-to-paste.txt.'
Write-Host ''
