$ErrorActionPreference = 'Stop'
# Independent, DDL-only fixture. Keep the approved local snapshot/default intact.
& (Join-Path $PSScriptRoot 'Validate-FlywayPhase08.ps1') `
    -SnapshotPath (Join-Path $PSScriptRoot 'fixtures/legacy-schema.sql') `
    -ExpectedSnapshotSha256 '4A6359B4D30060F5BC6F0FC2FEE78A6B0C5AFC53D989E1DC8A48D598C65D72B4'
