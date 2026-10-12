# F3.2 only. No target/database/credential parameters: all resources are owned and ephemeral.
# Reuses the unchanged E2E server/config/flows directly (explicitly authorized).
# Does not invoke run.mjs, the legacy validator entrypoint, Compose or Flyway repair.
[CmdletBinding()]
param([switch]$TimestampOnly)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$repo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$runId = [Guid]::NewGuid().ToString('N')
$resource = "smartaudits-f3-harness-$runId"
$label = 'smartaudits.f3-harness'
$database = "f3_harness_$runId"
$volume = "$resource-data"
$tempParent = [IO.Path]::GetFullPath([IO.Path]::GetTempPath())
$tempRoot = Join-Path $tempParent $resource
$children = New-Object 'System.Collections.Generic.List[object]'
$ports = New-Object 'System.Collections.Generic.List[int]'
$secrets = New-Object 'System.Collections.Generic.List[string]'
$baseEnv = @{}
$allowed = @('PATH','SystemRoot','WINDIR','ComSpec','PATHEXT','TEMP','TMP','TMPDIR',
    'HOME','USERPROFILE','LOCALAPPDATA','APPDATA','ProgramData','ProgramFiles',
    'ProgramFiles(x86)','JAVA_HOME','MAVEN_HOME','M2_HOME')
foreach ($entry in [Environment]::GetEnvironmentVariables().GetEnumerator()) {
    if ($allowed -contains $entry.Key) { $baseEnv[$entry.Key] = $entry.Value }
}
$utf8 = New-Object Text.UTF8Encoding($false)
$dockerReady = $false
$containerRegistered = $false
$volumeRegistered = $false
$tempCreated = $false
$habitualBefore = $null
$failure = $null
$cleanupErrors = New-Object 'System.Collections.Generic.List[string]'
$backend = $null
$newBackend = $null
$dbEnv = @{}
$migrationHashes = @{}

