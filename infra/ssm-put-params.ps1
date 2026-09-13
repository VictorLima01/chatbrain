# Publica no SSM Parameter Store os segredos que estao no .env da raiz.
# Nenhum valor e impresso na tela nem fica no historico do PowerShell.
# Uso:  .\infra\ssm-put-params.ps1

$env:AWS_PROFILE = "cloudability"
$Region = "us-east-2"

$EnvFile = Join-Path $PSScriptRoot "..\.env"
if (-not (Test-Path $EnvFile)) { throw "Nao encontrei o .env em: $EnvFile" }

# Le o .env. Chave repetida: a ULTIMA ocorrencia vence, que e como o
# docker compose e a maioria dos parsers se comportam.
$vals = @{}
foreach ($line in Get-Content $EnvFile) {
    $t = $line.Trim()
    if ($t -eq "" -or $t.StartsWith("#")) { continue }
    $i = $t.IndexOf("=")
    if ($i -lt 1) { continue }
    $k = $t.Substring(0, $i).Trim()
    $v = $t.Substring($i + 1).Trim()
    if ($v.Length -ge 2 -and $v.StartsWith('"') -and $v.EndsWith('"')) {
        $v = $v.Substring(1, $v.Length - 2)
    }
    $vals[$k] = $v
}

function Put-Param($Name, $EnvKey, $Type) {
    if (-not $vals.ContainsKey($EnvKey)) {
        Write-Host ("PULADO   {0}  -- {1} nao existe no .env" -f $Name, $EnvKey)
        return
    }
    $v = $vals[$EnvKey]
    if ([string]::IsNullOrWhiteSpace($v)) {
        Write-Host ("PULADO   {0}  -- {1} esta vazio" -f $Name, $EnvKey)
        return
    }
    aws ssm put-parameter --name $Name --type $Type --value $v --overwrite --region $Region | Out-Null
    if ($LASTEXITCODE -eq 0) {
        Write-Host ("OK       {0}  ({1}, {2} caracteres)" -f $Name, $Type, $v.Length)
    } else {
        Write-Host ("FALHOU   {0}" -f $Name)
    }
}

Put-Param "/cloudability/db_url"            "DB_URL"            "String"
Put-Param "/cloudability/db_user"           "DB_USER"           "String"
Put-Param "/cloudability/db_password"       "DB_PASSWORD"       "SecureString"
Put-Param "/cloudability/anthropic_api_key" "ANTHROPIC_API_KEY" "SecureString"

# O hash real e gerado no passo 9; ate la, um marcador.
aws ssm put-parameter --name "/cloudability/admin_password_hash" `
    --type SecureString --value "placeholder" --overwrite --region $Region | Out-Null
if ($LASTEXITCODE -eq 0) {
    Write-Host "OK       /cloudability/admin_password_hash  (placeholder)"
}

Write-Host ""
Write-Host "Parametros gravados em /cloudability/ :"
aws ssm get-parameters-by-path --path "/cloudability/" --region $Region `
    --query "Parameters[].[Name,Type]" --output table
