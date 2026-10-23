# Rebuilds the JVM truststore used by Gradle on machines where antivirus
# software intercepts HTTPS.
#
# Avast's Web Shield terminates TLS and re-signs responses with a CA it
# generates at install time. That CA is registered with Windows, so browsers,
# curl and Node accept it - but the JVM consults its own truststore and rejects
# every download with "PKIX path building failed". This script copies the JDK's
# cacerts and adds the interception CA to the copy, leaving the JDK untouched.
#
# Run it again if the antivirus is reinstalled (the CA is regenerated) or after
# switching JDKs. The path it writes to is referenced from gradle.properties.

param(
    [string]$JdkHome = "$env:ProgramFiles\Android\Android Studio\jbr",
    [string]$CertPath = "$env:ProgramData\Avast Software\Avast\wscert.pem",
    [string]$OutFile = "$env:USERPROFILE\.gradle\avast-jdk-truststore.jks"
)

$ErrorActionPreference = "Stop"

if (-not (Test-Path "$JdkHome\lib\security\cacerts")) {
    throw "No cacerts under $JdkHome - pass -JdkHome explicitly."
}
if (-not (Test-Path $CertPath)) {
    Write-Host "No interception certificate at $CertPath."
    Write-Host "If HTTPS is not being intercepted, drop the -Djavax.net.ssl.* flags from gradle.properties instead."
    exit 1
}

Copy-Item "$JdkHome\lib\security\cacerts" $OutFile -Force

& "$JdkHome\bin\keytool.exe" -importcert -noprompt -trustcacerts `
    -alias avast-web-shield `
    -file $CertPath `
    -keystore $OutFile `
    -storepass changeit

Write-Host "Wrote $OutFile"
