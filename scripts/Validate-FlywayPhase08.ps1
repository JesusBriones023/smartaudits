param(
    [switch]$KeepContainers,
    [string]$SnapshotPath = "$env:LOCALAPPDATA\SmartAuditsBackups\phase08-schema-before.sql",
    [string]$ExpectedSnapshotSha256 = 'B9B1B634F5D4D4971465519983082ACFBEB514FA49CD50F61218F442ABF63A65'
)

$ErrorActionPreference = 'Stop'

$repo = Split-Path -Parent $PSScriptRoot
$jar = Join-Path $repo 'smartaudits-backend/target/smartaudits-backend-1.0.0.jar'
$jarRelative = 'smartaudits-backend/target/smartaudits-backend-1.0.0.jar'
$migrationDir = Join-Path $repo 'smartaudits-backend/src/main/resources/db/migration'

$runId = [guid]::NewGuid().ToString('N').Substring(0, 12)

$emptyName = "sa08_empty_$runId"
$legacyName = "sa08_legacy_$runId"

$emptyVolumeName = "sa08_empty_data_$runId"
$legacyVolumeName = "sa08_legacy_data_$runId"

$emptyContainerId = $null
$legacyContainerId = $null

$ownedContainerRefs = New-Object System.Collections.Generic.List[string]
$ownedVolumeNames = New-Object System.Collections.Generic.List[string]
$startedProcessIds = New-Object System.Collections.Generic.List[int]

$cleanupErrors = New-Object System.Collections.Generic.List[string]
$validationError = $null
$validationCompleted = $false

$dangerousJavaEnvironment = @(
    'JAVA_TOOL_OPTIONS',
    'JDK_JAVA_OPTIONS',
    '_JAVA_OPTIONS'
)

$managedEnvironment = @(
    'DB_URL',
    'DB_USERNAME',
    'DB_PASSWORD',
    'JWT_SECRET',
    'JWT_ISSUER',
    'JWT_AUDIENCE',
    'APP_ADMIN_BOOTSTRAP_ENABLED',
    'APP_DATA_INITIALIZER_ENABLED',
    'SPRING_JPA_HIBERNATE_DDL_AUTO',
    'FLYWAY_ENABLED',
    'FLYWAY_BASELINE_ON_MIGRATE',
    'SERVER_PORT',
    'SERVER_ADDRESS',
    'SPRING_DATASOURCE_URL',
    'SPRING_DATASOURCE_USERNAME',
    'SPRING_DATASOURCE_PASSWORD',
    'SPRING_FLYWAY_URL',
    'SPRING_FLYWAY_USER',
    'SPRING_FLYWAY_PASSWORD',
    'DB_ROOT_PASSWORD'
)

$expectedDomainTables = @(
    'auditorias',
    'historial_acciones_admin',
    'historial_auditorias',
    'incidencias',
    'resultados',
    'roles',
    'usuarios',
    'usuarios_roles'
) | Sort-Object

$expectedManagedTables = @(
    'auditorias',
    'flyway_schema_history',
    'historial_acciones_admin',
    'historial_auditorias',
    'incidencias',
    'resultados',
    'roles',
    'usuarios',
    'usuarios_roles'
) | Sort-Object

function Write-Step {
    param([string]$Message)

    Write-Host ''
    Write-Host "=== $Message ==="
}

function Assert-Condition {
    param(
        [bool]$Condition,
        [string]$Message
    )

    if (-not $Condition) {
        throw $Message
    }
}

function Assert-NoInheritedOverrides {
    $conflicts = @(
        Get-ChildItem Env: |
            Where-Object {
                $_.Name -like 'SPRING_*' -or
                $dangerousJavaEnvironment -contains $_.Name
            } |
            Select-Object -ExpandProperty Name |
            Sort-Object -Unique
    )

    if ($conflicts.Count -gt 0) {
        throw (
            'Unsafe inherited configuration detected before validation: ' +
            ($conflicts -join ', ')
        )
    }
}

function New-RandomPassword {
    return (
        [guid]::NewGuid().ToString('N') +
        [guid]::NewGuid().ToString('N')
    )
}

function New-RandomJwt {
    $bytes = New-Object byte[] 32
    $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()

    try {
        $rng.GetBytes($bytes)
        return [Convert]::ToBase64String($bytes)
    }
    finally {
        $rng.Dispose()
        [Array]::Clear($bytes, 0, $bytes.Length)
    }
}

function Get-FreeTcpPort {
    $listener = [System.Net.Sockets.TcpListener]::new(
        [System.Net.IPAddress]::Loopback,
        0
    )

    try {
        $listener.Start()

        return (
            [System.Net.IPEndPoint]$listener.LocalEndpoint
        ).Port
    }
    finally {
        $listener.Stop()
    }
}

function Invoke-Native {
    param(
        [string]$FilePath,
        [string[]]$Arguments,
        [string]$Operation
    )

    $output = @(& $FilePath @Arguments 2>&1)
    $exitCode = $LASTEXITCODE

    if ($exitCode -ne 0) {
        throw "$Operation failed with exit code $exitCode."
    }

    return $output
}

function Invoke-DockerSql {
    param(
        [string]$ContainerId,
        [string]$Sql
    )

    $output = @(
        $Sql |
            & docker exec -i $ContainerId sh -c `
                'MYSQL_PWD="$MARIADB_PASSWORD" mariadb -u"$MARIADB_USER" "$MARIADB_DATABASE" -N' `
                2>&1
    )

    if ($LASTEXITCODE -ne 0) {
        throw 'MariaDB SQL command failed in validation container.'
    }

    return $output
}

function Invoke-DockerRootImport {
    param(
        [string]$ContainerId,
        [string]$ContainerFile
    )

    & docker exec $ContainerId sh -c `
        "MYSQL_PWD=`"`$MARIADB_ROOT_PASSWORD`" mariadb -uroot `"`$MARIADB_DATABASE`" < $ContainerFile" `
        | Out-Null

    if ($LASTEXITCODE -ne 0) {
        throw 'MariaDB schema import failed in validation container.'
    }
}

