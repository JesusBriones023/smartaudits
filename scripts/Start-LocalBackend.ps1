param(
    [switch]$Check,
    [switch]$AdoptLegacySchema
)

$ErrorActionPreference = 'Stop'

$repo = Split-Path -Parent $PSScriptRoot
$envFile = Join-Path $repo '.env'
$jar = Join-Path $repo 'smartaudits-backend\target\smartaudits-backend-1.0.0.jar'

$allowed = @(
    'DB_URL',
    'DB_USERNAME',
    'DB_PASSWORD',
    'JWT_SECRET',
    'JWT_ISSUER',
    'JWT_AUDIENCE',
    'APP_ADMIN_BOOTSTRAP_ENABLED',
    'APP_DATA_INITIALIZER_ENABLED',
    'SMARTAUDITS_ADMIN_EMAIL',
    'SMARTAUDITS_ADMIN_PASSWORD',
    'SMARTAUDITS_ADMIN_NOMBRE',
    'SPRING_JPA_HIBERNATE_DDL_AUTO',
    'AUDIT_DAILY_LIMIT',
    'FLYWAY_ENABLED',
    'FLYWAY_BASELINE_ON_MIGRATE'
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
    'SMARTAUDITS_ADMIN_EMAIL',
    'SMARTAUDITS_ADMIN_PASSWORD',
    'SMARTAUDITS_ADMIN_NOMBRE',
    'SPRING_JPA_HIBERNATE_DDL_AUTO',
    'AUDIT_DAILY_LIMIT',
    'FLYWAY_ENABLED',
    'FLYWAY_BASELINE_ON_MIGRATE',
    'SPRING_DATASOURCE_URL',
    'SPRING_DATASOURCE_USERNAME',
    'SPRING_DATASOURCE_PASSWORD',
    'SPRING_FLYWAY_URL',
    'SPRING_FLYWAY_USER',
    'SPRING_FLYWAY_PASSWORD',
    'DB_ROOT_PASSWORD'
)

$dangerousJavaEnvironment = @(
    'JAVA_TOOL_OPTIONS',
    'JDK_JAVA_OPTIONS',
    '_JAVA_OPTIONS'
)

$values = @{}
$previousEnvironment = @{}

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
            'Unsafe inherited configuration detected. Remove these variables before starting: ' +
            ($conflicts -join ', ')
        )
    }
}

function Test-BooleanValue {
    param(
        [string]$Name,
        [hashtable]$Values
    )

    if (-not $Values.ContainsKey($Name)) {
        return
    }

    $value = $Values[$Name]

    if (
        -not [string]::IsNullOrWhiteSpace($value) -and
        $value -notmatch '^(?i:true|false)$'
    ) {
        throw "$Name must be true or false."
    }
}

function Get-ValueOrDefault {
    param(
        [hashtable]$Values,
        [string]$Name,
        [string]$Default
    )

    if (
        $Values.ContainsKey($Name) -and
        -not [string]::IsNullOrWhiteSpace($Values[$Name])
    ) {
        return $Values[$Name]
    }

    return $Default
}

function Save-Environment {
    foreach ($name in $managedEnvironment) {
        $exists = Test-Path "Env:$name"
        $value = [Environment]::GetEnvironmentVariable($name, 'Process')

        $previousEnvironment[$name] = @{
            Exists = $exists
            Value = $value
        }
    }
}