function Assert-That([bool]$Condition, [string]$Message) {
    if (-not $Condition) { throw $Message }
}
function Pass([string]$Message) { Write-Host "PASS $Message" }
function Protect([string]$Text) {
    foreach ($secret in $secrets) { $Text = $Text.Replace($secret, '[REDACTED]') }
    # Includes possible tokens in third-party diagnostics, not only the JWT signing secret.
    return [regex]::Replace($Text, 'eyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+', '[TOKEN]')
}
function New-Secret {
    $bytes = New-Object byte[] 32
    $rng = [Security.Cryptography.RandomNumberGenerator]::Create()
    try { $rng.GetBytes($bytes) } finally { $rng.Dispose() }
    $value = [BitConverter]::ToString($bytes).Replace('-', '').ToLowerInvariant()
    $secrets.Add($value)
    return $value
}
function Digest([string]$Text) {
    $sha = [Security.Cryptography.SHA256]::Create()
    try { return [BitConverter]::ToString($sha.ComputeHash($utf8.GetBytes($Text))).Replace('-', '') }
    finally { $sha.Dispose() }
}
function Quote-Argument([string]$Value) {
    # Windows CommandLineToArgvW escaping, including embedded quotes and trailing slashes.
    return '"' + [regex]::Replace([regex]::Replace($Value, '(\\*)"', '$1$1\"'), '(\\+)$', '$1$1') + '"'
}
function Start-Owned([string]$Name, [string]$Exe, [string[]]$Arguments, [string]$Cwd, [hashtable]$Extra = @{}) {
    $info = New-Object Diagnostics.ProcessStartInfo
    $info.FileName = $Exe
    $info.WorkingDirectory = $Cwd
    $info.Arguments = ($Arguments | ForEach-Object { Quote-Argument $_ }) -join ' '
    if ([IO.Path]::GetFileName($Exe) -ieq 'cmd.exe') {
        Assert-That ($Arguments.Count -eq 4 -and $Arguments[0] -eq '/d' -and $Arguments[1] -eq '/s' -and $Arguments[2] -eq '/c') 'Unexpected shell invocation'
        # cmd parses one command string, not a CommandLineToArgvW argument vector.
        $info.Arguments = '/d /s /c "' + $Arguments[3] + '"'
    }
    $info.UseShellExecute = $false
    $info.CreateNoWindow = $true
    $info.RedirectStandardOutput = $true
    $info.RedirectStandardError = $true
    $info.StandardOutputEncoding = $utf8
    $info.StandardErrorEncoding = $utf8
    $info.EnvironmentVariables.Clear()
    foreach ($key in $baseEnv.Keys) { $info.EnvironmentVariables[$key] = $baseEnv[$key] }
    foreach ($key in $Extra.Keys) { $info.EnvironmentVariables[$key] = [string]$Extra[$key] }
    $process = New-Object Diagnostics.Process
    $process.StartInfo = $info
    Assert-That ($process.Start()) "Could not start $Name"
    $record = @{ Name=$Name; Process=$process; Out=$process.StandardOutput.ReadToEndAsync(); Err=$process.StandardError.ReadToEndAsync() }
    $children.Add($record)
    return $record
}
function Output-Of($Record) {
    Assert-That ($Record.Process.HasExited) 'Cannot collect output from a running child'
    return $Record.Out.Result + $Record.Err.Result
}
function Native([string]$Name, [string]$Exe, [string[]]$Arguments, [string]$Cwd = $repo, [hashtable]$Extra = @{}, [int]$Timeout = 60) {
    $record = Start-Owned $Name $Exe $Arguments $Cwd $Extra
    if (-not $record.Process.WaitForExit($Timeout * 1000)) { throw "$Name timed out (owned process retained for cleanup)" }
    $output = Output-Of $record
    if ($record.Process.ExitCode -ne 0) {
        $tail = $output.Substring([Math]::Max(0, $output.Length - 5000))
        throw "$Name failed (exit $($record.Process.ExitCode)): $(Protect $tail)"
    }
    return $output.Trim()
}
function Docker([string[]]$Arguments, [hashtable]$Extra = @{}) {
    return Native "Docker $($Arguments[0])" 'docker' $Arguments $repo $Extra 60
}
function Maven([string]$Cwd, [string]$Options, [hashtable]$Extra = @{}) {
    # Options are harness constants, never external input or secrets.
    return Native 'Maven' $baseEnv.ComSpec @('/d','/s','/c',"mvn -B -o $Options") $Cwd $Extra 360
}
function Assert-Migrations {
    $expected = @{
        'V1__initial_schema.sql'='355F84949A1ECC83CA63BDC783D66760094CBA99487C074D9F0C22E0F05B441A'
        'V2__audit_analysis_provenance.sql'='DC6E575768C1CEAD54A9814D3EBC857E229102B5CE98CDB730FFE651D4F4CBBD'
        'V3__incident_rule_provenance.sql'='8BCE983C4D69C3503629FC6B3C3523CBA3EDC98AA7A8E31B8831F2CEF4C3B10E'
    }
    foreach ($name in $expected.Keys) {
        $path = Join-Path $repo "smartaudits-backend/src/main/resources/db/migration/$name"
        $logical = Digest ([IO.File]::ReadAllText($path).Replace("`r`n", "`n"))
        Assert-That ($logical -ceq $expected[$name]) "Frozen migration changed: $name"
        $raw = (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash
        if ($migrationHashes.ContainsKey($name)) { Assert-That ($raw -ceq $migrationHashes[$name]) "Migration bytes changed: $name" }
        else { $migrationHashes[$name] = $raw }
    }
}
function Habitual-State {
    # Identity/status metadata only. Never full inspect, SQL, exec, mounts or credentials.
    $containerState = 'ABSENT'
    if (Docker @('ps','-a','--filter','name=^/smartaudits_mariadb$','--format','{{.Names}}')) {
        $containerState = Docker @('inspect','--format','{{.Id}}|{{.State.Status}}|{{.State.StartedAt}}|{{.State.FinishedAt}}|{{.RestartCount}}','smartaudits_mariadb')
    }
    $volumeState = 'ABSENT'
    if (Docker @('volume','ls','--filter','name=^smartaudits-ia_mariadb_data$','--format','{{.Name}}')) {
        $volumeState = Docker @('volume','inspect','--format','{{.Name}}|{{.Driver}}|{{.CreatedAt}}','smartaudits-ia_mariadb_data')
    }
    return "$containerState`n$volumeState"
}
function Leftovers {
    $c = Docker @('ps','-a','--filter','name=smartaudits-f3-harness-','--format','{{.Names}}')
    $v = Docker @('volume','ls','--filter','name=smartaudits-f3-harness-','--format','{{.Name}}')
    $n = Docker @('network','ls','--filter','name=smartaudits-f3-harness-','--format','{{.Name}}')
    return (@($c,$v,$n) | Where-Object { $_ }) -join ', '
}
function Sql([string]$Query) {
    Assert-That ($resource -cmatch '^smartaudits-f3-harness-[a-f0-9]{32}$') 'Unsafe container'
    Assert-That ($database -cmatch '^f3_harness_[a-f0-9]{32}$') 'Unsafe database'
    return Docker @('exec','-e','MYSQL_PWD',$resource,'mariadb','--host=127.0.0.1','-uf3_harness_user',
        '--default-character-set=utf8mb4','--batch','--skip-column-names',$database,'-e',$Query) @{MYSQL_PWD=$dbPassword}
}
function Free-Port {
    do {
        $listener = New-Object Net.Sockets.TcpListener([Net.IPAddress]::Loopback, 0)
        $listener.Start()
        try { $port = $listener.LocalEndpoint.Port } finally { $listener.Stop() }
    } while ($port -in @(3306,8080) -or $ports.Contains($port))
    $ports.Add($port)
    return $port
}
function Listening([int]$Port) {
    $client = New-Object Net.Sockets.TcpClient
    try {
        $task = $client.ConnectAsync('127.0.0.1',$Port)
        return ($task.Wait(300) -and $client.Connected)
    } catch { return $false } finally { $client.Dispose() }
}
function Http-Status([int]$Port, [string]$Path) {
    try {
        $request = [Net.HttpWebRequest]::Create("http://127.0.0.1:$Port$Path")
        $request.Proxy = $null
        $request.Timeout = 1500
        $response = $request.GetResponse()
        try { return [int]$response.StatusCode } finally { $response.Dispose() }
    } catch [Net.WebException] {
        if ($_.Exception.Response) {
            $response = $_.Exception.Response
            try { return [int]$response.StatusCode } finally { $response.Dispose() }
        }
        return 0
    }
}
function Wait-Ready([string]$Name, [scriptblock]$Check, $Record = $null) {
    $timer = [Diagnostics.Stopwatch]::StartNew()
    while ($timer.Elapsed.TotalSeconds -lt 120) {
        if ($null -ne $Record -and $Record.Process.HasExited) { throw "$Name exited: $(Protect (Output-Of $Record))" }
        if (& $Check) { return }
        Start-Sleep -Milliseconds 300 # Bounded predicate polling, never fixed startup synchronization.
    }
    throw "$Name readiness timeout"
}
function Legacy-Access([int]$Port) {
    $request = [Net.HttpWebRequest]::Create("http://127.0.0.1:$Port/auth/login")
    $request.Proxy=$null
    $request.Timeout=10000
    $request.Method='POST'
    $request.ContentType='application/json'
    $body=$utf8.GetBytes((@{email=$adminEmail;password=$e2ePassword} | ConvertTo-Json -Compress))
    $request.ContentLength=$body.Length
    $stream=$request.GetRequestStream()
    try { $stream.Write($body,0,$body.Length) } finally { $stream.Dispose() }
    $response=$request.GetResponse()
    try {
        Assert-That ([int]$response.StatusCode -eq 200) 'Legacy login failed'
        $reader=New-Object IO.StreamReader($response.GetResponseStream(),$utf8)
        try { $login=$reader.ReadToEnd() | ConvertFrom-Json } finally { $reader.Dispose() }
    } finally { $response.Dispose() }
    Assert-That (-not [string]::IsNullOrWhiteSpace($login.token)) 'Legacy login returned no token'
    $secrets.Add([string]$login.token)
    $request=[Net.HttpWebRequest]::Create("http://127.0.0.1:$Port/usuarios")
    $request.Proxy=$null
    $request.Timeout=10000
    $request.Headers['Authorization']='Bearer ' + $login.token
    $response=$request.GetResponse()
    try { Assert-That ([int]$response.StatusCode -eq 200) 'Authenticated legacy access failed' }
    finally { $response.Dispose() }
    Pass 'Legacy functional access: real login + authenticated GET /usuarios HTTP 200'
}
function Assert-Expansion([string]$Text, [string]$Table) {
    # Conservative allowlist for additive single-table migrations. Reusable for real V4+.
    # Reject comments too, avoiding keyword hiding; future supported syntax needs review.
    Assert-That ($Table -cmatch '^[a-z][a-z0-9_]+$') 'Unsafe table name'
    Assert-That ($Text -notmatch '(?s)--|/\*|\*/|#') 'Migration comments are not accepted by this conservative guard'
    Assert-That ($Text.Trim() -match "(?is)^CREATE\s+TABLE\s+$Table\s*\(.+\)\s*ENGINE=InnoDB\s*;\s*$") 'Expected one CREATE TABLE'
    Assert-That (([regex]::Matches($Text,'(?i)\bCREATE\s+TABLE\b')).Count -eq 1) 'Multiple CREATE TABLE statements'
    Assert-That (([regex]::Matches($Text,';')).Count -eq 1) 'Multiple SQL statements'
    Assert-That ($Text -notmatch '(?i)\b(ALTER|DROP|RENAME|INSERT|UPDATE|DELETE|REPLACE|TRUNCATE|CALL|GRANT|LOAD|SELECT)\b') 'Forbidden SQL operation'
    Assert-That ($Table -notin @('usuarios','roles','usuarios_roles','auditorias','incidencias','resultados','historial_auditorias','historial_acciones_admin')) 'Legacy target forbidden'
    # REFERENCES usuarios is legitimate for future v2 identity FKs; legacy DDL/DML targets are not.
}
function Snapshot {
    $filters = [ordered]@{
        usuarios='id IN (900001,900002)'; roles='id IN (900001,900002)'; usuarios_roles='usuario_id IN (900001,900002)'
        auditorias='id=900001'; incidencias='id=900001'; resultados='id=900001'
        historial_auditorias='id=900001'; historial_acciones_admin='id=900001'
        f3_harness_future_probe='1=1'; flyway_schema_history='1=1'
    }
    $snapshot = [ordered]@{}
    foreach ($table in $filters.Keys) {
        $columns = (Sql "SELECT COLUMN_NAME FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='$table' ORDER BY ORDINAL_POSITION").Split("`n")
        $quoted = ($columns | ForEach-Object { '``'.Substring(0,1) + $_.Trim() + '``'.Substring(0,1) }) -join ','
        $order = if ($table -eq 'usuarios_roles') { 'usuario_id,rol_id' } elseif ($table -eq 'flyway_schema_history') { 'installed_rank' } else { 'id' }
        $rows = Sql "SELECT JSON_ARRAY($quoted) FROM $table WHERE $($filters[$table]) ORDER BY $order"
        $count = Sql "SELECT COUNT(*) FROM $table WHERE $($filters[$table])"
        # E2E legitimately advances auto-increment counters. No row/history field is excluded.
        $structure = (Sql "SHOW CREATE TABLE $table") -replace 'AUTO_INCREMENT=\d+\s*',''
        $snapshot[$table] = "$count|$(Digest $rows)|$(Digest $structure)"
    }
    return $snapshot
}
function Assert-Snapshot($Expected, [string]$Stage) {
    $actual = Snapshot
    foreach ($table in $Expected.Keys) {
        Assert-That ($actual[$table] -ceq $Expected[$table]) "$Stage integrity mismatch: $table"
    }
    Pass "$Stage legacy sentinels + future row + Flyway history + table structures unchanged"
}
function Probe([string]$Mode, [hashtable]$Extra = @{}, [string]$Timezone = 'Asia/Kathmandu') {
    Assert-That ($Timezone -cin @('Asia/Kathmandu','Europe/Madrid')) 'Unexpected probe timezone'
    $environment = @{} + $dbEnv + @{F3_PROBE_MODE=$Mode; E2E_ADMIN_EMAIL=$adminEmail; E2E_PASSWORD=$e2ePassword}
    $environment.F3_TIMEZONE=$Timezone
    foreach ($key in $Extra.Keys) { $environment[$key]=$Extra[$key] }
    $output = Maven $newBackend "-Dtest=harness.smartaudits.v2.MariaDb20HarnessProbe -DargLine=-Duser.timezone=$Timezone test" $environment
    Assert-That ($output -match 'Tests run: 1, Failures: 0, Errors: 0, Skipped: 0') "Probe $Mode did not execute exactly one passing test"
    if ($Mode -eq 'timestamp') {
        Assert-That ($output.Contains('TIMESTAMP PASS:')) 'Missing raw UTC proof'
        foreach ($line in ($output -split '\r?\n')) {
            if ($line -match '^(TIMESTAMP CASE PASS:|RAW SQL -> INSTANT PASS:|TIMESTAMP PASS:)') { Pass $line }
        }
    }
}
function Timestamp-Matrix {
    foreach ($timezone in @('Asia/Kathmandu','Europe/Madrid')) {
        Probe 'timestamp' @{} $timezone
        Assert-That ((Sql "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='f3_harness_timestamp_probe'") -eq '0') 'Timestamp probe table remains'
        Pass "Timestamp ${timezone}: normal + DST case + raw SQL -> Instant; auxiliary table removed"
    }
}
function Start-Backend([string]$Jar, [bool]$Flyway, [string]$Name) {
    $port = Free-Port
    $environment = @{
        DB_URL=$dbEnv.F3_DB_URL; DB_USERNAME=$dbEnv.F3_DB_USER; DB_PASSWORD=$dbPassword; JWT_SECRET=$jwt
        SERVER_ADDRESS='127.0.0.1'; SERVER_PORT=[string]$port
        FLYWAY_ENABLED=$Flyway.ToString().ToLowerInvariant(); FLYWAY_BASELINE_ON_MIGRATE='false'
        SPRING_JPA_HIBERNATE_DDL_AUTO='validate'; APP_ADMIN_BOOTSTRAP_ENABLED='false'; APP_DATA_INITIALIZER_ENABLED='false'
    }
    $record = Start-Owned $Name 'java' @('-jar',$Jar,'--spring.config.location=classpath:/application.properties',
        '--spring.application.admin.enabled=true','--server.shutdown=graceful') $tempRoot $environment
    $record.Jar=$Jar
    $record.Port=$port
    $record.Flyway=$Flyway
    $script:backend=$record
    Wait-Ready $Name { (Http-Status $port '/usuarios') -eq 401 } $record
    Pass "$Name Spring startup + Hibernate validate; loopback port=$port; Flyway=$Flyway"
    return $record
}
function Stop-Backend($Record) {
    if ($null -eq $Record -or $Record.Process.HasExited) { return }
    Probe 'stop' @{F3_BACKEND_PID=[string]$Record.Process.Id; F3_BACKEND_JAR=$Record.Jar; F3_TEMP_ROOT=$tempRoot}
    Assert-That ($Record.Process.WaitForExit(30000)) 'Backend did not complete graceful shutdown'
    $log = Output-Of $Record
    Assert-That ($log.Contains('Started SmartAuditsApplication')) 'Missing Spring startup confirmation'
    Assert-That ($Record.Process.ExitCode -eq 0) 'Backend shutdown returned nonzero'
    # Inspect this process's complete log only; do not confuse startup with Flyway validation.
    $validationPattern = if ($Record.Flyway) {
        '(?m)\bDbValidate\s*:\s*(Successfully validated 4 migrations)\b'
    } else {
        '(?m)\bDbValidate\s*:\s*(Successfully validated \d+ migrations?)\b'
    }
    $validation = [regex]::Matches($log, $validationPattern)
    if ($Record.Flyway) {
        Assert-That ($validation.Count -gt 0) "$($Record.Name): no Flyway DbValidate success marker"
        Pass "$($Record.Name) Flyway ON log: $($validation[0].Groups[1].Value)"
    } else {
        Assert-That ($validation.Count -eq 0) "$($Record.Name): unexpected Flyway validation while OFF"
        Pass "$($Record.Name) Flyway OFF log: DbValidate success marker absent"
    }
    Assert-That ((Sql 'SELECT GROUP_CONCAT(version ORDER BY installed_rank) FROM flyway_schema_history') -ceq '1,2,3,9000') 'Flyway versions changed after backend execution'
    Pass "$($Record.Name) graceful shutdown"
}
function Force-Stop($Record) {
    if (-not $Record.Process.HasExited) {
        # Exact owned PID tree only; never match by executable name.
        $null = Native 'owned process teardown' 'taskkill' @('/PID',[string]$Record.Process.Id,'/T','/F') $repo @{} 30
        Assert-That ($Record.Process.WaitForExit(15000)) 'Owned process remains'
    }
}

try {
    Assert-That ($env:OS -eq 'Windows_NT') 'This harness uses the existing Windows PowerShell/JDK infrastructure'
    Assert-That ($resource -cmatch '^smartaudits-f3-harness-[a-f0-9]{32}$') 'Unsafe run name'
    Assert-Migrations
    $head = Native 'Git HEAD' 'git' @('rev-parse','HEAD')
    $baseline = Native 'Git baseline' 'git' @('rev-parse','cc78d6b^{commit}')
    Assert-That ($baseline -eq 'cc78d6b355ff95042cdb9d47e38ae5d33dc17eb5') 'Unexpected rollback baseline'
    $null = Docker @('version','--format','{{.Server.Version}}')
    $dockerReady=$true
    $left = Leftovers
    Assert-That (-not $left) "Pre-existing harness resources: $left (not removed)"
    $habitualBefore = Habitual-State
    Pass 'Habitual container/volume identity captured read-only (no connection)'
    $null = Docker @('image','inspect','--format','{{.Id}}','mariadb:11.2')
    Assert-That (-not (Test-Path -LiteralPath $tempRoot)) 'Temporary directory already exists'
    $null = New-Item -ItemType Directory -Path $tempRoot
    $tempCreated=$true
    $rootPassword=New-Secret
    $dbPassword=New-Secret
    $jwt=New-Secret
    $e2ePassword=New-Secret
    $adminEmail="admin-$runId@example.invalid"
    $probeRelative='smartaudits-backend/src/test/java/harness/smartaudits/v2/MariaDb20HarnessProbe.java'
    $copies = if ($TimestampOnly) { @('new') } else { @('old','new') }
    foreach ($copy in $copies) {
        $revision = if ($copy -eq 'old') { $baseline } else { $head }
        $destination = Join-Path $tempRoot $copy
        $null = New-Item -ItemType Directory -Path $destination
        $archive = Join-Path $tempRoot "$copy.zip"
        $null = Native 'Git archive' 'git' @('archive','--format=zip',"--output=$archive",$revision,'smartaudits-backend')
        Add-Type -AssemblyName System.IO.Compression.FileSystem
        [IO.Compression.ZipFile]::ExtractToDirectory($archive,$destination)
    }
    $newBackend=Join-Path $tempRoot 'new/smartaudits-backend'
    $oldBackend=Join-Path $tempRoot 'old/smartaudits-backend'
    $probeDestination=Join-Path $tempRoot "new/$probeRelative"
    $null = New-Item -ItemType Directory -Force -Path (Split-Path $probeDestination)
    Copy-Item -LiteralPath (Join-Path $repo $probeRelative) -Destination $probeDestination
    if (-not $TimestampOnly) {
    $futureSql=@'
CREATE TABLE f3_harness_future_probe (
    id BIGINT NOT NULL,
    marker VARCHAR(80) NOT NULL,
    observed_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_f3_harness_marker (marker),
    CONSTRAINT ck_f3_harness_id CHECK (id > 0)
) ENGINE=InnoDB;
'@
    Assert-Expansion $futureSql 'f3_harness_future_probe'
    # Guard self-checks: bad statements must be rejected, not merely the good fixture accepted.
    foreach ($bad in @("$futureSql CREATE TABLE extra (id INT);", "$futureSql ALTER TABLE extra ADD x INT;", "$futureSql INSERT INTO extra VALUES (1);")) {
        $rejected=$false
        try { Assert-Expansion $bad 'f3_harness_future_probe' } catch { $rejected=$true }
        Assert-That $rejected 'Static expansion guard accepted unsafe SQL'
    }
    [IO.File]::WriteAllText((Join-Path $newBackend 'src/main/resources/db/migration/V9000__f3_harness_future_probe.sql'),$futureSql,$utf8)
    Pass 'Synthetic migration V9000: one CREATE TABLE; no DML or legacy targets (temporary copy only)'
    $null = Maven $oldBackend '-DskipTests package'
    Pass "Exact baseline built from Git archive: $baseline"
    $null = Maven $newBackend '-DskipTests package'
    Pass "Synthetic new JAR built from HEAD $head + V9000"
    $oldJar=Join-Path $oldBackend 'target/smartaudits-backend-1.0.0.jar'
    $newJar=Join-Path $newBackend 'target/smartaudits-backend-1.0.0.jar'
    }
    $volumeRegistered=$true
    $null = Docker @('volume','create','--label',"$label=$runId",$volume)
    $containerRegistered=$true
    $null = Docker @('run','--detach','--pull=never','--name',$resource,'--label',"$label=$runId",'--publish','127.0.0.1::3306',
        '--mount',"type=volume,source=$volume,target=/var/lib/mysql",'-e','MARIADB_ROOT_PASSWORD','-e','MARIADB_DATABASE',
        '-e','MARIADB_USER','-e','MARIADB_PASSWORD','mariadb:11.2') @{
            MARIADB_ROOT_PASSWORD=$rootPassword; MARIADB_DATABASE=$database; MARIADB_USER='f3_harness_user'; MARIADB_PASSWORD=$dbPassword
        }
    $dbPort=[int](Docker @('inspect','--format','{{(index (index .NetworkSettings.Ports "3306/tcp") 0).HostPort}}',$resource))
    Assert-That ($dbPort -notin @(3306,8080) -and $dbPort -gt 0) 'Forbidden host port'
    $ports.Add($dbPort)
    $dbEnv=@{F3_DB_URL="jdbc:mariadb://127.0.0.1:$dbPort/$database";F3_DB_NAME=$database;F3_DB_USER='f3_harness_user';F3_DB_PASSWORD=$dbPassword}
    Wait-Ready 'MariaDB authenticated SQL' { try { (Sql 'SELECT 1') -eq '1' } catch { $false } }
    Assert-That ((Sql 'SELECT VERSION()') -match '^11\.2\.') 'Not MariaDB 11.2'
    Assert-That ((Sql 'SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()') -eq '0') 'Fresh database is not empty'
    Pass "Isolated MariaDB 11.2 ready: $resource port=$dbPort"
    if ($TimestampOnly) {
        Timestamp-Matrix
        Assert-That ((Sql 'SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()') -eq '0') 'Directed probe left schema objects behind'
        Pass 'Directed timestamp validation only; no backend, Flyway or E2E started'
    } else {
    $backend=Start-Backend $newJar $true 'NEW initial'
    Assert-That ((Sql 'SELECT GROUP_CONCAT(version ORDER BY installed_rank) FROM flyway_schema_history') -ceq '1,2,3,9000') 'Unexpected migration versions'
    Assert-That ((Sql "SELECT COUNT(*) FROM flyway_schema_history WHERE type='SQL' AND success=1") -eq '4') 'Unexpected migration results'
    Stop-Backend $backend
    Probe 'seed'
    $before=Snapshot
    foreach ($table in $before.Keys) { Pass "PRE $table count|data SHA256|structure SHA256=$($before[$table])" }
    $backend=Start-Backend $oldJar $true 'OLD exact cc78d6b Flyway ON'
    Assert-Snapshot $before 'OLD ON startup'
    $frontend=Join-Path $repo 'smartaudits-frontend'
    $webPort=Free-Port
    $web=Start-Owned 'existing E2E server' 'node' @((Join-Path $frontend 'e2e/server.mjs')) $frontend @{
        E2E_BACKEND_URL="http://127.0.0.1:$($backend.Port)";E2E_FRONTEND_PORT=[string]$webPort
    }
    Wait-Ready 'existing E2E frontend' { (Http-Status $webPort '/') -eq 200 } $web
    $e2e=Native 'unchanged legacy Playwright flows' 'node' @((Join-Path $frontend 'node_modules/@playwright/test/cli.js'),
        'test','flows.spec.js','--config',(Join-Path $frontend 'playwright.config.js'),'--output',(Join-Path $tempRoot 'playwright-output')) $frontend @{
            E2E_RUN_ID=$runId;E2E_BASE_URL="http://127.0.0.1:$webPort";E2E_ADMIN_EMAIL=$adminEmail;E2E_PASSWORD=$e2ePassword
        } 240
    Assert-That ($e2e -match 'Running 4 tests' -and $e2e -match '\b4 passed\b') 'Expected exactly four passing legacy scenarios'
    Pass 'LEGACY E2E: 4/4 PASS (exact cc78d6b; V1/V2/V3/V9000; isolated MariaDB 11.2)'
    Force-Stop $web
    Assert-Snapshot $before 'POST rollback ON + E2E'
    # Verify the actual E2E-created rows separately, not by weakening sentinel comparisons.
    Assert-That ((Sql "SELECT COUNT(*) FROM usuarios WHERE email LIKE 'client-%-$runId@example.invalid'") -eq '3') 'Expected three E2E-created clients'
    Assert-That ((Sql "SELECT COUNT(*) FROM auditorias a JOIN usuarios u ON u.id=a.usuario_id WHERE u.email='client-audit-$runId@example.invalid'") -eq '1') 'Expected one E2E-created audit'
    Stop-Backend $backend
    $backend=Start-Backend $oldJar $false 'OLD exact cc78d6b Flyway OFF'
    Legacy-Access $backend.Port
    Assert-Snapshot $before 'POST rollback OFF'
    Stop-Backend $backend
    $backend=Start-Backend $newJar $true 'NEW return forward'
    Assert-Snapshot $before 'NEW return forward'
    Stop-Backend $backend
    Timestamp-Matrix
    Assert-Snapshot $before 'POST timestamp'
    }
    Assert-Migrations
    Assert-That ((Native 'Git HEAD verification' 'git' @('rev-parse','HEAD')) -ceq $head) 'Main checkout moved'
    Pass 'V1/V2/V3 bytes and normalized hashes intact'
} catch {
    $failure=Protect $_.Exception.Message
    Write-Host "FAIL $failure"
} finally {
    if ($null -ne $backend -and -not $backend.Process.HasExited) {
        try { Stop-Backend $backend } catch { $cleanupErrors.Add('Graceful backend shutdown failed; attempting owned PID teardown') }
    }
    foreach ($child in @($children.ToArray())) {
        try { Force-Stop $child } catch { $cleanupErrors.Add("Could not stop owned process: $($child.Name)") }
    }
    if ($dockerReady) {
        foreach ($kind in @('container','volume')) {
            $registered = if ($kind -eq 'container') { $containerRegistered } else { $volumeRegistered }
            $name = if ($kind -eq 'container') { $resource } else { $volume }
            if (-not $registered) { continue }
            try {
                $names = if ($kind -eq 'container') {
                    Docker @('ps','-a','--filter',"name=^/$name$",'--format','{{.Names}}')
                } else { Docker @('volume','ls','--filter',"name=^$name$",'--format','{{.Name}}') }
                if ($names) {
                    Assert-That ($names -ceq $name) 'Owned resource name mismatch'
                    $template = if ($kind -eq 'container') { '{{index .Config.Labels "smartaudits.f3-harness"}}' } else { '{{index .Labels "smartaudits.f3-harness"}}' }
                    $owner=Docker @($kind,'inspect','--format',$template,$name)
                    Assert-That ($owner -ceq $runId) 'Owned resource label mismatch: refusing deletion'
                    if ($kind -eq 'container') { $null=Docker @('rm','-f',$name) }
                    else { $null=Docker @('volume','rm',$name) }
                }
            } catch { $cleanupErrors.Add("Cleanup failed for $kind $name") }
        }
        try {
            $remaining=Leftovers
            Assert-That (-not $remaining) "Remaining harness resources: $remaining"
            if ($null -ne $habitualBefore) { Assert-That ((Habitual-State) -ceq $habitualBefore) 'Habitual resource identity/status changed' }
            Pass 'Docker cleanup: containers=0 volumes=0 networks=0; habitual container/volume unchanged'
        } catch { $cleanupErrors.Add((Protect $_.Exception.Message)) }
    }
    foreach ($port in $ports) { if (Listening $port) { $cleanupErrors.Add("Owned port still listening: $port") } }
    if ($tempCreated) {
        try {
            $resolved=[IO.Path]::GetFullPath($tempRoot)
            Assert-That ($resolved -ceq (Join-Path $tempParent $resource)) 'Unexpected cleanup path'
            Assert-That ($resolved.StartsWith($tempParent,[StringComparison]::OrdinalIgnoreCase)) 'Cleanup outside temporary root'
            Assert-That ((Split-Path $resolved -Leaf) -ceq $resource) 'Unexpected temporary directory name'
            Remove-Item -LiteralPath $resolved -Recurse -Force
            Assert-That (-not (Test-Path -LiteralPath $resolved)) 'Temporary directory remains'
        } catch { $cleanupErrors.Add("Temporary directory cleanup failed: $resource") }
    }
    try { Assert-Migrations } catch { $cleanupErrors.Add('Frozen migration integrity failed') }
    # Child environments were built independently; parent environment never changed.
    if ($cleanupErrors.Count -eq 0) { Pass 'Cleanup: owned processes/ports/temporary files=0; parent environment unchanged' }
    else { foreach ($problem in $cleanupErrors) { Write-Host "FAIL $problem" } }
}
if ($null -ne $failure -or $cleanupErrors.Count -gt 0) { exit 1 }
if ($TimestampOnly) { Write-Host 'SMARTAUDITS F3.2 TIMESTAMP-ONLY PASS' }
else { Write-Host 'SMARTAUDITS F3.2 HARNESS PASS' }
exit 0