function Wait-Healthy {
    param([string]$ContainerId)

    for ($i = 0; $i -lt 45; $i++) {
        $health = @(
            & docker inspect `
                --format '{{.State.Health.Status}}' `
                $ContainerId `
                2>$null
        )

        if (
            $LASTEXITCODE -eq 0 -and
            $health.Count -gt 0 -and
            $health[0].Trim() -eq 'healthy'
        ) {
            return
        }

        Start-Sleep -Seconds 2
    }

    throw "Container $ContainerId did not become healthy."
}

function New-OwnedVolume {
    param(
        [string]$Name,
        [string]$Kind
    )

    if (-not $ownedVolumeNames.Contains($Name)) {
        # Registrar antes de crear para cubrir fallos parciales del comando.
        $ownedVolumeNames.Add($Name)
    }

    Invoke-Native `
        -FilePath 'docker' `
        -Arguments @(
            'volume',
            'create',
            '--label', 'smartaudits.validation=phase08',
            '--label', "smartaudits.validation.run=$runId",
            '--label', "smartaudits.validation.kind=$Kind",
            $Name
        ) `
        -Operation "Create $Kind MariaDB validation volume" |
        Out-Null
}

function Remove-OwnedVolume {
    param([string]$VolumeName)

    if ([string]::IsNullOrWhiteSpace($VolumeName)) {
        return
    }

    $previousErrorActionPreference = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'

    try {
        $inspectOutput = @(
            & docker volume inspect $VolumeName 2>&1
        )

        $inspectExitCode = $LASTEXITCODE
        $inspectText = $inspectOutput -join "`n"

        if ($inspectExitCode -ne 0) {
            if ($inspectText -match '(?i)no such volume') {
                return
            }

            throw "Could not inspect owned volume $VolumeName before cleanup."
        }

        try {
            $inspect = $inspectText | ConvertFrom-Json
        }
        catch {
            throw "Could not parse metadata for owned volume $VolumeName."
        }

        if (
            $null -eq $inspect -or
            $inspect.Count -eq 0 -or
            $null -eq $inspect[0].Labels
        ) {
            throw "Ownership metadata missing for volume $VolumeName."
        }

        $label = $inspect[0].Labels.'smartaudits.validation.run'

        if ($label -cne $runId) {
            throw "Refusing to remove volume $VolumeName because ownership does not match this run."
        }

        $removeOutput = @(
            & docker volume rm -f $VolumeName 2>&1
        )

        if ($LASTEXITCODE -ne 0) {
            throw "Failed to remove owned volume $VolumeName."
        }

        $verifyOutput = @(
            & docker volume inspect $VolumeName 2>&1
        )

        $verifyExitCode = $LASTEXITCODE
        $verifyText = $verifyOutput -join "`n"

        if ($verifyExitCode -eq 0) {
            throw "Owned volume $VolumeName still exists after cleanup."
        }

        if ($verifyText -notmatch '(?i)no such volume') {
            throw "Unable to verify removal of owned volume $VolumeName."
        }
    }
    finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }
}

function Remove-OwnedContainer {
    param([string]$ContainerId)

    if ([string]::IsNullOrWhiteSpace($ContainerId)) {
        return
    }

    # PowerShell 5 puede convertir stderr de programas nativos en errores
    # terminantes cuando ErrorActionPreference=Stop. Para docker inspect/rm
    # necesitamos evaluar nosotros mismos el exit code y stderr.
    $previousErrorActionPreference = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'

    try {
        $inspectOutput = @(
            & docker inspect $ContainerId 2>&1
        )

        $inspectExitCode = $LASTEXITCODE
        $inspectText = $inspectOutput -join "`n"

        if ($inspectExitCode -ne 0) {
            if ($inspectText -match '(?i)no such (object|container)') {
                return
            }

            throw "Could not inspect owned container $ContainerId before cleanup."
        }

        try {
            $inspect = $inspectText | ConvertFrom-Json
        }
        catch {
            throw "Could not parse metadata for owned container $ContainerId."
        }

        if (
            $null -eq $inspect -or
            $inspect.Count -eq 0 -or
            $null -eq $inspect[0].Config.Labels
        ) {
            throw "Ownership metadata missing for container $ContainerId."
        }

        $label = $inspect[0].Config.Labels.'smartaudits.validation.run'

        if ($label -ne $runId) {
            throw "Refusing to remove container $ContainerId because ownership does not match this run."
        }

        $removeOutput = @(
            & docker rm -f $ContainerId 2>&1
        )

        $removeExitCode = $LASTEXITCODE

        if ($removeExitCode -ne 0) {
            throw "Failed to remove owned container $ContainerId."
        }

        $verifyOutput = @(
            & docker inspect $ContainerId 2>&1
        )

        $verifyExitCode = $LASTEXITCODE
        $verifyText = $verifyOutput -join "`n"

        if ($verifyExitCode -eq 0) {
            throw "Owned container $ContainerId still exists after cleanup."
        }

        if ($verifyText -notmatch '(?i)no such (object|container)') {
            throw "Unable to verify removal of owned container $ContainerId."
        }
    }
    finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }
}
function Stop-ProcessTree {
    param([int]$ProcessId)

    if ($ProcessId -le 0) {
        return
    }

    $process = Get-Process `
        -Id $ProcessId `
        -ErrorAction SilentlyContinue

    if (-not $process) {
        return
    }

    $taskkill = Get-Command taskkill.exe `
        -ErrorAction SilentlyContinue

    if ($taskkill) {
        try {
            Start-Process `
                -FilePath $taskkill.Source `
                -ArgumentList @('/PID', "$ProcessId", '/T', '/F') `
                -WindowStyle Hidden `
                -Wait `
                -ErrorAction SilentlyContinue |
                Out-Null
        }
        catch {
            # Se verifica explícitamente el resultado más abajo.
        }
    }

    Start-Sleep -Milliseconds 200

    $stillRunning = Get-Process `
        -Id $ProcessId `
        -ErrorAction SilentlyContinue

    if ($stillRunning) {
        Stop-Process `
            -Id $ProcessId `
            -Force `
            -ErrorAction SilentlyContinue
    }

    for ($i = 0; $i -lt 20; $i++) {
        $stillRunning = Get-Process `
            -Id $ProcessId `
            -ErrorAction SilentlyContinue

        if (-not $stillRunning) {
            return
        }

        Start-Sleep -Milliseconds 100
    }

    throw "Process $ProcessId is still running after cleanup."
}

function Save-Environment {
    $snapshot = @{}

    foreach ($name in $managedEnvironment) {
        $snapshot[$name] = @{
            Exists = Test-Path "Env:$name"
            Value = [Environment]::GetEnvironmentVariable(
                $name,
                'Process'
            )
        }
    }

    return $snapshot
}

function Restore-Environment {
    param([hashtable]$Snapshot)

    foreach ($name in $Snapshot.Keys) {
        $entry = $Snapshot[$name]

        if ($entry.Exists) {
            [Environment]::SetEnvironmentVariable(
                $name,
                $entry.Value,
                'Process'
            )
        }
        else {
            [Environment]::SetEnvironmentVariable(
                $name,
                $null,
                'Process'
            )
        }
    }
}

function Set-ChildEnvironment {
    param(
        [string]$DbUrl,
        [string]$DbUser,
        [string]$DbPassword,
        [string]$JwtSecret,
        [string]$Issuer,
        [string]$Audience,
        [int]$ApiPort,
        [bool]$Baseline
    )

    [Environment]::SetEnvironmentVariable(
        'DB_URL',
        $DbUrl,
        'Process'
    )

    [Environment]::SetEnvironmentVariable(
        'DB_USERNAME',
        $DbUser,
        'Process'
    )

    [Environment]::SetEnvironmentVariable(
        'DB_PASSWORD',
        $DbPassword,
        'Process'
    )

    [Environment]::SetEnvironmentVariable(
        'JWT_SECRET',
        $JwtSecret,
        'Process'
    )

    [Environment]::SetEnvironmentVariable(
        'JWT_ISSUER',
        $Issuer,
        'Process'
    )

    [Environment]::SetEnvironmentVariable(
        'JWT_AUDIENCE',
        $Audience,
        'Process'
    )

    [Environment]::SetEnvironmentVariable(
        'APP_ADMIN_BOOTSTRAP_ENABLED',
        'false',
        'Process'
    )

    [Environment]::SetEnvironmentVariable(
        'APP_DATA_INITIALIZER_ENABLED',
        'false',
        'Process'
    )

    [Environment]::SetEnvironmentVariable(
        'SPRING_JPA_HIBERNATE_DDL_AUTO',
        'validate',
        'Process'
    )

    [Environment]::SetEnvironmentVariable(
        'FLYWAY_ENABLED',
        'true',
        'Process'
    )

    [Environment]::SetEnvironmentVariable(
        'FLYWAY_BASELINE_ON_MIGRATE',
        $Baseline.ToString().ToLowerInvariant(),
        'Process'
    )

    [Environment]::SetEnvironmentVariable(
        'SERVER_PORT',
        "$ApiPort",
        'Process'
    )

    [Environment]::SetEnvironmentVariable(
        'SERVER_ADDRESS',
        '127.0.0.1',
        'Process'
    )

    [Environment]::SetEnvironmentVariable(
        'SPRING_DATASOURCE_URL',
        $DbUrl,
        'Process'
    )

    [Environment]::SetEnvironmentVariable(
        'SPRING_DATASOURCE_USERNAME',
        $DbUser,
        'Process'
    )

    [Environment]::SetEnvironmentVariable(
        'SPRING_DATASOURCE_PASSWORD',
        $DbPassword,
        'Process'
    )

    [Environment]::SetEnvironmentVariable(
        'SPRING_FLYWAY_URL',
        $DbUrl,
        'Process'
    )

    [Environment]::SetEnvironmentVariable(
        'SPRING_FLYWAY_USER',
        $DbUser,
        'Process'
    )

    [Environment]::SetEnvironmentVariable(
        'SPRING_FLYWAY_PASSWORD',
        $DbPassword,
        'Process'
    )

    # Nunca propagar root al backend.
    [Environment]::SetEnvironmentVariable(
        'DB_ROOT_PASSWORD',
        $null,
        'Process'
    )
}

function Start-ValidationBackend {
    param(
        [string]$DbUrl,
        [string]$DbUser,
        [string]$DbPassword,
        [string]$JwtSecret,
        [string]$Issuer,
        [string]$Audience,
        [int]$ApiPort,
        [bool]$Baseline,
        [string]$LogPrefix
    )

    $snapshot = Save-Environment

    try {
        Set-ChildEnvironment `
            -DbUrl $DbUrl `
            -DbUser $DbUser `
            -DbPassword $DbPassword `
            -JwtSecret $JwtSecret `
            -Issuer $Issuer `
            -Audience $Audience `
            -ApiPort $ApiPort `
            -Baseline $Baseline

        $stdout = Join-Path `
            ([System.IO.Path]::GetTempPath()) `
            "$LogPrefix-$runId-out.log"

        $stderr = Join-Path `
            ([System.IO.Path]::GetTempPath()) `
            "$LogPrefix-$runId-err.log"

        Remove-Item $stdout `
            -Force `
            -ErrorAction SilentlyContinue

        Remove-Item $stderr `
            -Force `
            -ErrorAction SilentlyContinue

        $arguments = @(
            '-Dspring.main.add-command-line-properties=true',
            '-jar',
            $jarRelative,
            '--spring.config.location=classpath:/application.properties',
            "--spring.datasource.url=$DbUrl",
            "--spring.datasource.username=$DbUser",
            '--spring.jpa.hibernate.ddl-auto=validate',
            '--spring.flyway.enabled=true',
            "--spring.flyway.baseline-on-migrate=$($Baseline.ToString().ToLowerInvariant())",
            '--spring.flyway.baseline-version=1',
            '--spring.flyway.target=2',
            '--spring.flyway.locations=classpath:db/migration',
            '--spring.flyway.validate-on-migrate=true',
            '--spring.flyway.clean-disabled=true',
            '--spring.sql.init.mode=never',
            '--app.data.initializer.enabled=false',
            '--app.admin.bootstrap.enabled=false',
            '--server.address=127.0.0.1',
            "--server.port=$ApiPort"
        )

        $platformOptions = @{}
        if ($env:OS -eq 'Windows_NT') {
            $platformOptions.WindowStyle = 'Hidden'
        }
        $process = Start-Process @platformOptions `
            -FilePath 'java' `
            -ArgumentList $arguments `
            -WorkingDirectory $repo `
            -RedirectStandardOutput $stdout `
            -RedirectStandardError $stderr `
            -PassThru

        $startedProcessIds.Add($process.Id)

        return @{
            Process = $process
            StdOut = $stdout
            StdErr = $stderr
        }
    }
    finally {
        Restore-Environment -Snapshot $snapshot
    }
}

function Get-CombinedLog {
    param([hashtable]$Backend)

    $parts = New-Object System.Collections.Generic.List[string]

    if (Test-Path $Backend.StdOut) {
        $parts.Add(
            (Get-Content $Backend.StdOut -Raw)
        )
    }

    if (Test-Path $Backend.StdErr) {
        $parts.Add(
            (Get-Content $Backend.StdErr -Raw)
        )
    }

    return ($parts -join "`n")
}

