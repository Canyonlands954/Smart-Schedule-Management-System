param(
    [uri]$BaseUrl='http://127.0.0.1:18865',
    [string]$Username='',
    [pscredential]$Credential,
    [switch]$PublicOnly,
    [switch]$TestModels
)
$ErrorActionPreference='Stop'
if($BaseUrl.Scheme -ne 'http' -or $BaseUrl.Host -notin @('127.0.0.1','localhost','[::1]','::1') -or $BaseUrl.AbsolutePath -ne '/' -or $BaseUrl.Query -or $BaseUrl.Fragment -or $BaseUrl.UserInfo){
    throw 'Use a local HTTP origin without a path, query, or credentials.'
}
$taskApi=$BaseUrl.GetLeftPart([System.UriPartial]::Authority)+'/api'
$taskSession=New-Object Microsoft.PowerShell.Commands.WebRequestSession
$taskResults=New-Object 'System.Collections.Generic.List[object]'
$taskSignedIn=$false

function Invoke-ApiCheck {
    param([string]$Path,[string]$Method='GET',[object]$Body=$null,[int]$Expected=200,[switch]$Authenticated)
    $taskRequest=@{Uri=$taskApi+$Path;Method=$Method;UseBasicParsing=$true;TimeoutSec=90;ErrorAction='Stop'}
    if($Authenticated){$taskRequest.WebSession=$taskSession}
    if($Method -ne 'GET'){
        $taskRequest.ContentType='application/json; charset=utf-8'
        $taskPayload=if($null -eq $Body){'{}'}else{ConvertTo-Json -InputObject $Body -Depth 12 -Compress}
        $taskRequest.Body=[Text.Encoding]::UTF8.GetBytes($taskPayload)
    }
    $taskResponse=$null
    try {
        $taskResponse=Invoke-WebRequest @taskRequest
        $taskStatus=[int]$taskResponse.StatusCode
    } catch {
        if($null -eq $_.Exception.Response){throw "Cannot reach $taskApi. Start the project first."}
        $taskStatus=[int]$_.Exception.Response.StatusCode
    }
    if($taskStatus -ne $Expected){throw "$Method $Path returned HTTP $taskStatus; expected $Expected."}
    [void]$taskResults.Add(@{method=$Method;path=$Path;status=$taskStatus})
    Write-Host "[PASS] $Method /api$Path -> $taskStatus"
    if($taskResponse -and $taskResponse.Content){return ($taskResponse.Content | ConvertFrom-Json)}
}

$taskHealth=Invoke-ApiCheck '/health'
if($taskHealth.ok -ne $true -or $taskHealth.architecture -ne 'spring-boot-postgresql'){throw 'Unexpected backend response.'}
$taskStatus=Invoke-ApiCheck '/auth/status'
$null=Invoke-ApiCheck '/state' -Expected 401
if($PublicOnly){Write-Host "Completed $($taskResults.Count) public checks. No account was used.";return}
if(-not $taskStatus.initialized){throw 'Create the first administrator in the local web page before authenticated checks.'}
if(-not $Credential){
    if(-not $Username){$Username=Read-Host 'Username'}
    $taskPassword=Read-Host 'Password' -AsSecureString
    $Credential=New-Object System.Management.Automation.PSCredential($Username,$taskPassword)
}

try {
    $taskLoginBody=@{username=$Credential.UserName;password=$Credential.GetNetworkCredential().Password}
    try {$taskLogin=Invoke-ApiCheck '/auth/login' 'POST' $taskLoginBody -Authenticated} finally {$taskLoginBody.password='';$taskLoginBody=$null}
    $taskSignedIn=$true
    if(-not $taskLogin.user.id){throw 'Login response did not contain a user.'}
    $taskStatus=Invoke-ApiCheck '/auth/status' -Authenticated
    if($taskStatus.user.id -ne $taskLogin.user.id){throw 'Session cookie did not retain the authenticated user.'}
    $taskWorkspace=Invoke-ApiCheck '/state' -Authenticated
    $null=Invoke-ApiCheck '/scheduling-policy' -Authenticated
    $null=Invoke-ApiCheck '/conflict-report' -Authenticated
    $null=Invoke-ApiCheck '/knowledge/bases' -Authenticated
    $null=Invoke-ApiCheck '/knowledge/documents' -Authenticated
    if($taskLogin.user.role -eq 'admin'){
        $null=Invoke-ApiCheck '/metrics' -Authenticated
        $taskChat=Invoke-ApiCheck '/admin/model-config' -Authenticated
        $taskEmbedding=Invoke-ApiCheck '/admin/embedding-config' -Authenticated
        foreach($taskConfig in @($taskChat.config,$taskEmbedding.config)){
            if($null -eq $taskConfig -or $taskConfig.PSObject.Properties.Name -contains 'api_key' -or $taskConfig.PSObject.Properties.Name -contains 'encrypted_payload'){throw 'Model configuration response was not redacted.'}
        }
        if($TestModels){
            foreach($taskModel in @(@{path='/admin/model-config/test';config=$taskChat.config},@{path='/admin/embedding-config/test';config=$taskEmbedding.config})){
                if($taskModel.config.has_key -and $taskModel.config.enabled){
                    $null=Invoke-ApiCheck $taskModel.path 'POST' @{revision=$taskModel.config.revision} -Authenticated
                }else{Write-Host "[SKIP] $($taskModel.path): no enabled saved configuration."}
            }
        }
    }else{
        $null=Invoke-ApiCheck '/admin/model-config' -Expected 403 -Authenticated
        $null=Invoke-ApiCheck '/admin/embedding-config' -Expected 403 -Authenticated
        if($TestModels){throw 'Model connection tests require an administrator account.'}
    }
    Write-Host "Visible workspace: $(@($taskWorkspace.events).Count) events. Response bodies and credentials are not printed."
} finally {
    if($taskSignedIn){
        try {$null=Invoke-ApiCheck '/auth/logout' 'POST' @{} -Authenticated} catch {Write-Warning 'Logout failed; the test session will expire automatically.'}
    }
}
$null=Invoke-ApiCheck '/state' -Expected 401 -Authenticated
Write-Host "Completed $($taskResults.Count) API checks. No schedules, users, or documents were created or edited."