function Restore-Environment {
    foreach ($name in $previousEnvironment.Keys) {
        $entry = $previousEnvironment[$name]

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

function Set-ProcessEnvironment {
    param(
        [string]$Name,
        [AllowNull()]
        [string]$Value
    )

    [Environment]::SetEnvironmentVariable(
        $Name,
        $Value,
        'Process'
    )
}

function Assert-AdoptionArtifact {
    if (-not (Test-Path -LiteralPath $jar)) {
        throw 'Build the backend with mvn package before legacy adoption.'
    }

    $entries = @(& jar tf $jar)

    if ($LASTEXITCODE -ne 0) {
        throw 'Unable to inspect the backend JAR.'
    }

    $migrationEntries = @(
        $entries |
            Where-Object {
                $_ -like 'BOOT-INF/classes/db/migration/*' -and
                -not $_.EndsWith('/')
            }
    )

    $expected = 'BOOT-INF/classes/db/migration/V1__initial_schema.sql'

    if (
        $migrationEntries.Count -ne 1 -or
        $migrationEntries[0] -ne $expected
    ) {
        throw (
            'Legacy adoption is allowed only with an artifact containing exactly ' +
            'V1__initial_schema.sql and no additional migrations or callbacks.'
        )
    }
}

try {
    Assert-NoInheritedOverrides
    Save-Environment

    if (-not (Test-Path -LiteralPath $envFile)) {
        throw 'Missing local .env; see docs/SECURITY_CONFIGURATION_PHASE_0_3.md.'
    }

    foreach ($line in Get-Content -LiteralPath $envFile) {
        if (
            $line -match '^([A-Z_][A-Z_0-9]*)=(.*)$' -and
            $allowed -contains $Matches[1]
        ) {
            $key = $Matches[1]

            if ($values.ContainsKey($key)) {
                throw "Duplicate configuration key: $key"
            }

            $values[$key] = $Matches[2]
        }
    }

    foreach ($key in @(
        'DB_URL',
        'DB_USERNAME',
        'DB_PASSWORD',
        'JWT_SECRET'
    )) {
        if (
            -not $values.ContainsKey($key) -or
            [string]::IsNullOrWhiteSpace($values[$key])
        ) {
            throw "Missing variable: $key"
        }
    }

    if (
        $values['DB_URL'] -match
        '(?i)(?:[?&;](?:user|username|password)=)'
    ) {
        throw 'DB_URL must not embed database credentials.'
    }

    foreach ($booleanKey in @(
        'APP_ADMIN_BOOTSTRAP_ENABLED',
        'APP_DATA_INITIALIZER_ENABLED',
        'FLYWAY_ENABLED',
        'FLYWAY_BASELINE_ON_MIGRATE'
    )) {
        Test-BooleanValue -Name $booleanKey -Values $values
    }

    try {
        $keyBytes = [Convert]::FromBase64String(
            $values['JWT_SECRET']
        )
    }
    catch {
        throw 'JWT_SECRET must be Base64.'
    }

    if ($keyBytes.Length -lt 32) {
        throw 'JWT_SECRET requires at least 32 random bytes.'
    }

    [Array]::Clear(
        $keyBytes,
        0,
        $keyBytes.Length
    )

    $configuredDdl = Get-ValueOrDefault `
        -Values $values `
        -Name 'SPRING_JPA_HIBERNATE_DDL_AUTO' `
        -Default 'validate'

    if ($configuredDdl -ine 'validate') {
        throw 'SPRING_JPA_HIBERNATE_DDL_AUTO must always be validate.'
    }

    $configuredBaseline = Get-ValueOrDefault `
        -Values $values `
        -Name 'FLYWAY_BASELINE_ON_MIGRATE' `
        -Default 'false'

    if (
        -not $AdoptLegacySchema -and
        $configuredBaseline -ieq 'true'
    ) {
        throw (
            'FLYWAY_BASELINE_ON_MIGRATE=true is only permitted with ' +
            '-AdoptLegacySchema.'
        )
    }

    if ($AdoptLegacySchema) {
        Assert-AdoptionArtifact

        $flywayEnabled = 'true'
        $baselineEnabled = 'true'
        $initializerEnabled = 'false'
        $adminBootstrapEnabled = 'false'
    }
    else {
        $flywayEnabled = (
            Get-ValueOrDefault `
                -Values $values `
                -Name 'FLYWAY_ENABLED' `
                -Default 'false'
        ).ToLowerInvariant()

        $baselineEnabled = 'false'

        $initializerEnabled = (
            Get-ValueOrDefault `
                -Values $values `
                -Name 'APP_DATA_INITIALIZER_ENABLED' `
                -Default 'true'
        ).ToLowerInvariant()

        $adminBootstrapEnabled = (
            Get-ValueOrDefault `
                -Values $values `
                -Name 'APP_ADMIN_BOOTSTRAP_ENABLED' `
                -Default 'false'
        ).ToLowerInvariant()
    }

    foreach ($key in $allowed) {
        if ($values.ContainsKey($key)) {
            Set-ProcessEnvironment `
                -Name $key `
                -Value $values[$key]
        }
        else {
            Set-ProcessEnvironment `
                -Name $key `
                -Value $null
        }
    }

    # Nunca entregar la credencial root al backend.
    Set-ProcessEnvironment `
        -Name 'DB_ROOT_PASSWORD' `
        -Value $null

    # Fijamos explícitamente datasource y Flyway al mismo destino.
    Set-ProcessEnvironment `
        -Name 'SPRING_DATASOURCE_URL' `
        -Value $values['DB_URL']

    Set-ProcessEnvironment `
        -Name 'SPRING_DATASOURCE_USERNAME' `
        -Value $values['DB_USERNAME']

    Set-ProcessEnvironment `
        -Name 'SPRING_DATASOURCE_PASSWORD' `
        -Value $values['DB_PASSWORD']

    Set-ProcessEnvironment `
        -Name 'SPRING_FLYWAY_URL' `
        -Value $values['DB_URL']

    Set-ProcessEnvironment `
        -Name 'SPRING_FLYWAY_USER' `
        -Value $values['DB_USERNAME']

    Set-ProcessEnvironment `
        -Name 'SPRING_FLYWAY_PASSWORD' `
        -Value $values['DB_PASSWORD']

    Set-ProcessEnvironment `
        -Name 'SPRING_JPA_HIBERNATE_DDL_AUTO' `
        -Value 'validate'

    Set-ProcessEnvironment `
        -Name 'FLYWAY_ENABLED' `
        -Value $flywayEnabled

    Set-ProcessEnvironment `
        -Name 'FLYWAY_BASELINE_ON_MIGRATE' `
        -Value $baselineEnabled

    Set-ProcessEnvironment `
        -Name 'APP_DATA_INITIALIZER_ENABLED' `
        -Value $initializerEnabled

    Set-ProcessEnvironment `
        -Name 'APP_ADMIN_BOOTSTRAP_ENABLED' `
        -Value $adminBootstrapEnabled

    if ($Check) {
        if ($AdoptLegacySchema) {
            Write-Output (
                'PASS: legacy schema adoption configuration is valid; ' +
                'artifact is restricted to V1; no secret values displayed.'
            )
        }
        else {
            Write-Output (
                'PASS: local configuration is complete and hardened; ' +
                'no secret values displayed.'
            )
        }

        return
    }

    if (-not (Test-Path -LiteralPath $jar)) {
        throw 'Build the backend with mvn package first.'
    }

    $javaArgs = @(
        '-Dspring.main.add-command-line-properties=true',
        '-jar',
        $jar,
        '--spring.config.location=classpath:/application.properties',
        "--spring.datasource.url=$($values['DB_URL'])",
        "--spring.datasource.username=$($values['DB_USERNAME'])",
        '--spring.jpa.hibernate.ddl-auto=validate',
        "--spring.flyway.enabled=$flywayEnabled",
        "--spring.flyway.baseline-on-migrate=$baselineEnabled",
        '--spring.flyway.baseline-version=1',
        '--spring.flyway.locations=classpath:db/migration',
        '--spring.flyway.validate-on-migrate=true',
        '--spring.flyway.clean-disabled=true',
        '--spring.sql.init.mode=never',
        "--app.data.initializer.enabled=$initializerEnabled",
        "--app.admin.bootstrap.enabled=$adminBootstrapEnabled",
        '--server.address=127.0.0.1'
    )

    if ($AdoptLegacySchema) {
        # La adopción extraordinaria SOLO puede registrar baseline V1.
        # Si el artefacto contiene V2+ ya habrá sido rechazado antes.
        $javaArgs += '--spring.flyway.target=1'
    }

    & java @javaArgs

    if ($LASTEXITCODE -ne 0) {
        throw "Backend exited with code $LASTEXITCODE"
    }
}
finally {
    Restore-Environment

    $values.Clear()
    $previousEnvironment.Clear()
}