function Wait-BackendStarted {
    param(
        [hashtable]$Backend,
        [int]$TimeoutSeconds = 60
    )

    for ($i = 0; $i -lt $TimeoutSeconds; $i++) {
        $Backend.Process.Refresh()

        $log = Get-CombinedLog -Backend $Backend

        if ($log -match 'Started SmartAuditsApplication') {
            return
        }

        if ($Backend.Process.HasExited) {
            throw (
                'Validation backend exited before startup. Logs: ' +
                $Backend.StdOut + ' ; ' + $Backend.StdErr
            )
        }

        Start-Sleep -Seconds 1
    }

    throw (
        'Validation backend startup timed out. Logs: ' +
        $Backend.StdOut + ' ; ' + $Backend.StdErr
    )
}

function Wait-ExpectedFlywayFailure {
    param(
        [hashtable]$Backend,
        [int]$TimeoutSeconds = 45
    )

    for ($i = 0; $i -lt $TimeoutSeconds; $i++) {
        $Backend.Process.Refresh()

        $log = Get-CombinedLog -Backend $Backend

        if ($log -match 'Started SmartAuditsApplication') {
            throw 'Historical schema unexpectedly started without baseline.'
        }

        if ($Backend.Process.HasExited) {
            $exitCode = $Backend.Process.ExitCode

            if ($exitCode -eq 0) {
                throw 'Historical schema without baseline exited with code 0 instead of failing.'
            }

            $hasNonEmptySchema = (
                $log -match '(?is)Found non-empty schema'
            )

            $hasNoHistoryTable = (
                $log -match '(?is)but no schema history table'
            )

            $hasBaselineHint = (
                $log -match '(?is)baselineOnMigrate.*true'
            )

            if (
                $hasNonEmptySchema -and
                $hasNoHistoryTable -and
                $hasBaselineHint
            ) {
                return
            }

            throw (
                'Historical schema failed, but not with the expected ' +
                'Flyway non-empty-schema-without-history diagnostic. ' +
                'Logs: ' + $Backend.StdOut + ' ; ' + $Backend.StdErr
            )
        }

        Start-Sleep -Seconds 1
    }

    throw 'Historical schema without baseline did not fail within the expected time.'
}

