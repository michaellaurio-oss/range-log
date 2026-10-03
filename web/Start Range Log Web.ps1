# Serves the web folder at http://localhost:8080 and opens it in your browser.
# (Google sign-in needs a web address like this; double-clicking index.html won't work.)
# Close this window to stop it.
$root = $PSScriptRoot
$listener = New-Object System.Net.HttpListener
$listener.Prefixes.Add('http://localhost:8080/')
$listener.Start()
Start-Process 'http://localhost:8080/'
Write-Host 'Range Log web is running at http://localhost:8080  (close this window to stop)'
while ($listener.IsListening) {
    $ctx = $listener.GetContext()
    $path = $ctx.Request.Url.LocalPath.TrimStart('/')
    if ($path -eq '') { $path = 'index.html' }
    $file = Join-Path $root $path
    if ((Test-Path $file -PathType Leaf) -and ($file.StartsWith($root))) {
        $bytes = [IO.File]::ReadAllBytes($file)
        $ctx.Response.ContentType = if ($file.EndsWith('.html')) { 'text/html; charset=utf-8' } else { 'application/octet-stream' }
        $ctx.Response.OutputStream.Write($bytes, 0, $bytes.Length)
    } else {
        $ctx.Response.StatusCode = 404
    }
    $ctx.Response.Close()
}
