$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$privateDirectory = Join-Path $projectRoot 'publishing/private'
[IO.Directory]::CreateDirectory($privateDirectory) | Out-Null
$resolvedPrivate = (Resolve-Path $privateDirectory).Path
if ($resolvedPrivate -ne (Join-Path $projectRoot 'publishing/private')) {
    throw 'Unexpected private directory; refusing to change permissions.'
}
$keyPath = Join-Path $resolvedPrivate 'ruotu-release.p12'
$propertiesPath = Join-Path $resolvedPrivate 'keystore.properties'
if ((Test-Path -LiteralPath $keyPath) -or (Test-Path -LiteralPath $propertiesPath)) {
    throw 'Existing release credentials found. Do not overwrite or rotate the signing key.'
}

# Only this Windows account and SYSTEM can read the credentials.
$currentSid = [Security.Principal.WindowsIdentity]::GetCurrent().User
$systemSid = [Security.Principal.SecurityIdentifier]::new('S-1-5-18')
$acl = [Security.AccessControl.DirectorySecurity]::new()
$acl.SetOwner($currentSid)
$acl.SetAccessRuleProtection($true, $false)
foreach ($sid in @($currentSid, $systemSid)) {
    $acl.AddAccessRule([Security.AccessControl.FileSystemAccessRule]::new(
        $sid, 'FullControl', 'ContainerInherit,ObjectInherit', 'None', 'Allow'))
}
Set-Acl -LiteralPath $resolvedPrivate -AclObject $acl

$randomBytes = [byte[]]::new(32)
$random = [Security.Cryptography.RandomNumberGenerator]::Create()
try { $random.GetBytes($randomBytes) } finally { $random.Dispose() }
$password = [Convert]::ToHexString($randomBytes)
$keytool = Join-Path $projectRoot 'toolchain/jdk/home/bin/keytool.exe'
$env:RUOTU_SIGNING_STORE_PASS = $password
try {
    & $keytool -genkeypair -alias ruotu -keystore $keyPath -storetype PKCS12 `
        -keyalg RSA -keysize 3072 -sigalg SHA256withRSA -validity 10000 `
        -dname 'CN=Ruotu' -storepass:env RUOTU_SIGNING_STORE_PASS -keypass:env RUOTU_SIGNING_STORE_PASS -noprompt
    if ($LASTEXITCODE -ne 0) { throw 'Signing key generation failed.' }
    $lines = @(
        'storeFile=publishing/private/ruotu-release.p12',
        "storePassword=$password", 'keyAlias=ruotu', "keyPassword=$password"
    )
    [IO.File]::WriteAllLines($propertiesPath, $lines, [Text.UTF8Encoding]::new($false))
    $certificate = Join-Path $projectRoot 'publishing/vivo/ruotu-signing-certificate.pem'
    & $keytool -exportcert -rfc -alias ruotu -keystore $keyPath -storepass:env RUOTU_SIGNING_STORE_PASS -file $certificate
    if ($LASTEXITCODE -ne 0) { throw 'Public certificate export failed.' }
    & $keytool -list -v -alias ruotu -keystore $keyPath -storepass:env RUOTU_SIGNING_STORE_PASS
    if ($LASTEXITCODE -ne 0) { throw 'Certificate inspection failed.' }
} finally {
    Remove-Item Env:RUOTU_SIGNING_STORE_PASS -ErrorAction SilentlyContinue
    $password = $null
    [Array]::Clear($randomBytes, 0, $randomBytes.Length)
}
Write-Output 'Release key generated; credentials are private. Public certificate contains no private key.'