function Get-HttpStatus {
    param([string]$Uri)

    $request = [System.Net.HttpWebRequest]::Create($Uri)
    $request.Method = 'GET'
    $request.Timeout = 5000
    $request.ReadWriteTimeout = 5000

    try {
        $response = $request.GetResponse()

        try {
            return [int]$response.StatusCode
        }
        finally {
            $response.Close()
        }
    }
    catch [System.Net.WebException] {
        if ($_.Exception.Response) {
            $response = $_.Exception.Response

            try {
                return [int]$response.StatusCode
            }
            finally {
                $response.Close()
            }
        }

        throw
    }
}

function Assert-ExactTables {
    param(
        [string]$ContainerId,
        [string[]]$Expected
    )

    $actual = @(
        Invoke-DockerSql `
            -ContainerId $ContainerId `
            -Sql 'SHOW TABLES;' |
            ForEach-Object { "$_".Trim() } |
            Where-Object { $_ } |
            Sort-Object
    )

    $difference = @(
        Compare-Object `
            -ReferenceObject $Expected `
            -DifferenceObject $actual
    )

    if ($difference.Count -gt 0) {
        throw (
            'Unexpected table inventory: ' +
            (($actual) -join ', ')
        )
    }
}

function Assert-Sentinel {
    param([string]$ContainerId)

    $user = @(
        Invoke-DockerSql `
            -ContainerId $ContainerId `
            -Sql "SELECT CONCAT(id,'|',email,'|',nombre,'|',password,'|',role,'|',IF(activo,1,0),'|',IF(protegido,1,0),'|',token_version,'|',row_version,'|',DATE_FORMAT(fecha_registro,'%Y-%m-%d %H:%i:%s.%f')) FROM usuarios WHERE id=900001;"
    )

    Assert-Condition `
        -Condition (
            $user.Count -eq 1 -and
            $user[0].Trim() -ceq '900001|sentinel@phase08.invalid|Sentinel Phase08|not-a-real-password|CLIENTE|1|0|7|3|2001-02-03 04:05:06.123456'
        ) `
        -Message 'Sentinel user changed unexpectedly.'

    $roles = @(
        Invoke-DockerSql `
            -ContainerId $ContainerId `
            -Sql "SELECT CONCAT(id,'|',descripcion,'|',nombre) FROM roles WHERE id IN (900001,900002) ORDER BY id;"
    )

    $expectedRoles = @(
        '900001|Phase08 sentinel CLIENTE|CLIENTE',
        '900002|Phase08 sentinel ADMIN|ADMIN'
    )

    $roleDifference = @(
        Compare-Object `
            -ReferenceObject $expectedRoles `
            -DifferenceObject @(
                $roles | ForEach-Object { [string]$_ }
            ) `
            -CaseSensitive
    )

    Assert-Condition `
        -Condition ($roleDifference.Count -eq 0) `
        -Message 'Sentinel roles changed unexpectedly.'

    $relation = @(
        Invoke-DockerSql `
            -ContainerId $ContainerId `
            -Sql "SELECT CONCAT(usuario_id,'|',rol_id) FROM usuarios_roles WHERE usuario_id=900001;"
    )

    Assert-Condition `
        -Condition (
            $relation.Count -eq 1 -and
            $relation[0].Trim() -ceq '900001|900001'
        ) `
        -Message 'Sentinel user-role relation changed unexpectedly.'

    $counts = @(
        Invoke-DockerSql `
            -ContainerId $ContainerId `
            -Sql "SELECT CONCAT((SELECT COUNT(*) FROM usuarios),'|',(SELECT COUNT(*) FROM roles),'|',(SELECT COUNT(*) FROM usuarios_roles));"
    )

    Assert-Condition `
        -Condition (
            $counts.Count -eq 1 -and
            $counts[0].Trim() -ceq '1|2|1'
        ) `
        -Message 'Sentinel table counts changed unexpectedly.'
}

function Assert-ProvenanceSchema {
    param([string]$ContainerId)

    $actual = @(Invoke-DockerSql -ContainerId $ContainerId -Sql @'
SELECT CONCAT(column_name,'|',column_type,'|',is_nullable,'|',IF(column_default IS NULL,'NONE',column_default))
FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='auditorias'
AND column_name IN ('version_motor','version_reglas','fecha_analisis','tipo_fuente') ORDER BY column_name;
'@)
    $expected = @('fecha_analisis|datetime(6)|NO|NONE', 'tipo_fuente|varchar(16)|NO|NONE',
        'version_motor|varchar(50)|NO|NONE', 'version_reglas|varchar(50)|NO|NONE')
    Assert-Condition -Condition (($actual -join "`n") -ceq ($expected -join "`n")) `
        -Message 'V2 provenance column types, nullability or defaults differ.'
}

function Get-AuditDataSnapshot {
    param([string]$ContainerId, [switch]$IncludeProvenance)

    # Hash every old column, preserving NULL, case, whitespace, IDs and relationships.
    # The second-start snapshot additionally includes the new metadata and new audit.
    $extra = ''
    if ($IncludeProvenance) { $extra = ',version_motor,version_reglas,fecha_analisis,tipo_fuente' }
    return @(Invoke-DockerSql -ContainerId $ContainerId -Sql @"
SELECT CONCAT('audit|',id,'|',SHA2(JSON_ARRAY(id,fecha_creacion,puntuacion_riesgo,resultado_json,texto_original,tipo_documento,titulo,url_opcional,usuario_id,estado$extra),256)) FROM auditorias ORDER BY id;
SELECT CONCAT('result|',id,'|',SHA2(JSON_ARRAY(id,fecha_resultado,puntuacion_cumplimiento,recomendaciones_generales,resumen_general,auditoria_id),256)) FROM resultados ORDER BY id;
SELECT CONCAT('incident|',id,'|',SHA2(JSON_ARRAY(id,categoria,descripcion,evidencia,impacto,recomendacion,severidad,auditoria_id),256)) FROM incidencias ORDER BY id;
SELECT CONCAT('history|',id,'|',SHA2(JSON_ARRAY(id,accion,fecha_acceso,ip_acceso,auditoria_id,usuario_id),256)) FROM historial_auditorias ORDER BY id;
SELECT CONCAT('admin|',id,'|',SHA2(JSON_ARRAY(id,admin_email,admin_nombre,detalles,fecha,objetivo_email,objetivo_nombre,tipo_accion,admin_id,usuario_objetivo_id),256)) FROM historial_acciones_admin ORDER BY id;
"@)
}

function Assert-AuditDataSnapshot {
    param([string]$ContainerId, [string[]]$Expected, [switch]$IncludeProvenance)

    $actual = @(Get-AuditDataSnapshot -ContainerId $ContainerId -IncludeProvenance:$IncludeProvenance)
    Assert-Condition -Condition (($actual -join "`n") -ceq ($Expected -join "`n")) `
        -Message 'Audit sentinel data, ownership, results or related rows changed unexpectedly.'
}

function Assert-HistoricalProvenance {
    param([string]$ContainerId)

    # Frozen expectations for the historical motor, independent of future runtime versions.
    $actual = @(Invoke-DockerSql -ContainerId $ContainerId -Sql @'
SELECT CONCAT(id,'|',version_motor,'|',version_reglas,'|',DATE_FORMAT(fecha_analisis,'%Y-%m-%d %H:%i:%s.%f'),'|',tipo_fuente)
FROM auditorias WHERE id IN (900001,900002) ORDER BY id;
'@)
    $expected = @('900001|1|1|2002-03-04 05:06:07.123456|MANUAL',
        '900002|1|1|2003-04-05 06:07:08.654321|MANUAL')
    Assert-Condition -Condition (($actual -join "`n") -ceq ($expected -join "`n")) `
        -Message 'Historical provenance backfill differs from the original creation timestamps or versions.'
}

function Assert-ProvenanceConstraints {
    param([string]$ContainerId)

    $columns = @('version_motor', 'version_reglas', 'fecha_analisis', 'tipo_fuente')
    $values = @("'constraint-engine'", "'constraint-rules'", "'2004-01-02 03:04:05.123456'", "'MANUAL'")
    $invalidInserts = @()
    for ($index = 0; $index -lt $columns.Count; $index++) {
        $nullValues = @($values)
        $nullValues[$index] = 'NULL'
        $invalidInserts += "INSERT INTO auditorias (id,usuario_id,titulo,estado,fecha_creacion,$($columns -join ',')) VALUES (999999,900001,'Constraint probe','COMPLETADA',NOW(6),$($nullValues -join ','));"
        $others = @(0..3 | Where-Object { $_ -ne $index })
        $names = @($others | ForEach-Object { $columns[$_] }) -join ','
        $data = @($others | ForEach-Object { $values[$_] }) -join ','
        $invalidInserts += "INSERT INTO auditorias (id,usuario_id,titulo,estado,fecha_creacion,$names) VALUES (999999,900001,'Constraint probe','COMPLETADA',NOW(6),$data);"
    }
    foreach ($invalidSource in @('UNKNOWN', 'manual', 'MANUAL ')) {
        $invalidInserts += "INSERT INTO auditorias (id,usuario_id,titulo,estado,fecha_creacion,$($columns -join ',')) VALUES (999999,900001,'Constraint probe','COMPLETADA',NOW(6),'probe','probe',NOW(6),'$invalidSource');"
    }
    foreach ($sql in $invalidInserts) {
        # A failed statement closes the connection and rolls back; an unexpected
        # success also rolls back. No probe can persist or alter business rows.
        $previousPreference = $ErrorActionPreference
        try {
            $ErrorActionPreference = 'Continue'
            $diagnostic = @("SET SESSION sql_mode='STRICT_ALL_TABLES'; START TRANSACTION; $sql ROLLBACK;" |
                & docker exec -i $ContainerId sh -c `
                    'MYSQL_PWD="$MARIADB_PASSWORD" mariadb -u"$MARIADB_USER" "$MARIADB_DATABASE" -N' 2>&1)
            $code = $LASTEXITCODE
        }
        finally { $ErrorActionPreference = $previousPreference }
        Assert-Condition -Condition ($code -ne 0 -and ($diagnostic -join "`n") -match 'ERROR (1048|1364|4025) ') `
            -Message 'Missing, NULL or invalid provenance was not rejected by the expected database constraint.'
    }
    foreach ($source in @('MANUAL', 'CRAWLER', 'FILE', 'API')) {
        Invoke-DockerSql -ContainerId $ContainerId -Sql "START TRANSACTION; INSERT INTO auditorias (id,usuario_id,titulo,estado,fecha_creacion,$($columns -join ',')) VALUES (999999,900001,'Constraint probe','COMPLETADA',NOW(6),'probe','probe',NOW(6),'$source'); ROLLBACK;" | Out-Null
    }
    Write-Host 'V2 NOT NULL + SOURCE CONSTRAINTS OK'
}

function ConvertTo-Base64Url {
    param([byte[]]$Bytes)
    return [Convert]::ToBase64String($Bytes).TrimEnd('=').Replace('+','-').Replace('/','_')
}

function Assert-NewAuditViaApi {
    param([string]$ContainerId, [int]$ApiPort, [string]$JwtSecret)

    # Authenticate only the synthetic sentinel against this run's loopback backend.
    # The signing key was generated for this temporary DB; no real credentials.
    $now = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds()
    $claims = @{sub='900001'; userId=900001; tokenVersion=7; role='CLIENTE';
        iss='smartaudits-phase08-legacy-baseline'; aud=@('smartaudits-phase08-legacy-baseline-api');
        iat=$now; exp=($now + 300)} | ConvertTo-Json -Compress
    $header = ConvertTo-Base64Url ([Text.Encoding]::UTF8.GetBytes('{"alg":"HS256","typ":"JWT"}'))
    $payload = ConvertTo-Base64Url ([Text.Encoding]::UTF8.GetBytes($claims))
    $unsigned = "$header.$payload"
    $hmac = New-Object System.Security.Cryptography.HMACSHA256
    try {
        $hmac.Key = [Convert]::FromBase64String($JwtSecret)
        $signature = ConvertTo-Base64Url ($hmac.ComputeHash([Text.Encoding]::UTF8.GetBytes($unsigned)))
    }
    finally { $hmac.Dispose() }
    $body = @{titulo='Phase22 new audit'; tipoDocumento='Aviso Legal';
        textoOriginal='Responsable: Ejemplo. Contacto: sentinel@phase08.invalid.';
        urlOpcional='https://example.invalid/reference-only'} | ConvertTo-Json -Compress
    $response = Invoke-WebRequest -UseBasicParsing -Method Post -Uri "http://127.0.0.1:${ApiPort}/auditorias" `
        -Headers @{Authorization="Bearer $unsigned.$signature"} -ContentType 'application/json' `
        -Body ([Text.Encoding]::UTF8.GetBytes($body))
    $audit = $response.Content | ConvertFrom-Json
    Assert-Condition -Condition ($response.StatusCode -eq 201 -and $audit.id -gt 900002 -and
        $audit.versionMotor -ceq '1' -and $audit.versionReglas -ceq '1' -and
        $audit.tipoFuente -ceq 'MANUAL' -and $audit.fechaAnalisis) `
        -Message 'New API audit did not return the current analysis provenance.'
    $id = [long]$audit.id
    $stored = @(Invoke-DockerSql -ContainerId $ContainerId -Sql "SELECT CONCAT(version_motor,'|',version_reglas,'|',tipo_fuente,'|',usuario_id,'|',url_opcional,'|',IF(fecha_analisis <= fecha_creacion AND fecha_analisis >= fecha_creacion - INTERVAL 1 MINUTE,1,0),'|',IF(JSON_VALID(resultado_json),1,0)) FROM auditorias WHERE id=$id;")
    Assert-Condition -Condition ($stored.Count -eq 1 -and $stored[0] -ceq '1|1|MANUAL|900001|https://example.invalid/reference-only|1|1') `
        -Message 'New API audit provenance or result was not persisted correctly.'
    Write-Host 'NEW AUDIT PROVENANCE PERSISTED OK'
}

function New-ValidationContainer {
    param(
        [string]$Name,
        [string]$VolumeName,
        [int]$Port,
        [string]$Database,
        [string]$User,
        [string]$Password,
        [string]$RootPassword,
        [string]$Kind
    )

    New-OwnedVolume `
        -Name $VolumeName `
        -Kind $Kind

    if (-not $ownedContainerRefs.Contains($Name)) {
        # Registrar ANTES de docker create.
        # Si Docker llega a crear el recurso pero el comando falla,
        # el finally podrá localizarlo por nombre y verificar ownership.
        $ownedContainerRefs.Add($Name)
    }

    $output = Invoke-Native `
        -FilePath 'docker' `
        -Arguments @(
            'create',
            '--name', $Name,
            '--label', 'smartaudits.validation=phase08',
            '--label', "smartaudits.validation.run=$runId",
            '--label', "smartaudits.validation.kind=$Kind",
            '--publish', "127.0.0.1:${Port}:3306",
            '--mount', "type=volume,source=$VolumeName,target=/var/lib/mysql",
            '--env', "MARIADB_ROOT_PASSWORD=$RootPassword",
            '--env', "MARIADB_DATABASE=$Database",
            '--env', "MARIADB_USER=$User",
            '--env', "MARIADB_PASSWORD=$Password",
            '--health-cmd', 'healthcheck.sh --connect --innodb_initialized',
            '--health-interval', '2s',
            '--health-timeout', '5s',
            '--health-retries', '30',
            'mariadb:11.2'
        ) `
        -Operation "Create $Kind MariaDB container"

    $containerId = (
        $output |
            Select-Object -Last 1
    ).Trim()

    Assert-Condition `
        -Condition (-not [string]::IsNullOrWhiteSpace($containerId)) `
        -Message "Docker did not return an ID for $Kind container."

    Invoke-Native `
        -FilePath 'docker' `
        -Arguments @(
            'start',
            $containerId
        ) `
        -Operation "Start $Kind MariaDB container" |
        Out-Null

    Wait-Healthy -ContainerId $containerId

    return $containerId
}

try {
    Write-Step 'Prechecks'

    Assert-NoInheritedOverrides

    foreach ($command in @(
        'docker',
        'java',
        'jar'
    )) {
        Assert-Condition `
            -Condition (
                $null -ne (
                    Get-Command $command `
                        -ErrorAction SilentlyContinue
                )
            ) `
            -Message "Required command not found: $command"
    }

    Assert-Condition `
        -Condition (Test-Path -LiteralPath $jar) `
        -Message 'Backend JAR not found. Run mvn package first.'

    Assert-Condition `
        -Condition (Test-Path -LiteralPath $SnapshotPath) `
        -Message "Missing schema snapshot: $SnapshotPath"

    $actualSnapshotHash = (
        Get-FileHash `
            -LiteralPath $SnapshotPath `
            -Algorithm SHA256
    ).Hash

    Assert-Condition `
        -Condition (
            $actualSnapshotHash -ieq $ExpectedSnapshotSha256
        ) `
        -Message 'Schema snapshot SHA-256 does not match the approved Phase 0.8 snapshot.'

    $sourceMigrations = @(
        Get-ChildItem `
            -LiteralPath $migrationDir `
            -Recurse `
            -File |
            ForEach-Object {
                $_.FullName.Substring(
                    $migrationDir.Length
                ).TrimStart('\', '/').Replace('\', '/')
            } |
            Sort-Object
    )

    Assert-Condition `
        -Condition (
            $sourceMigrations.Count -eq 2 -and
            $sourceMigrations[0] -ceq 'V1__initial_schema.sql' -and
            $sourceMigrations[1] -ceq 'V2__audit_analysis_provenance.sql'
        ) `
        -Message 'Migration validation requires exactly the frozen V1 and the analysis provenance V2.'

    $jarEntries = @(
        & jar tf $jar
    )

    if ($LASTEXITCODE -ne 0) {
        throw 'Unable to inspect backend JAR.'
    }

    $jarMigrations = @(
        $jarEntries |
            Where-Object {
                $_ -like 'BOOT-INF/classes/db/migration/*' -and
                -not $_.EndsWith('/')
            }
    )

    Assert-Condition `
        -Condition (
            $jarMigrations.Count -eq 2 -and
            @($jarMigrations | Sort-Object)[0] -ceq 'BOOT-INF/classes/db/migration/V1__initial_schema.sql' -and
            @($jarMigrations | Sort-Object)[1] -ceq 'BOOT-INF/classes/db/migration/V2__audit_analysis_provenance.sql'
        ) `
        -Message 'Backend JAR must contain exactly the frozen V1 and the analysis provenance V2.'

    $ports = New-Object System.Collections.Generic.HashSet[int]

    do {
        $emptyDbPort = Get-FreeTcpPort
    } until ($ports.Add($emptyDbPort))

    do {
        $emptyApiPort = Get-FreeTcpPort
    } until ($ports.Add($emptyApiPort))

    do {
        $legacyDbPort = Get-FreeTcpPort
    } until ($ports.Add($legacyDbPort))

    do {
        $legacyRejectApiPort = Get-FreeTcpPort
    } until ($ports.Add($legacyRejectApiPort))

    do {
        $legacyBaselineApiPort = Get-FreeTcpPort
    } until ($ports.Add($legacyBaselineApiPort))

    do {
        $legacySecondApiPort = Get-FreeTcpPort
    } until ($ports.Add($legacySecondApiPort))

    Write-Host "RUN ID=$runId"
    Write-Host 'SNAPSHOT HASH OK'
    Write-Host 'MIGRATION ARTIFACT OK'
    Write-Host 'PRECHECK OK'

    Write-Step 'Empty database'

    $emptyRoot = New-RandomPassword
    $emptyPass = New-RandomPassword
    $emptyJwt = New-RandomJwt

    $emptyContainerId = New-ValidationContainer `
        -Name $emptyName `
        -VolumeName $emptyVolumeName `
        -Port $emptyDbPort `
        -Database 'sa08_validation' `
        -User 'sa08_user' `
        -Password $emptyPass `
        -RootPassword $emptyRoot `
        -Kind 'empty'

    $emptyDbUrl = "jdbc:mariadb://127.0.0.1:${emptyDbPort}/sa08_validation"

    $emptyBackend = Start-ValidationBackend `
        -DbUrl $emptyDbUrl `
        -DbUser 'sa08_user' `
        -DbPassword $emptyPass `
        -JwtSecret $emptyJwt `
        -Issuer 'smartaudits-phase08-empty' `
        -Audience 'smartaudits-phase08-empty-api' `
        -ApiPort $emptyApiPort `
        -Baseline $false `
        -LogPrefix 'sa08-empty'

    Wait-BackendStarted -Backend $emptyBackend



    Assert-ExactTables `
        -ContainerId $emptyContainerId `
        -Expected $expectedManagedTables

    $emptyHistory = @(
        Invoke-DockerSql `
            -ContainerId $emptyContainerId `
            -Sql "SELECT CONCAT(installed_rank,'|',version,'|',description,'|',type,'|',success) FROM flyway_schema_history ORDER BY installed_rank;"
    )

    Assert-Condition `
        -Condition (
            $emptyHistory.Count -eq 2 -and
            $emptyHistory[0] -ceq '1|1|initial schema|SQL|1' -and
            $emptyHistory[1] -ceq '2|2|audit analysis provenance|SQL|1'
        ) `
        -Message 'Unexpected Flyway history for empty installation.'

    Assert-ProvenanceSchema -ContainerId $emptyContainerId
    Write-Host 'FRESH V1 -> V2 OK'

    $emptyHttp = Get-HttpStatus `
        -Uri "http://127.0.0.1:${emptyApiPort}/auditorias/mias"

    Assert-Condition `
        -Condition ($emptyHttp -eq 401) `
        -Message "Expected HTTP 401 for empty installation, got $emptyHttp."

    Stop-ProcessTree `
        -ProcessId $emptyBackend.Process.Id

    Write-Host 'EMPTY PATH OK'

    Write-Step 'Legacy database without baseline must fail'

    $legacyRoot = New-RandomPassword
    $legacyPass = New-RandomPassword
    $legacyJwt = New-RandomJwt

    $legacyContainerId = New-ValidationContainer `
        -Name $legacyName `
        -VolumeName $legacyVolumeName `
        -Port $legacyDbPort `
        -Database 'sa08_legacy_validation' `
        -User 'sa08_legacy_user' `
        -Password $legacyPass `
        -RootPassword $legacyRoot `
        -Kind 'legacy'

    Invoke-Native `
        -FilePath 'docker' `
        -Arguments @(
            'cp',
            $SnapshotPath,
            "${legacyContainerId}:/tmp/phase08-schema-before.sql"
        ) `
        -Operation 'Copy approved historical schema snapshot' |
        Out-Null

    Invoke-DockerRootImport `
        -ContainerId $legacyContainerId `
        -ContainerFile '/tmp/phase08-schema-before.sql'

    Assert-ExactTables `
        -ContainerId $legacyContainerId `
        -Expected $expectedDomainTables

    Invoke-DockerSql `
        -ContainerId $legacyContainerId `
        -Sql "INSERT INTO roles (id,descripcion,nombre) VALUES (900001,'Phase08 sentinel CLIENTE','CLIENTE'),(900002,'Phase08 sentinel ADMIN','ADMIN'); INSERT INTO usuarios (id,email,fecha_registro,nombre,password,role,activo,protegido,token_version,row_version) VALUES (900001,'sentinel@phase08.invalid','2001-02-03 04:05:06.123456','Sentinel Phase08','not-a-real-password','CLIENTE',1,0,7,3); INSERT INTO usuarios_roles (usuario_id,rol_id) VALUES (900001,900001);" |
        Out-Null

    Assert-Sentinel `
        -ContainerId $legacyContainerId

    Invoke-DockerSql -ContainerId $legacyContainerId -Sql @'
INSERT INTO auditorias (id,fecha_creacion,puntuacion_riesgo,resultado_json,texto_original,tipo_documento,titulo,url_opcional,usuario_id,estado) VALUES
(900001,'2002-03-04 05:06:07.123456',37,'{ "resumen": "Historical RESULT  ", "puntuacionRiesgo": 37 }','Original Text  ','Aviso Legal','Historical audit  ','https://example.invalid/historical',900001,'COMPLETADA'),
(900002,'2003-04-05 06:07:08.654321',NULL,NULL,NULL,NULL,'Nullable historical audit',NULL,900001,'EN_PROCESO');
INSERT INTO resultados (id,fecha_resultado,puntuacion_cumplimiento,recomendaciones_generales,resumen_general,auditoria_id)
VALUES (900001,'2002-03-04 05:06:08.123456',37,'Original recommendations  ','Original summary  ',900001);
INSERT INTO incidencias (id,categoria,descripcion,evidencia,impacto,recomendacion,severidad,auditoria_id)
VALUES (900001,'Original category','Original description  ','Original evidence','Original impact','Original recommendation','MEDIA',900001);
INSERT INTO historial_auditorias (id,accion,fecha_acceso,ip_acceso,auditoria_id,usuario_id)
VALUES (900001,'CREACION','2002-03-04 05:06:09.123456','192.0.2.22',900001,900001);
INSERT INTO historial_acciones_admin (id,admin_email,admin_nombre,detalles,fecha,objetivo_email,objetivo_nombre,tipo_accion,admin_id,usuario_objetivo_id)
VALUES (900001,'historical-admin@example.invalid','Historical admin','Original admin event  ','2002-03-04 05:06:10.123456','sentinel@phase08.invalid','Sentinel Phase08','REACTIVAR',900001,900001);
'@ | Out-Null
    $legacyAuditSnapshot = @(Get-AuditDataSnapshot -ContainerId $legacyContainerId)
    Assert-Condition -Condition ($legacyAuditSnapshot.Count -eq 6) -Message 'Incomplete historical audit fixture.'

    $legacyDbUrl = "jdbc:mariadb://127.0.0.1:${legacyDbPort}/sa08_legacy_validation"

    $rejectBackend = Start-ValidationBackend `
        -DbUrl $legacyDbUrl `
        -DbUser 'sa08_legacy_user' `
        -DbPassword $legacyPass `
        -JwtSecret $legacyJwt `
        -Issuer 'smartaudits-phase08-legacy-reject' `
        -Audience 'smartaudits-phase08-legacy-reject-api' `
        -ApiPort $legacyRejectApiPort `
        -Baseline $false `
        -LogPrefix 'sa08-legacy-reject'

    Wait-ExpectedFlywayFailure `
        -Backend $rejectBackend

    Stop-ProcessTree `
        -ProcessId $rejectBackend.Process.Id

    Assert-ExactTables `
        -ContainerId $legacyContainerId `
        -Expected $expectedDomainTables

    Assert-Sentinel `
        -ContainerId $legacyContainerId

    Write-Host 'LEGACY WITHOUT BASELINE REJECTED'

    Assert-AuditDataSnapshot -ContainerId $legacyContainerId -Expected $legacyAuditSnapshot

    Write-Step 'Legacy baseline'

    $baselineBackend = Start-ValidationBackend `
        -DbUrl $legacyDbUrl `
        -DbUser 'sa08_legacy_user' `
        -DbPassword $legacyPass `
        -JwtSecret $legacyJwt `
        -Issuer 'smartaudits-phase08-legacy-baseline' `
        -Audience 'smartaudits-phase08-legacy-baseline-api' `
        -ApiPort $legacyBaselineApiPort `
        -Baseline $true `
        -LogPrefix 'sa08-legacy-baseline'

    Wait-BackendStarted `
        -Backend $baselineBackend

    Assert-ExactTables `
        -ContainerId $legacyContainerId `
        -Expected $expectedManagedTables

    $baselineHistory = @(
        Invoke-DockerSql `
            -ContainerId $legacyContainerId `
            -Sql "SELECT CONCAT(installed_rank,'|',version,'|',description,'|',type,'|',success) FROM flyway_schema_history ORDER BY installed_rank;"
    )

    Assert-Condition `
        -Condition (
            $baselineHistory.Count -eq 2 -and
            $baselineHistory[0] -ceq '1|1|Legacy SmartAudits schema|BASELINE|1' -and
            $baselineHistory[1] -ceq '2|2|audit analysis provenance|SQL|1'
        ) `
        -Message 'Unexpected Flyway history after historical baseline.'

    Assert-ProvenanceSchema -ContainerId $legacyContainerId
    Assert-AuditDataSnapshot -ContainerId $legacyContainerId -Expected $legacyAuditSnapshot
    Assert-HistoricalProvenance -ContainerId $legacyContainerId
    Assert-ProvenanceConstraints -ContainerId $legacyContainerId
    Assert-AuditDataSnapshot -ContainerId $legacyContainerId -Expected $legacyAuditSnapshot
    Assert-NewAuditViaApi -ContainerId $legacyContainerId -ApiPort $legacyBaselineApiPort -JwtSecret $legacyJwt
    $upgradedAuditSnapshot = @(Get-AuditDataSnapshot -ContainerId $legacyContainerId -IncludeProvenance)
    Write-Host 'HISTORICAL BASELINE 1 -> V2 + DATA PRESERVATION OK'

    Assert-Sentinel `
        -ContainerId $legacyContainerId

    $baselineHttp = Get-HttpStatus `
        -Uri "http://127.0.0.1:${legacyBaselineApiPort}/auditorias/mias"

    Assert-Condition `
        -Condition ($baselineHttp -eq 401) `
        -Message "Expected HTTP 401 after baseline, got $baselineHttp."

    Stop-ProcessTree `
        -ProcessId $baselineBackend.Process.Id

    Write-Host 'LEGACY BASELINE OK'

    Write-Step 'Second historical startup without baseline'

    $secondBackend = Start-ValidationBackend `
        -DbUrl $legacyDbUrl `
        -DbUser 'sa08_legacy_user' `
        -DbPassword $legacyPass `
        -JwtSecret $legacyJwt `
        -Issuer 'smartaudits-phase08-legacy-second' `
        -Audience 'smartaudits-phase08-legacy-second-api' `
        -ApiPort $legacySecondApiPort `
        -Baseline $false `
        -LogPrefix 'sa08-legacy-second'

    Wait-BackendStarted `
        -Backend $secondBackend

    $secondHistory = @(
        Invoke-DockerSql `
            -ContainerId $legacyContainerId `
            -Sql "SELECT CONCAT(installed_rank,'|',version,'|',description,'|',type,'|',success) FROM flyway_schema_history ORDER BY installed_rank;"
    )

    Assert-Condition `
        -Condition (
            $secondHistory.Count -eq 2 -and
            $secondHistory[0] -ceq '1|1|Legacy SmartAudits schema|BASELINE|1' -and
            $secondHistory[1] -ceq '2|2|audit analysis provenance|SQL|1'
        ) `
        -Message 'Flyway history changed unexpectedly on second historical startup.'

    Assert-ProvenanceSchema -ContainerId $legacyContainerId
    Assert-HistoricalProvenance -ContainerId $legacyContainerId
    Assert-AuditDataSnapshot -ContainerId $legacyContainerId -Expected $upgradedAuditSnapshot -IncludeProvenance
    Write-Host 'SECOND START ALL AUDIT DATA + PROVENANCE UNCHANGED'

    Assert-ExactTables `
        -ContainerId $legacyContainerId `
        -Expected $expectedManagedTables

    Assert-Sentinel `
        -ContainerId $legacyContainerId

    $secondHttp = Get-HttpStatus `
        -Uri "http://127.0.0.1:${legacySecondApiPort}/auditorias/mias"

    Assert-Condition `
        -Condition ($secondHttp -eq 401) `
        -Message "Expected HTTP 401 on second startup, got $secondHttp."

    Stop-ProcessTree `
        -ProcessId $secondBackend.Process.Id

    Write-Host 'SECOND START WITHOUT BASELINE OK'

$validationCompleted = $true
}
catch {
    $validationError = $_
}
finally {
    foreach ($processId in @($startedProcessIds)) {
        try {
            Stop-ProcessTree `
                -ProcessId $processId
        }
        catch {
            $cleanupErrors.Add(
                "Process $processId cleanup failed: $($_.Exception.Message)"
            )
        }
    }

    if (-not $KeepContainers) {
    foreach ($containerRef in @($ownedContainerRefs)) {
        try {
            Remove-OwnedContainer `
                -ContainerId $containerRef
        }
        catch {
            $cleanupErrors.Add(
                "Container $containerRef cleanup failed: $($_.Exception.Message)"
            )
        }
    }

    foreach ($volumeName in @($ownedVolumeNames)) {
        try {
            Remove-OwnedVolume `
                -VolumeName $volumeName
        }
        catch {
            $cleanupErrors.Add(
                "Volume $volumeName cleanup failed: $($_.Exception.Message)"
            )
        }
    }
}
else {
    Write-Host ''
    Write-Host 'Temporary resources kept by explicit request:'

    foreach ($containerRef in @($ownedContainerRefs)) {
        Write-Host "CONTAINER=$containerRef"
    }

    foreach ($volumeName in @($ownedVolumeNames)) {
        Write-Host "VOLUME=$volumeName"
    }
}
}

if ($validationError) {
    if ($cleanupErrors.Count -gt 0) {
        $cleanupSummary = $cleanupErrors -join ' | '

        throw (
            "Validation failed: $($validationError.Exception.Message) " +
            "Cleanup also failed: $cleanupSummary"
        )
    }

    throw $validationError
}

if ($cleanupErrors.Count -gt 0) {
    $cleanupSummary = $cleanupErrors -join ' | '

    throw "Validation completed but cleanup failed: $cleanupSummary"
}

Assert-Condition `
    -Condition $validationCompleted `
    -Message 'Validation did not reach successful completion.'

Write-Host ''

if ($KeepContainers) {
    Write-Host 'PHASE 0.8 FLYWAY VALIDATION COMPLETED - CLEANUP SKIPPED BY REQUEST'
}
else {
    Write-Host 'PHASE 0.8 FLYWAY VALIDATION PASSED'
}

# Reached only after all validation/cleanup guards above have succeeded.
# Cleanup's expected "not found" inspections leave a native LASTEXITCODE of 1;
# do not let the GitHub Actions PowerShell epilogue treat it as our result.
exit 0
