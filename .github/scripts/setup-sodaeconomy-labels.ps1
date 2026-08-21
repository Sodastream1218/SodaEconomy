$ErrorActionPreference = "Stop"

$repo = "Sodastream1218/SodaEconomy"

function Invoke-Gh {
    param(
        [Parameter(Mandatory = $true)]
        [string[]]$Arguments,

        [switch]$IgnoreFailure
    )

    & gh @Arguments
    $exitCode = $LASTEXITCODE

    if (-not $IgnoreFailure -and $exitCode -ne 0) {
        throw "GitHub CLI command failed: gh $($Arguments -join ' ')"
    }

    return $exitCode
}

Write-Host "Checking GitHub CLI authentication..."
Invoke-Gh -Arguments @("auth", "status")

Write-Host "Checking repository access..."
Invoke-Gh -Arguments @("repo", "view", $repo, "--json", "nameWithOwner")

$labelsToDelete = @(
    "dependencies"
)

foreach ($label in $labelsToDelete) {
    Write-Host "Removing optional default label '$label' if present..."
    Invoke-Gh -Arguments @("label", "delete", $label, "--repo", $repo, "--yes") -IgnoreFailure | Out-Null
}

$labels = @(
    @{ Name = "priority: critical"; Color = "B60205"; Description = "Release blocker or severe production issue" },
    @{ Name = "priority: high"; Color = "D93F0B"; Description = "High-priority issue requiring prompt attention" },
    @{ Name = "priority: medium"; Color = "FBCA04"; Description = "Normal development priority" },
    @{ Name = "priority: low"; Color = "0E8A16"; Description = "Low-priority improvement or cleanup" },

    @{ Name = "bug"; Color = "D73A4A"; Description = "Something is not working correctly" },
    @{ Name = "feature"; Color = "7057FF"; Description = "A new user-facing capability" },
    @{ Name = "enhancement"; Color = "A2EEEF"; Description = "Improvement to existing functionality" },
    @{ Name = "refactor"; Color = "C2E0C6"; Description = "Internal restructuring without behavior changes" },
    @{ Name = "performance"; Color = "1D76DB"; Description = "Performance or resource-usage improvement" },
    @{ Name = "security"; Color = "B60205"; Description = "Security or data-consistency concern" },
    @{ Name = "documentation"; Color = "0075CA"; Description = "Documentation changes or additions" },
    @{ Name = "tests"; Color = "5319E7"; Description = "Tests, coverage, or test infrastructure" },

    @{ Name = "area: api"; Color = "D4C5F9"; Description = "Public API or integration contracts" },
    @{ Name = "area: commands"; Color = "C5DEF5"; Description = "Commands, permissions, or tab completion" },
    @{ Name = "area: config"; Color = "BFDADC"; Description = "Configuration or runtime reload system" },
    @{ Name = "area: identity"; Color = "F9D0C4"; Description = "Player identity and name resolution" },
    @{ Name = "area: vault"; Color = "FAD8C7"; Description = "Vault economy integration" },
    @{ Name = "area: floodgate"; Color = "C2E0C6"; Description = "Floodgate or Geyser compatibility" },
    @{ Name = "area: storage"; Color = "BFD4F2"; Description = "Shared persistence and storage architecture" },
    @{ Name = "area: mysql"; Color = "006B75"; Description = "MySQL backend or multi-server behavior" },
    @{ Name = "area: sqlite"; Color = "0E8A16"; Description = "SQLite backend" },
    @{ Name = "area: yaml"; Color = "FEF2C0"; Description = "YAML backend" },
    @{ Name = "area: transactions"; Color = "D876E3"; Description = "Transactions, journal, and consistency" },
    @{ Name = "area: rollback"; Color = "E99695"; Description = "Transaction rollback behavior" },
    @{ Name = "area: audit"; Color = "F9D0C4"; Description = "Audit, history, and statistics" },
    @{ Name = "area: leaderboard"; Color = "BFDADC"; Description = "Balance leaderboard and identity display" },
    @{ Name = "area: language"; Color = "C5DEF5"; Description = "Translations, messages, and prefix formatting" },
    @{ Name = "area: runtime reload"; Color = "D4C5F9"; Description = "Safe runtime configuration reload" },

    @{ Name = "question"; Color = "D876E3"; Description = "Further information or support is requested" },
    @{ Name = "support"; Color = "0E8A16"; Description = "Setup or usage support request" },
    @{ Name = "help wanted"; Color = "008672"; Description = "Community help is welcome" },
    @{ Name = "good first issue"; Color = "7057FF"; Description = "Suitable for a first contribution" },
    @{ Name = "needs triage"; Color = "FBCA04"; Description = "Awaiting maintainer review and classification" },
    @{ Name = "blocked"; Color = "B60205"; Description = "Blocked by another issue or external dependency" },
    @{ Name = "duplicate"; Color = "CFD3D7"; Description = "This issue or pull request already exists" },
    @{ Name = "invalid"; Color = "E4E669"; Description = "Insufficient, invalid, or unrelated report" },
    @{ Name = "wontfix"; Color = "FFFFFF"; Description = "This change is not currently planned" }
)

foreach ($label in $labels) {
    Write-Host "Creating or updating '$($label.Name)'..."
    Invoke-Gh -Arguments @(
        "label", "create", $label.Name,
        "--repo", $repo,
        "--color", $label.Color,
        "--description", $label.Description,
        "--force"
    ) | Out-Null
}

Write-Host ""
Write-Host "SodaEconomy labels were configured successfully."
Write-Host "Open: https://github.com/Sodastream1218/SodaEconomy/labels"
