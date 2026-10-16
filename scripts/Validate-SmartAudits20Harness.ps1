<#
.SYNOPSIS
Validates F3 rollback and the UTC microsecond contract on owned, isolated MariaDB.
.DESCRIPTION
Requires Windows/PowerShell, Git, JDK 17 with jdk.attach, Maven and dependencies
already cached for offline (-o) builds. Requires a running Docker daemon and the
local mariadb:11.2 image; nothing is downloaded automatically.
Full runs also require Node/npm, frontend node_modules, resolvable Playwright and
its installed Chromium, plus available loopback ports. The tracked frontend must
equal the approved rollback release and have no tracked/untracked source changes,
except e2e/run.mjs (explicitly approved; this harness never invokes that runner).
Vite .vite is moved to this run's temporary directory and restored byte-for-byte.
Do not run another frontend/Vite process concurrently. Restoration failure retains
the saved cache and reports cleanup failure instead of deleting the recovery copy.
Only frozen migrations, guarded numeric V4+ and temporary V9000 are used.
Protected UtcMicros/probe sources must equal HEAD and come from Git archive.
.PARAMETER RollbackRelease
Release from the reviewed catalogue below. Updating this catalogue requires an
explicit compatibility decision; arbitrary commits/refs are not accepted.
.PARAMETER IncludePendingMembership
Pre-commit validation only: overlay the six V5 files and the reviewed Organization
historical probe adaptation. Exact paths/statuses/hashes are checked throughout.
IncludePendingOrganization is obsolete and is no longer an accepted parameter.
Future entities must prove they call UtcMicros before persisting timestamps;
driver handling of an unnormalized Instant is not the domain contract.
.PARAMETER TimestampOnly
Runs the two separate JVM timestamp probes without backend startup or E2E.
.PARAMETER SelfTestOnly
Runs finite-output and SQL-guard self-tests without Docker or application startup.
#>
# No target/database/credential parameters: all resources are owned and ephemeral.
# Reuses the unchanged E2E server/config/flows directly (explicitly authorized).
# Does not invoke run.mjs, the legacy validator entrypoint, Compose or Flyway repair.
[CmdletBinding()]
param([switch]$TimestampOnly, [switch]$SelfTestOnly,
    [ValidateSet('bbd02ff','a201582')][string]$RollbackRelease = 'bbd02ff',
    [switch]$IncludePendingMembership)

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
$frontend = Join-Path $repo 'smartaudits-frontend'
$viteCache = Join-Path $frontend 'node_modules/.vite'
$viteSaved = Join-Path $tempRoot 'vite-cache-original'
$cacheManaged = $false
$cacheExisted = $false
$cacheBefore = $null
$preserveTemp = $false
$expectedVersions = @()
$approvedReleases = @{
    # Published compatibility references, neither represents a production deployment.
    bbd02ff='bbd02ff0ae738fff71b6f27a8c6fd70845d1ad3b'
    a201582='a20158268666d4d43bc16844bfaedfa9f00e786d'
}
$candidatePaths = @(
    'smartaudits-backend/src/main/resources/db/migration/V5__create_organization_memberships.sql',
    'smartaudits-backend/src/main/java/com/smartaudits/v2/model/OrganizationMembership.java',
    'smartaudits-backend/src/main/java/com/smartaudits/v2/model/MembershipRole.java',
    'smartaudits-backend/src/main/java/com/smartaudits/v2/model/MembershipStatus.java',
    'smartaudits-backend/src/test/java/com/smartaudits/v2/model/OrganizationMembershipTest.java',
    'smartaudits-backend/src/test/java/com/smartaudits/v2/model/OrganizationMembershipMariaDbProbe.java',
    'smartaudits-backend/src/test/java/com/smartaudits/v2/model/OrganizationTest.java'
)
$candidateHashes = @{}

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
function Await-Output([Threading.Tasks.Task[string]]$Task, [string]$Name, [int]$TimeoutMs = 10000) {
    Assert-That ($TimeoutMs -gt 0) 'Output timeout must be finite and positive'
    if (-not $Task.Wait($TimeoutMs)) { throw "Output capture timed out: $Name (pipe may still be open)" }
    # The task is complete; this retrieval cannot wait on an inherited pipe.
    return $Task.GetAwaiter().GetResult()
}
function Output-Of($Record) {
    Assert-That ($Record.Process.HasExited) 'Cannot collect output from a running child'
    return (Await-Output $Record.Out "$($Record.Name) stdout") + (Await-Output $Record.Err "$($Record.Name) stderr")
}
function Native([string]$Name, [string]$Exe, [string[]]$Arguments, [string]$Cwd = $repo, [hashtable]$Extra = @{}, [int]$Timeout = 60, [switch]$KeepLeadingWhitespace) {
    $record = Start-Owned $Name $Exe $Arguments $Cwd $Extra
    if (-not $record.Process.WaitForExit($Timeout * 1000)) { throw "$Name timed out (owned process retained for cleanup)" }
    $output = Output-Of $record
    if ($record.Process.ExitCode -ne 0) {
        $tail = $output.Substring([Math]::Max(0, $output.Length - 5000))
        throw "$Name failed (exit $($record.Process.ExitCode)); final $($tail.Length) of $($output.Length) log characters: $(Protect $tail)"
    }
    if ($KeepLeadingWhitespace) { return $output.TrimEnd() }
    return $output.Trim()
}
function Docker([string[]]$Arguments, [hashtable]$Extra = @{}) {
    return Native "Docker $($Arguments[0])" 'docker' $Arguments $repo $Extra 60
}
function Maven([string]$Cwd, [string]$Options, [hashtable]$Extra = @{}) {
    # Options are harness constants, never external input or secrets.
    return Native 'Maven' $baseEnv.ComSpec @('/d','/s','/c',"mvn -B -o $Options") $Cwd $Extra 360
}
function Assert-Migrations([string]$Jar) {
    # Published UTF-8 text hashes, canonical CRLF -> LF; never derived from HEAD/JAR.
    $expected = @{
        'V1__initial_schema.sql'='355F84949A1ECC83CA63BDC783D66760094CBA99487C074D9F0C22E0F05B441A'
        'V2__audit_analysis_provenance.sql'='DC6E575768C1CEAD54A9814D3EBC857E229102B5CE98CDB730FFE651D4F4CBBD'
        'V3__incident_rule_provenance.sql'='8BCE983C4D69C3503629FC6B3C3523CBA3EDC98AA7A8E31B8831F2CEF4C3B10E'
        'V4__create_organizations.sql'='298E83B5E675CE032E24D1AE79243A1C01DB81FCC92CAB64D561A1CE0E94D2D5'
        'V5__create_organization_memberships.sql'='CE5A10EF0E1074B337971AF4ABA0DFECEC410A55A0A4BA469C479798F956C498'
    }
    $migrationDir = Join-Path $repo 'smartaudits-backend/src/main/resources/db/migration'
    $sourceNames = @(Get-ChildItem -LiteralPath $migrationDir -Force | ForEach-Object { $_.Name } | Sort-Object)
    Assert-That (($sourceNames -join ',') -ceq (($expected.Keys | Sort-Object) -join ',')) 'Expected exactly V1-V5 source migrations'
    $archive = $null
    if ($Jar) {
        Add-Type -AssemblyName System.IO.Compression.FileSystem
        $archive = [IO.Compression.ZipFile]::OpenRead($Jar)
    }
    try {
        foreach ($name in $expected.Keys) {
            $path = Join-Path $repo "smartaudits-backend/src/main/resources/db/migration/$name"
            Assert-That (Test-Path -LiteralPath $path -PathType Leaf) "Frozen migration missing: $name"
            $logical = Digest ([IO.File]::ReadAllText($path).Replace("`r`n", "`n"))
            Assert-That ($logical -ceq $expected[$name]) "Frozen migration changed: $name"
            $raw = (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash
            if ($migrationHashes.ContainsKey($name)) { Assert-That ($raw -ceq $migrationHashes[$name]) "Migration bytes changed: $name" }
            else { $migrationHashes[$name] = $raw }
            if ($archive) {
                $entries = @($archive.Entries | Where-Object { $_.FullName -ceq "BOOT-INF/classes/db/migration/$name" })
                Assert-That ($entries.Count -eq 1) "Packaged frozen migration missing/duplicate: $name"
                $reader = [IO.StreamReader]::new($entries[0].Open(), $utf8)
                try { $packaged = Digest ($reader.ReadToEnd().Replace("`r`n", "`n")) }
                finally { $reader.Dispose() }
                Assert-That ($packaged -ceq $expected[$name]) "Packaged frozen migration changed: $name"
            }
        }
        if ($archive) {
            # Real-Migrations fixes approved Git versions plus owned synthetic V9000.
            $packagedVersions = @($archive.Entries | Where-Object {
                $_.FullName.StartsWith('BOOT-INF/classes/db/migration/') -and -not $_.FullName.EndsWith('/')
            } | ForEach-Object {
                Assert-That ($_.FullName -cmatch '^BOOT-INF/classes/db/migration/V([0-9]+)__.+\.sql$') 'Unsupported packaged migration name'
                [long]$Matches[1]
            })
            Assert-That ((($packagedVersions | Sort-Object) -join ',') -ceq (($expectedVersions | Sort-Object) -join ',')) 'Packaged migration inventory differs from approved versions'
        }
    } finally { if ($archive) { $archive.Dispose() } }
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
function Assert-Expansion([string]$Path, [switch]$RequireTableOptions) {
    # Conservative lexical subset, not a universal SQL parser. Strings and quoted
    # identifiers cannot hide statement delimiters/keywords from this analysis.
    $sql = [IO.File]::ReadAllText($Path)
    $lexer = [regex]'\G(?:(?<space>\s+)|(?<string>''(?:[^''\\]|'''')*'')|(?<quoted>`[A-Za-z_][A-Za-z0-9_]*`)|(?<word>[A-Za-z_][A-Za-z0-9_]*)|(?<number>\d+(?:\.\d+)?)|(?<symbol>[(),;=<>!+*.-]))'
    $tokens = New-Object 'System.Collections.Generic.List[object]'
    $offset = 0
    while ($offset -lt $sql.Length) {
        Assert-That (-not $sql.Substring($offset).StartsWith('--')) 'SQL comments forbidden'
        $match = $lexer.Match($sql, $offset)
        Assert-That ($match.Success -and $match.Index -eq $offset) 'Unsupported SQL token/comment/escape'
        $offset += $match.Length
        if ($match.Groups['space'].Success) { continue }
        foreach ($kind in @('string','quoted','word','number','symbol')) {
            if ($match.Groups[$kind].Success) {
                $tokens.Add(@{Kind=$kind; Text=$match.Value.ToUpperInvariant()})
                break
            }
        }
    }
    Assert-That ($tokens.Count -ge 8) 'Incomplete CREATE TABLE'
    Assert-That ($tokens[0].Kind -eq 'word' -and $tokens[0].Text -eq 'CREATE' -and
        $tokens[1].Kind -eq 'word' -and $tokens[1].Text -eq 'TABLE') 'Expected CREATE TABLE only'
    Assert-That ($tokens[2].Kind -in @('word','quoted') -and $tokens[3].Text -eq '(') 'Expected unqualified target table'
    $table = $tokens[2].Text.Trim([char]96)
    Assert-That ($table -notin @('USUARIOS','ROLES','USUARIOS_ROLES','AUDITORIAS','INCIDENCIAS','RESULTADOS',
        'HISTORIAL_AUDITORIAS','HISTORIAL_ACCIONES_ADMIN','FLYWAY_SCHEMA_HISTORY')) 'Legacy target forbidden'
    $depth = 0
    $close = -1
    for ($i=3; $i -lt $tokens.Count; $i++) {
        $token = $tokens[$i]
        if ($token.Kind -eq 'symbol') {
            if ($token.Text -eq '(') { $depth++ }
            if ($token.Text -eq ')') { $depth--; if ($depth -eq 0) { $close=$i; break } }
            Assert-That ($token.Text -ne ';' -and $depth -ge 0) 'Unexpected statement delimiter'
        }
        # Inspect the body only, before the separately validated table-options tail.
        # Column names and nested CHECK expressions do not set storage encoding.
        # Quoting an encoding/type token in a declaration cannot bypass this guard.
        if ($depth -eq 1 -and $token.Kind -in @('word','quoted') -and
            $i -ne 4 -and $tokens[$i-1].Text -ne ',') {
            $keyword = $token.Text.Trim([char]96)
            $characterSet = $keyword -in @('CHAR','CHARACTER') -and $i+1 -lt $tokens.Count -and
                $tokens[$i+1].Kind -in @('word','quoted') -and $tokens[$i+1].Text.Trim([char]96) -eq 'SET'
            $encoding = $keyword -in @('CHARSET','COLLATE','ASCII','UNICODE','NCHAR','NATIONAL','NVARCHAR')
            $storageAlias = $keyword -in @('BINARY','BYTE','JSON')
            Assert-That (-not ($characterSet -or $encoding -or $storageAlias)) 'Column charset/collation override or implicit encoding forbidden'
        }
        if ($token.Kind -ne 'word') { continue }
        if ($token.Text -eq 'REFERENCES') {
            Assert-That ($i+2 -lt $tokens.Count -and $tokens[$i+1].Kind -in @('word','quoted') -and
                $tokens[$i+2].Text -eq '(') 'Expected unqualified FK reference'
            $reference = $tokens[$i+1].Text.Trim([char]96)
            Assert-That ($reference -notin @('ROLES','USUARIOS_ROLES','AUDITORIAS','INCIDENCIAS','RESULTADOS',
                'HISTORIAL_AUDITORIAS','HISTORIAL_ACCIONES_ADMIN','FLYWAY_SCHEMA_HISTORY')) 'Only usuarios may be referenced from legacy'
        }
        Assert-That ($token.Text -notin @('CREATE','ALTER','DROP','RENAME','TRUNCATE','INSERT','REPLACE','CALL',
            'LOAD','SELECT','GRANT','REVOKE','SET','CASCADE','CURRENT_TIMESTAMP')) 'Forbidden SQL operation/default'
        if ($token.Text -in @('DELETE','UPDATE')) {
            Assert-That ($tokens[$i-1].Kind -eq 'word' -and $tokens[$i-1].Text -eq 'ON') 'DML forbidden'
            $restrict = $i+1 -lt $tokens.Count -and $tokens[$i+1].Kind -eq 'word' -and $tokens[$i+1].Text -eq 'RESTRICT'
            $noAction = $i+2 -lt $tokens.Count -and $tokens[$i+1].Kind -eq 'word' -and $tokens[$i+1].Text -eq 'NO' -and
                $tokens[$i+2].Kind -eq 'word' -and $tokens[$i+2].Text -eq 'ACTION'
            Assert-That ($restrict -or $noAction) 'Only FK RESTRICT/NO ACTION is allowed'
        }
    }
    Assert-That ($close -gt 4 -and $depth -eq 0) 'Unbalanced or empty CREATE TABLE'
    # No AS SELECT, LIKE, extra DDL, table directories, executable comments or second statement.
    $tail = @($tokens | Select-Object -Skip ($close+1))
    $bare = $tail.Count -eq 1 -and $tail[0].Text -eq ';'
    $plainTail = @($tail | Where-Object { $_.Kind -notin @('word','symbol') }).Count -eq 0
    $options = ($tail | ForEach-Object { $_.Text }) -join ' '
    $explicitOptions = $plainTail -and $options -cmatch '^ENGINE = INNODB DEFAULT (?:CHARACTER SET|CHARSET) = UTF8MB4 COLLATE = UTF8MB4_UNICODE_520_CI ;$'
    $engineOnly = $plainTail -and $options -ceq 'ENGINE = INNODB ;'
    # Real V4+ must declare all three; synthetic fixtures retain the original subset.
    Assert-That ($explicitOptions -or (-not $RequireTableOptions -and ($bare -or $engineOnly))) 'Unsupported/missing table options; real V4+ requires InnoDB, utf8mb4 and utf8mb4_unicode_520_ci'
}
function Self-Tests {
    $done = New-Object 'System.Threading.Tasks.TaskCompletionSource[string]'
    $done.SetResult('complete log')
    Assert-That ((Await-Output $done.Task 'completed fixture' 100) -ceq 'complete log') 'Output content changed'
    $pending = New-Object 'System.Threading.Tasks.TaskCompletionSource[string]'
    $timedOut = $false
    try { $null = Await-Output $pending.Task 'open pipe fixture' 50 } catch {
        $timedOut = $_.Exception.Message -like 'Output capture timed out:*'
    }
    Assert-That $timedOut 'Pending output did not fail with finite timeout'
    Pass 'Output self-test: completed log intact; pending pipe task rejected with finite timeout'
    $simple = 'CREATE TABLE f3_example (id BIGINT PRIMARY KEY) ENGINE=InnoDB;'
    $good = @(
        $simple,
        'create table `f3_example` (`id` BIGINT PRIMARY KEY);',
        "CREATE TABLE f3_example (status VARCHAR(20) CHECK (status IN ('ACTIVE','REVOKED')));",
        'CREATE TABLE f3_example (id BIGINT, FOREIGN KEY (id) REFERENCES usuarios(id));',
        'CREATE TABLE f3_example (id BIGINT, CONSTRAINT fk FOREIGN KEY (id) REFERENCES `usuarios` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT);',
        "CREATE`nTABLE f3_example (id BIGINT, FOREIGN KEY (id) REFERENCES usuarios(id) ON DELETE NO ACTION ON UPDATE NO ACTION);",
        'CREATE TABLE f3_example (id BIGINT, INDEX ix (id), UNIQUE KEY uq (id));',
        "CREATE TABLE f3_example (status VARCHAR(60) CHECK (status IN ('DELETE FROM; --','it''s ACTIVE')));"
    )
    $bad = @(
        "$simple CREATE TABLE extra (id INT);", "$simple CREATE INDEX ix ON f3_example(id);",
        'ALTER TABLE usuarios ADD x INT;', 'DROP TABLE usuarios;', 'INSERT INTO usuarios VALUES (1);',
        'UPDATE usuarios SET id=1;', 'DELETE FROM usuarios;', 'TRUNCATE TABLE usuarios;',
        'RENAME TABLE usuarios TO stolen;', 'REPLACE INTO usuarios VALUES (1);', 'CALL anything();',
        "LOAD DATA INFILE 'anything' INTO TABLE usuarios;", "$simple SELECT 1;",
        'CREATE TABLE f3_example (id INT, FOREIGN KEY (id) REFERENCES usuarios(id) ON DELETE CASCADE);',
        'CREATE TABLE f3_example (id INT, FOREIGN KEY (id) REFERENCES usuarios(id) ON DELETE SET NULL);',
        'CREATE TABLE f3_example (id INT); -- comment', 'CREATE /* hidden */ TABLE f3_example (id INT);',
        'CREATE TABLE f3_example (id INT) /*! INSERT INTO usuarios VALUES (1) */;',
        'CREATE TABLE f3_example (id INT); # comment',
        'CREATE TABLE f3_example (id INT) AS SELECT id FROM usuarios;',
        'CREATE TABLE f3_example (id INT, CHECK (id IN (SELECT id FROM usuarios)));'
    )
    foreach ($legacy in @('usuarios','roles','usuarios_roles','auditorias','incidencias','resultados',
        'historial_auditorias','historial_acciones_admin','flyway_schema_history')) {
        $bad += ('CREATE TABLE `' + $legacy.ToUpperInvariant() + '` (id INT);')
        if ($legacy -ne 'usuarios') {
            $bad += ('CREATE TABLE f3_example (id BIGINT, FOREIGN KEY(id) REFERENCES `' + $legacy.ToUpperInvariant() + '` (id));')
            $bad += ("CREATE TABLE f3_example (id BIGINT REFERENCES`n" + $legacy + "`n(id));")
        }
    }
    $good += 'CREATE TABLE f3_example (id BIGINT REFERENCES `UsUaRiOs` (id)) ENGINE=InnoDB DEFAULT CHARACTER SET=utf8mb4 COLLATE=utf8mb4_unicode_520_ci;'
    $good += 'CREATE TABLE f3_example (id BIGINT) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_520_ci;'
    foreach ($suffix in @('ENGINE=MyISAM;', 'ENGINE=InnoDB DEFAULT CHARSET=latin1 COLLATE=utf8mb4_unicode_520_ci;',
        'ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;', 'ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;',
        'ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_520_ci; DROP TABLE usuarios;',
        'ENGINE=InnoDB DATA DIRECTORY=''elsewhere'';')) { $bad += "CREATE TABLE f3_example (id BIGINT) $suffix" }
    $bad += 'CREATE TABLE f3_example (id BIGINT REFERENCES other.`usuarios` (id));'
    $realGood = @(
        'CREATE TABLE f3_example (id BIGINT REFERENCES usuarios(id)) ENGINE=InnoDB DEFAULT CHARACTER SET=utf8mb4 COLLATE=utf8mb4_unicode_520_ci;',
        "create`ntable ``f3_example`` (id BIGINT)`nengine = InnoDB`ndefault charset = utf8mb4`ncollate = utf8mb4_unicode_520_ci;"
    )
    # Every incomplete subset of ENGINE / charset / collation must fail for real V4+.
    $realBad = @(';', 'ENGINE=InnoDB;', 'DEFAULT CHARACTER SET=utf8mb4;',
        'COLLATE=utf8mb4_unicode_520_ci;', 'ENGINE=InnoDB DEFAULT CHARACTER SET=utf8mb4;',
        'ENGINE=InnoDB COLLATE=utf8mb4_unicode_520_ci;',
        'DEFAULT CHARACTER SET=utf8mb4 COLLATE=utf8mb4_unicode_520_ci;') |
        ForEach-Object { "CREATE TABLE f3_example (id BIGINT) $_" }
    $tableOptions = 'ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_520_ci;'
    $encodingGood = @(
        [IO.File]::ReadAllText((Join-Path $repo 'smartaudits-backend/src/main/resources/db/migration/V4__create_organizations.sql')),
        [IO.File]::ReadAllText((Join-Path $repo $candidatePaths[0])),
        "CREATE TABLE f3_example (status VARCHAR(20), CHECK (BINARY status IN ('ACTIVE','REVOKED'))) $tableOptions",
        "CREATE TABLE f3_example (value VARCHAR(90) DEFAULT 'CHARSET COLLATE ASCII UNICODE NCHAR NATIONAL NVARCHAR JSON BINARY') $tableOptions",
        ('CREATE TABLE f3_example (`charset` VARCHAR(50), `unicode` CHAR(2), value CHARACTER VARYING(50)) ' + $tableOptions),
        "CREATE TABLE f3_example (value LONGTEXT CHECK (JSON_VALID(value))) $tableOptions"
    )
    $encodingDeclarations = @(
        'VARCHAR(50) CHARSET latin1', 'VARCHAR(50) CHARSET=latin1',
        'VARCHAR(50) CHARACTER SET latin1', 'VARCHAR(50) CHARACTER SET=latin1',
        'VARCHAR(50) CHAR SET latin1',
        'VARCHAR(50) COLLATE latin1_bin', 'VARCHAR(50) COLLATE=utf8mb4_bin',
        'VARCHAR(50) CHARSET `latin1`', "VARCHAR(50) CHARSET 'latin1'",
        'VARCHAR(50) COLLATE `utf8mb4_bin`', "VARCHAR(50) COLLATE 'utf8mb4_bin'",
        'VARCHAR(50) CHARSET utf8mb4', 'VARCHAR(50) COLLATE utf8mb4_unicode_520_ci',
        'VARCHAR(50) ASCII', 'VARCHAR(50) UNICODE', 'VARCHAR(50) BINARY',
        'CHAR(5) BYTE', 'NCHAR(5)', 'NCHAR VARCHAR(50)', 'NCHAR VARYING(50)',
        'NVARCHAR(50)', 'NATIONAL VARCHAR(50)', 'NATIONAL CHAR(5)',
        'NATIONAL CHARACTER VARYING(50)', 'NATIONAL VARCHARACTER(50)',
        'TEXT ASCII', 'TEXT UNICODE', 'TEXT BINARY', 'JSON'
    )
    $encodingRejected = 0
    $fixture = Join-Path $tempRoot 'guard-fixture.sql'
    try {
        foreach ($sql in $good) { [IO.File]::WriteAllText($fixture,$sql,$utf8); Assert-Expansion $fixture }
        foreach ($sql in $bad) {
            [IO.File]::WriteAllText($fixture,$sql,$utf8)
            $rejected=$false
            try { Assert-Expansion $fixture } catch { $rejected=$true }
            Assert-That $rejected 'Static migration guard accepted a rejection fixture'
        }
        foreach ($sql in $realGood) {
            [IO.File]::WriteAllText($fixture,$sql,$utf8)
            Assert-Expansion $fixture -RequireTableOptions
        }
        foreach ($sql in $realBad) {
            [IO.File]::WriteAllText($fixture,$sql,$utf8)
            $rejected=$false
            try { Assert-Expansion $fixture -RequireTableOptions } catch { $rejected=$true }
            Assert-That $rejected 'Real V4+ guard accepted incomplete table options'
        }
        foreach ($sql in $encodingGood) {
            [IO.File]::WriteAllText($fixture,$sql,$utf8)
            Assert-Expansion $fixture -RequireTableOptions
        }
        foreach ($declaration in $encodingDeclarations) {
            foreach ($variant in @($declaration, $declaration.ToLowerInvariant(), $declaration.Replace(' ',"`r`n`t"))) {
                [IO.File]::WriteAllText($fixture,('CREATE TABLE f3_example (`value` ' + $variant + ') ' + $tableOptions),$utf8)
                $reason = ''
                try { Assert-Expansion $fixture -RequireTableOptions } catch { $reason=$_.Exception.Message }
                Assert-That ($reason -ceq 'Column charset/collation override or implicit encoding forbidden') "Wrong encoding rejection: $declaration -> $reason"
                $encodingRejected++
            }
        }
        # Quoted type/attribute disguises and executable/comment splitting fail
        # explicitly; rejection for unrelated malformed table options is insufficient.
        foreach ($case in @(
            @('VARCHAR(50) `CHARSET` latin1', 'Column charset/collation override or implicit encoding forbidden'),
            @('`NVARCHAR`(50)', 'Column charset/collation override or implicit encoding forbidden'),
            @('VARCHAR(50) /*! CHARSET latin1 */', 'Unsupported SQL token/comment/escape'),
            @('VARCHAR(50) CHARACTER /* hidden */ SET latin1', 'Unsupported SQL token/comment/escape'),
            @('VARCHAR(50) CHARSET "latin1"', 'Unsupported SQL token/comment/escape'),
            @("VARCHAR(50) -- hidden`n CHARSET latin1", 'SQL comments forbidden'),
            @("VARCHAR(50) # hidden`n CHARSET latin1", 'Unsupported SQL token/comment/escape')
        )) {
            [IO.File]::WriteAllText($fixture,('CREATE TABLE f3_example (value ' + $case[0] + ') ' + $tableOptions),$utf8)
            $reason = ''
            try { Assert-Expansion $fixture -RequireTableOptions } catch { $reason=$_.Exception.Message }
            Assert-That ($reason -ceq $case[1]) "Wrong encoding syntax rejection: $($case[0]) -> $reason"
            $encodingRejected++
        }
    } finally { if (Test-Path -LiteralPath $fixture) { Remove-Item -LiteralPath $fixture } }
    Pass "Migration guard self-tests: $($good.Count) accepted; $($bad.Count) rejected; before Docker"
    Pass "Real V4+ table options self-tests: $($realGood.Count) accepted; $($realBad.Count) rejected; before Docker"
    Pass "Column encoding self-tests: $($encodingGood.Count) accepted; $encodingRejected rejected with exact reasons; before Docker"
}
function Real-Migrations {
    $relative = 'smartaudits-backend/src/main/resources/db/migration'
    $status = Native 'Git migration status' 'git' @('status','--porcelain','--untracked-files=all','--',$relative)
    if ($status) {
        Assert-That ($IncludePendingMembership -and $status -ceq "?? $($candidatePaths[0])") 'Only explicit new Membership migration candidate may differ from HEAD'
    }
    $files = @((Native 'Git migration inventory' 'git' @('ls-tree','-r','--name-only',$head,'--',$relative)) -split '\r?\n')
    if ($IncludePendingMembership -and $files -notcontains $candidatePaths[0]) { $files += $candidatePaths[0] }
    $versions = @{}
    foreach ($file in $files) {
        Assert-That ((Split-Path $file -Leaf) -cmatch '^V([0-9]+)__.+\.sql$') 'Unsupported migration filename/version'
        $version = [long]$Matches[1]
        Assert-That ($version -gt 0 -and $version -lt 9000 -and -not $versions.ContainsKey($version)) 'Duplicate/reserved migration version'
        $versions[$version] = $file
        if ($version -ge 4) { Assert-Expansion (Join-Path $repo $file) -RequireTableOptions }
    }
    Assert-That ((($versions.Keys | Sort-Object) -join ',') -ceq '1,2,3,4,5') 'Expected exactly versioned V1-V5 (plus explicit pending V5 only)'
    $real = @($versions.Keys | Where-Object { $_ -ge 4 } | Sort-Object)
    $script:expectedVersions = @(1,2,3) + $real + @(9000)
    $found = if ($real.Count) { $real -join ',' } else { 'none' }
    Pass "Real V4+: $found; pending candidates=$IncludePendingMembership; expected Flyway versions=$($expectedVersions -join ','); count=$($expectedVersions.Count)"
}
function Assert-VersionedSources {
    foreach ($path in @('smartaudits-backend/src/main/java/com/smartaudits/v2/time/UtcMicros.java',
        'smartaudits-backend/src/test/java/harness/smartaudits/v2/MariaDb20HarnessProbe.java')) {
        Assert-That (-not (Native 'Protected source status' 'git' @('status','--porcelain','--untracked-files=all','--',(Split-Path $path)))) 'Protected source directory diverges from HEAD'
        $blob = Native 'Protected HEAD blob' 'git' @('rev-parse',"${head}:$path")
        Assert-That ((Native 'Protected source hash' 'git' @('hash-object',"--path=$path",$path)) -ceq $blob) 'Protected source differs from HEAD'
    }
    Pass 'UtcMicros and original probe equal HEAD; Git archive is the source of both'
}
function Assert-Candidates {
    # The only other dirty files allowed are the four reviewed validation/documentation files.
    $allowedChanges = $candidatePaths + @('scripts/Validate-SmartAudits20Harness.ps1',
        'scripts/Validate-FlywayPhase08.ps1','smartaudits-frontend/e2e/run.mjs','docs/ADR-0001-smartaudits-2-core-model.md')
    $status = Native 'Git exact V5 scope entries' 'git' @('-c','core.quotePath=false','status','--porcelain=v1','--untracked-files=all') -KeepLeadingWhitespace
    $entries = @(if ($status) { $status -split '\r?\n' })
    for ($i=0; $i -lt $entries.Count; $i++) {
        $entry=$entries[$i]
        Assert-That ($entry.Length -gt 3) 'Malformed Git status'
        $path=$entry.Substring(3)
        $state=$entry.Substring(0,2)
        Assert-That ($allowedChanges -ccontains $path) "Unexpected file outside V5 scope: $path"
        $newCandidate = $candidatePaths[0..5] -ccontains $path
        Assert-That (($newCandidate -and $IncludePendingMembership -and $state -ceq '??') -or
            (-not $newCandidate -and $state -ceq ' M')) "Unexpected index/candidate status: $path"
        if (-not $IncludePendingMembership) {
            Assert-That ($candidatePaths -cnotcontains $path) 'Membership sources must equal HEAD without IncludePendingMembership'
        }
    }
    if ($IncludePendingMembership) {
        foreach ($path in $candidatePaths[0..5]) {
            Assert-That ((Native 'Pending candidate status' 'git' @('status','--porcelain=v1','--untracked-files=all','--',$path)) -ceq "?? $path") 'Pending Membership requires six exact untracked new files'
        }
    }
    foreach ($path in $candidatePaths) {
        $item=Get-Item -LiteralPath (Join-Path $repo $path) -Force
        Assert-That (-not $item.PSIsContainer -and -not ($item.Attributes -band [IO.FileAttributes]::ReparsePoint)) 'Candidate must be a regular file'
        if (-not $IncludePendingMembership) {
            $blob=Native 'Membership HEAD blob' 'git' @('rev-parse',"${head}:$path")
            Assert-That ((Native 'Membership source blob' 'git' @('hash-object',"--path=$path",$path)) -ceq $blob) 'Membership source differs from HEAD'
        }
        $hash=(Get-FileHash -LiteralPath (Join-Path $repo $path) -Algorithm SHA256).Hash
        if ($candidateHashes.ContainsKey($path)) { Assert-That ($candidateHashes[$path] -ceq $hash) 'Candidate changed during validation' }
        else { $candidateHashes[$path]=$hash; Pass "Candidate $path SHA256=$hash" }
    }
}
function Assert-History {
    Assert-That ((Sql 'SELECT GROUP_CONCAT(version ORDER BY installed_rank) FROM flyway_schema_history') -ceq ($expectedVersions -join ',')) 'Unexpected Flyway version set/order'
    Assert-That ((Sql "SELECT COUNT(*) FROM flyway_schema_history WHERE type='SQL' AND success=1") -eq [string]$expectedVersions.Count) 'Unexpected migration results'
}
function Assert-Frontend {
    $message = "Rollback harness needs an explicit frontend/E2E decision: frontend diverges from $RollbackRelease"
    # Coordinator-approved exception: unused runner only; server/config/flows remain protected.
    $paths = @('smartaudits-frontend', ':(top,exclude,literal)smartaudits-frontend/e2e/run.mjs')
    Assert-That (-not (Native 'Git frontend status' 'git' (@('status','--porcelain','--untracked-files=all','--') + $paths))) $message
    try { $null = Native 'Git frontend baseline diff' 'git' (@('diff','--quiet',$baseline,$head,'--') + $paths) }
    catch { throw $message }
    Pass 'Frontend equals exact baseline and source tree is clean, except the explicitly approved unused e2e/run.mjs'
}
function Preflight {
    Assert-VersionedSources
    $java = Native 'Java properties' 'java' @('-XshowSettings:properties','-version')
    Assert-That ($java -match '(?m)^\s*java\.home = (.+)\r?$') 'Cannot identify PATH Java home'
    $pathJdk=$Matches[1].Trim()
    Assert-That ($java -match '(?m)^\s*java\.version = (17\.[^\s]+)') 'JDK 17 required'
    $javaVersion=$Matches[1]
    $maven = Native 'Maven runtime' $baseEnv.ComSpec @('/d','/s','/c','mvn -version')
    Assert-That ($maven -match 'Java version: ([^,]+),[^\r\n]*runtime: ([^\r\n]+)') 'Cannot identify Maven runtime'
    Assert-That ($Matches[1] -ceq $javaVersion) 'Maven and PATH Java versions differ'
    $mavenJdk=$Matches[2].Trim()
    $jdks=@($pathJdk,$mavenJdk)
    if ($baseEnv.ContainsKey('JAVA_HOME')) { $jdks += $baseEnv.JAVA_HOME }
    foreach ($jdk in $jdks) {
        foreach ($file in @('release','bin/java.exe','jmods/jdk.attach.jmod','jmods/java.base.jmod')) {
            Assert-That ((Get-FileHash -LiteralPath (Join-Path $jdk $file)).Hash -ceq (Get-FileHash -LiteralPath (Join-Path $pathJdk $file)).Hash) 'PATH Java, Maven or JAVA_HOME use different JDK builds'
        }
        Assert-That ((Native 'Verified JDK modules' (Join-Path $jdk 'bin/java.exe') @('--list-modules')) -match '(?m)^jdk\.attach@') 'Verified JDK lacks jdk.attach'
    }
    Pass "PATH Java / Maven / JAVA_HOME coherent: JDK $javaVersion; matching runtime, release and attach hashes"
    if (-not $TimestampOnly) {
        Assert-Frontend
        $null = Native 'Node version' 'node' @('--version')
        $null = Native 'npm version' $baseEnv.ComSpec @('/d','/s','/c','npm --version')
        Assert-That (Test-Path -LiteralPath (Join-Path $frontend 'node_modules') -PathType Container) 'Frontend node_modules required'
        $null = Native 'Playwright Chromium preflight' 'node' @('-e',
            'const fs=require("node:fs"); const {chromium}=require("@playwright/test"); if(!fs.existsSync(chromium.executablePath())) throw new Error("Install Playwright Chromium before running the harness");') $frontend
    }
    $null = Free-Port
    $null = Docker @('version','--format','{{.Server.Version}}')
    $null = Docker @('image','inspect','--format','{{.Id}}','mariadb:11.2')
    Pass 'Prerequisites available; Maven execution remains strictly offline'
}
function Cache-State([string]$Path) {
    if (-not (Test-Path -LiteralPath $Path)) { return 'ABSENT' }
    $root = Get-Item -LiteralPath $Path -Force
    Assert-That ($root.PSIsContainer -and -not ($root.Attributes -band [IO.FileAttributes]::ReparsePoint)) 'Unsafe Vite cache directory'
    $entries = New-Object 'System.Collections.Generic.List[string]'
    $queue = New-Object 'System.Collections.Generic.Queue[IO.DirectoryInfo]'
    $queue.Enqueue($root)
    while ($queue.Count) {
        $dir = $queue.Dequeue()
        $entries.Add("D|$($dir.FullName.Substring($root.FullName.Length))|$($dir.LastWriteTimeUtc.Ticks)|$($dir.Attributes)")
        foreach ($item in (Get-ChildItem -LiteralPath $dir.FullName -Force)) {
            Assert-That (-not ($item.Attributes -band [IO.FileAttributes]::ReparsePoint)) 'Vite cache reparse point forbidden'
            if ($item.PSIsContainer) { $queue.Enqueue($item) }
            else { $entries.Add("F|$($item.FullName.Substring($root.FullName.Length))|$($item.Length)|$($item.LastWriteTimeUtc.Ticks)|$($item.Attributes)|$((Get-FileHash -LiteralPath $item.FullName -Algorithm SHA256).Hash)") }
        }
    }
    return Digest (($entries | Sort-Object) -join "`n")
}
function Preserve-Vite {
    # Both exact paths are on the same volume; Directory.Move is an atomic rename.
    Assert-That ([IO.Path]::GetFullPath($viteCache) -ceq [IO.Path]::GetFullPath((Join-Path $repo 'smartaudits-frontend/node_modules/.vite'))) 'Unexpected Vite path'
    Assert-That (-not ((Get-Item -LiteralPath (Split-Path $viteCache) -Force).Attributes -band [IO.FileAttributes]::ReparsePoint)) 'node_modules reparse point forbidden'
    Assert-That ([IO.Path]::GetPathRoot($viteCache) -eq [IO.Path]::GetPathRoot($viteSaved)) 'Cache preservation requires same volume'
    $script:cacheBefore = Cache-State $viteCache
    $script:cacheExisted = $cacheBefore -ne 'ABSENT'
    if ($cacheExisted) { [IO.Directory]::Move($viteCache,$viteSaved) }
    $script:cacheManaged=$true
    Pass "Vite cache preserved; existed=$cacheExisted"
}
function Restore-Vite {
    if (-not $cacheManaged) { return }
    Assert-That (@($children | Where-Object { -not $_.Process.HasExited }).Count -eq 0) 'Owned process still running: cannot restore Vite cache'
    if ($cacheExisted) { Assert-That ((Cache-State $viteSaved) -ceq $cacheBefore) 'Saved Vite cache changed' }
    if (Test-Path -LiteralPath $viteCache) {
        $null = Cache-State $viteCache # Reject reparse points before recursive deletion.
        Assert-That ([IO.Path]::GetFullPath($viteCache) -ceq [IO.Path]::GetFullPath((Join-Path $repo 'smartaudits-frontend/node_modules/.vite'))) 'Unsafe cache cleanup path'
        Remove-Item -LiteralPath $viteCache -Recurse -Force
    }
    if ($cacheExisted) { [IO.Directory]::Move($viteSaved,$viteCache) }
    Assert-That ((Cache-State $viteCache) -ceq $cacheBefore) 'Vite cache PRE/POST mismatch'
    $script:cacheManaged=$false
    Pass 'Vite cache PRE == POST (content, structure, attributes, modification times)'
}
function Snapshot {
    $filters = [ordered]@{
        usuarios='id IN (900001,900002)'; roles='id IN (900001,900002)'; usuarios_roles='usuario_id IN (900001,900002)'
        auditorias='id=900001'; incidencias='id=900001'; resultados='id=900001'
        historial_auditorias='id=900001'; historial_acciones_admin='id=900001'
        f3_harness_future_probe='1=1'; flyway_schema_history='1=1'
    }
    if ((Sql "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='organizations'") -eq '1') {
        $filters['organizations']='1=1'
    }
    $filters['organization_memberships']='1=1'
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
function Organization-Probe([string]$Mode, [hashtable]$DatabaseEnv = $dbEnv, [string]$Timezone = 'Europe/Madrid') {
    Assert-That ($Mode -cin @('contracts','seed','historical')) 'Unexpected Organization probe mode'
    Assert-That ($Timezone -cin @('Asia/Kathmandu','Europe/Madrid')) 'Unexpected Organization timezone'
    $environment=@{} + $DatabaseEnv + @{F3_ORG_MODE=$Mode;F3_TIMEZONE=$Timezone;F3_EXPECTED_VERSIONS=($expectedVersions -join ',')}
    $output=Maven $newBackend "-Dtest=com.smartaudits.v2.model.OrganizationMariaDbProbe -DargLine=-Duser.timezone=$Timezone test" $environment
    Assert-That ($output -match 'Tests run: 1, Failures: 0, Errors: 0, Skipped: 0') 'Organization MariaDB probe did not pass'
    foreach ($line in ($output -split '\r?\n')) {
        if ($line -match '^(ORGANIZATION (CONTRACTS|SENTINEL) PASS:|HISTORICAL V4 PASS:)') { Pass $line }
    }
}
function Historical-Organization {
    # A second schema in the same owned container, never an external DB target.
    $historyDb='f3_harness_' + [Guid]::NewGuid().ToString('N')
    Assert-That ($historyDb -cmatch '^f3_harness_[a-f0-9]{32}$') 'Unsafe historical fixture name'
    $owner=Docker @('container','inspect','--format','{{index .Config.Labels "smartaudits.f3-harness"}}',$resource)
    Assert-That ($owner -ceq $runId) 'Historical schema requires exact owned container label'
    $null=Docker @('exec','-e','MYSQL_PWD',$resource,'mariadb','--host=127.0.0.1','-uroot','-e',
        "CREATE DATABASE $historyDb; GRANT ALL ON $historyDb.* TO 'f3_harness_user'@'%';") @{MYSQL_PWD=$rootPassword}
    $historyEnv=@{} + $dbEnv
    $historyEnv.F3_DB_NAME=$historyDb
    $historyEnv.F3_DB_URL="jdbc:mariadb://127.0.0.1:$dbPort/$historyDb"
    Organization-Probe 'historical' $historyEnv
    Pass 'Historical schema is owned by this run and will be removed with its container/volume'
}
function Membership-Probe([string]$Mode, [hashtable]$DatabaseEnv = $dbEnv, [string]$Timezone = 'Europe/Madrid') {
    Assert-That ($Mode -cin @('contracts','seed','upgrade')) 'Unexpected Membership probe mode'
    Assert-That ($Timezone -cin @('Asia/Kathmandu','Europe/Madrid')) 'Unexpected Membership timezone'
    $environment=@{} + $DatabaseEnv + @{F3_MEMBERSHIP_MODE=$Mode;F3_TIMEZONE=$Timezone;
        F3_EXPECTED_VERSIONS=($expectedVersions -join ',');F3_MEMBERSHIP_HARNESS_RUN_ID=$runId}
    $output=Maven $newBackend "-Dtest=com.smartaudits.v2.model.OrganizationMembershipMariaDbProbe -DargLine=-Duser.timezone=$Timezone test" $environment
    Assert-That ($output -match 'Tests run: 1, Failures: 0, Errors: 0, Skipped: 0') 'Membership MariaDB probe did not pass'
    $marker = switch ($Mode) { 'contracts' {'MEMBERSHIP CONTRACTS PASS:'}; 'seed' {'MEMBERSHIP SENTINELS PASS:'}; 'upgrade' {'MEMBERSHIP UPGRADE PASS:'} }
    Assert-That ($output.Contains($marker)) 'Missing Membership proof marker'
    foreach ($line in ($output -split '\r?\n')) { if ($line.StartsWith($marker)) { Pass $line } }
}
function Populated-V4-Upgrade {
    $upgradeDb='f3_harness_' + [Guid]::NewGuid().ToString('N')
    Assert-That ($upgradeDb -cmatch '^f3_harness_[a-f0-9]{32}$') 'Unsafe V4 upgrade schema'
    $owner=Docker @('container','inspect','--format','{{index .Config.Labels "smartaudits.f3-harness"}}',$resource)
    Assert-That ($owner -ceq $runId) 'V4 upgrade requires exact owned container label'
    $null=Docker @('exec','-e','MYSQL_PWD',$resource,'mariadb','--host=127.0.0.1','-uroot','-e',
        "CREATE DATABASE $upgradeDb; GRANT ALL ON $upgradeDb.* TO 'f3_harness_user'@'%';") @{MYSQL_PWD=$rootPassword}
    $upgradeEnv=@{} + $dbEnv
    $upgradeEnv.F3_DB_NAME=$upgradeDb
    $upgradeEnv.F3_DB_URL="jdbc:mariadb://127.0.0.1:$dbPort/$upgradeDb"
    Membership-Probe 'upgrade' $upgradeEnv
    Pass 'Populated V4 upgrade schema belongs to this run and is removed with its container/volume'
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
        '(?m)\bDbValidate\s*:\s*(Successfully validated ' + $expectedVersions.Count + ' migrations)\b'
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
    Assert-History
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
    Assert-That (-not ($TimestampOnly -and $SelfTestOnly)) 'Choose only one directed mode'
    Assert-That ($env:OS -eq 'Windows_NT') 'This harness uses the existing Windows PowerShell/JDK infrastructure'
    Assert-That ($resource -cmatch '^smartaudits-f3-harness-[a-f0-9]{32}$') 'Unsafe run name'
    Assert-Migrations
    $head = Native 'Git HEAD' 'git' @('rev-parse','HEAD')
    $baseline = Native 'Git baseline' 'git' @('rev-parse',"$RollbackRelease^{commit}")
    Assert-That ($baseline -ceq $approvedReleases[$RollbackRelease]) 'Rollback release does not match reviewed full commit'
    $null = Native 'Rollback ancestry' 'git' @('merge-base','--is-ancestor',$baseline,$head)
    Assert-VersionedSources
    Assert-Candidates
    Assert-That (-not (Test-Path -LiteralPath $tempRoot)) 'Temporary directory already exists'
    $null = New-Item -ItemType Directory -Path $tempRoot
    $tempCreated=$true
    Self-Tests
    Real-Migrations
    if (-not $SelfTestOnly) {
    Preflight
    $dockerReady=$true
    $left = Leftovers
    Assert-That (-not $left) "Pre-existing harness resources: $left (not removed)"
    $habitualBefore = Habitual-State
    Pass 'Habitual container/volume identity captured read-only (no connection)'
    $rootPassword=New-Secret
    $dbPassword=New-Secret
    $jwt=New-Secret
    $e2ePassword=New-Secret
    $adminEmail="admin-$runId@example.invalid"
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
    Assert-VersionedSources
    if ($IncludePendingMembership) {
        Assert-Candidates
        foreach ($path in $candidatePaths) {
            $target=Join-Path $tempRoot "new/$path"
            $null=New-Item -ItemType Directory -Force -Path (Split-Path $target)
            Copy-Item -LiteralPath (Join-Path $repo $path) -Destination $target
            Assert-That ((Get-FileHash -LiteralPath $target -Algorithm SHA256).Hash -ceq $candidateHashes[$path]) 'Copied candidate hash mismatch'
        }
    }
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
    $futurePath=Join-Path $newBackend 'src/main/resources/db/migration/V9000__f3_harness_future_probe.sql'
    [IO.File]::WriteAllText($futurePath,$futureSql,$utf8)
    Assert-Expansion $futurePath
    Pass 'Synthetic migration V9000: one CREATE TABLE; no DML or legacy targets (temporary copy only)'
    $null = Maven $oldBackend '-DskipTests package'
    Pass "Exact baseline built from Git archive: $baseline"
    $null = Maven $newBackend '-DskipTests package'
    Pass "New JAR from HEAD $head + V9000; explicit Membership candidates=$IncludePendingMembership"
    $oldJar=Join-Path $oldBackend 'target/smartaudits-backend-1.0.0.jar'
    $newJar=Join-Path $newBackend 'target/smartaudits-backend-1.0.0.jar'
    Assert-Migrations -Jar $newJar # OLD may have V1-V3 or V1-V4; never require V5 in OLD.
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
    Historical-Organization
    Populated-V4-Upgrade
    $backend=Start-Backend $newJar $true 'NEW initial'
    Assert-History
    Stop-Backend $backend
    Probe 'seed'
    foreach ($zone in @('Asia/Kathmandu','Europe/Madrid')) { Organization-Probe 'contracts' $dbEnv $zone }
    # Organization contracts delete their fixtures before Membership creates any dependent rows.
    foreach ($zone in @('Asia/Kathmandu','Europe/Madrid')) { Membership-Probe 'contracts' $dbEnv $zone }
    Organization-Probe 'seed'
    Assert-That ((Sql 'SELECT COUNT(*) FROM organizations') -eq '1') 'Expected exactly one real Organization sentinel'
    Membership-Probe 'seed'
    Assert-That ((Sql 'SELECT COUNT(*) FROM organization_memberships') -eq '2') 'Expected ACTIVE and REVOKED Membership sentinels'
    $before=Snapshot
    foreach ($table in $before.Keys) { Pass "PRE $table count|data SHA256|structure SHA256=$($before[$table])" }
    $backend=Start-Backend $oldJar $true "OLD exact $RollbackRelease Flyway ON"
    Assert-Snapshot $before 'OLD ON startup'
    Assert-Frontend
    Preserve-Vite
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
    Pass "LEGACY E2E: 4/4 PASS (exact $RollbackRelease; versions=$($expectedVersions -join ','); isolated MariaDB 11.2)"
    Force-Stop $web
    Assert-Snapshot $before 'POST rollback ON + E2E'
    # Verify the actual E2E-created rows separately, not by weakening sentinel comparisons.
    Assert-That ((Sql "SELECT COUNT(*) FROM usuarios WHERE email LIKE 'client-%-$runId@example.invalid'") -eq '3') 'Expected three E2E-created clients'
    Assert-That ((Sql "SELECT COUNT(*) FROM auditorias a JOIN usuarios u ON u.id=a.usuario_id WHERE u.email='client-audit-$runId@example.invalid'") -eq '1') 'Expected one E2E-created audit'
    Stop-Backend $backend
    $backend=Start-Backend $oldJar $false "OLD exact $RollbackRelease Flyway OFF"
    Legacy-Access $backend.Port
    Assert-Snapshot $before 'POST rollback OFF'
    Stop-Backend $backend
    $backend=Start-Backend $newJar $true 'NEW return forward'
    Assert-Snapshot $before 'NEW return forward'
    Stop-Backend $backend
    Timestamp-Matrix
    Assert-Snapshot $before 'POST timestamp'
    }
    }
    Assert-Migrations
    Assert-Candidates
    Assert-VersionedSources
    Assert-That ((Native 'Git HEAD verification' 'git' @('rev-parse','HEAD')) -ceq $head) 'Main checkout moved'
    Pass 'V1/V2/V3/V4/V5 bytes and normalized hashes intact'
} catch {
    $failure=Protect $_.Exception.Message
    Write-Host "FAIL $failure"
} finally {
    if ($null -ne $backend -and -not $backend.Process.HasExited) {
        try { Stop-Backend $backend } catch { $cleanupErrors.Add('Graceful backend shutdown failed; attempting owned PID teardown') }
    }
    foreach ($child in @($children.ToArray())) {
        try {
            Force-Stop $child
            $null = Output-Of $child # Also bound drains of long-lived server output.
        } catch { $cleanupErrors.Add("Owned process/output cleanup failed: $($child.Name): $($_.Exception.Message)") }
    }
    try { Restore-Vite } catch {
        $preserveTemp=$true
        $cleanupErrors.Add("Vite restoration failed; recovery directory retained: $tempRoot; $($_.Exception.Message)")
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
    if ($tempCreated -and -not $preserveTemp) {
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
if ($SelfTestOnly) { Write-Host 'SMARTAUDITS F3.3 SELF-TESTS PASS' }
elseif ($TimestampOnly) { Write-Host 'SMARTAUDITS F3.3 TIMESTAMP-ONLY PASS' }
else { Write-Host 'SMARTAUDITS F3.3 HARNESS PASS' }
exit 0
