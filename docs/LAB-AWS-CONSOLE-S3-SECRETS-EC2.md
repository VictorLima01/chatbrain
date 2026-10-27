# Laboratório AWS pelo console em português — S3 + Secrets Manager + Perfil do IAM + EC2

Passo a passo para montar, **inteiramente pelo console da AWS com a interface em Português
(Brasil)**, o laboratório descrito em `infra/teste!.MD`: um bucket com arquivos, um segredo que
guarda o nome desse bucket, um perfil do IAM (*role*) com permissões mínimas e uma EC2 que usa esse
perfil para descobrir o bucket e listá-lo.

## 0. Colocar o console em português

1. Faça login no console e olhe o **rodapé** da página (canto inferior esquerdo): há um seletor de
   idioma. Clique nele e escolha **Português (Brasil)**.
2. Alternativa permanente: menu da sua conta (canto superior direito) → **Configurações**
   (*Settings*) → **Localização e idioma** → **Idioma** → **Português (Brasil)** → **Salvar**.
3. Confira o **seletor de região**, ao lado do nome da conta: deve ficar em
   **Leste dos EUA (Norte da Virgínia) us-east-1** — confirme isso **em toda etapa**, é o erro mais
   comum do laboratório.

| Item | Valor |
|---|---|
| Região | **Leste dos EUA (Norte da Virgínia)** — `us-east-1` |
| Sistema da EC2 | Amazon Linux 2023 |
| Tipo da instância | `t3.micro` |

> **Sobre as traduções.** A AWS revisa os rótulos com frequência e alguns ficam só em inglês. Neste
> documento o termo em português vem primeiro e o original em inglês entre parênteses — se o seu
> console mostrar o inglês, é o mesmo botão. Três traduções que confundem:
>
> | Console em inglês | Console em português |
> |---|---|
> | IAM **Role** | **Perfil** — e o botão é **Criar perfil** |
> | **Launch instance** | **Executar instância** |
> | **Terminate instance** | **Encerrar instância** |
>
> A de **Role** é a que mais atrapalha: material antigo (e tutoriais em português na internet) fala
> em **"Função"**, mas o console atual usa **Perfil** — no menu do IAM a entrada é **Perfis** e o
> botão é **Criar perfil**. Some to isso que **perfil de instância** (*instance profile*) é outra
> coisa, explicada no passo 3.2.
>
> Já a **AWS CLI e as mensagens de erro da API não têm tradução** — tudo o que roda dentro da EC2
> (seção 6) continua em inglês, inclusive `AccessDenied`.

Marcadores usados ao longo do documento — troque pelos nomes que você escolher:

| Marcador | O que é | Exemplo |
|---|---|---|
| `<NOME_BUCKET>` | nome do bucket S3 (único no mundo inteiro) | `lab-finops-2026` |
| `<NOME_SEGREDO>` | nome do segredo no Secrets Manager | `lab/bucket-config` |
| `<NOME_ROLE>` | nome do perfil (*role*) do IAM | `lab-ec2-s3-secrets-role` |
| `<ID_CONTA>` | seu ID de conta (12 dígitos) | `123456789012` |

> **Custo.** Bucket e perfil são gratuitos; o segredo custa ~US$ 0,40/mês e a `t3.micro` é cobrada
> por hora (ou entra no nível gratuito). A seção [Limpeza](#7-limpeza) apaga tudo no final.

---

## 1. Bucket S3 e arquivos de teste

1. Console → busque **S3** → **Buckets** → **Criar bucket** (*Create bucket*).
2. **Tipo de bucket** (*Bucket type*): **Uso geral** (*General purpose*).
3. **Nome do bucket**: `<NOME_BUCKET>`. **Região da AWS**: **Leste dos EUA (Norte da Virgínia)**.
4. Deixe **Bloquear todo o acesso público** (*Block all public access*) marcado, como vem por padrão
   — o acesso do laboratório é pelo perfil do IAM, não por acesso público.
5. **Versionamento de bucket**, tags e **Criptografia padrão**: mantenha os padrões (a **SSE-S3** já
   vem ligada).
6. **Criar bucket**.

Upload dos arquivos:

1. Clique no bucket `<NOME_BUCKET>` → aba **Objetos** (*Objects*) → **Fazer upload** (*Upload*).
2. **Adicionar arquivos** (*Add files*) e escolha dois ou três arquivos quaisquer (um `.txt`, um
   `.json` — o conteúdo não importa). Um Bloco de Notas salvo como `teste1.txt` serve.
3. **Fazer upload** → espere o aviso de upload concluído → **Fechar**.

Os objetos devem aparecer listados na aba **Objetos**.

---

## 2. Segredo no Secrets Manager

1. Console → busque **Secrets Manager** → **Armazenar um novo segredo** (*Store a new secret*).
2. **Tipo de segredo** (*Secret type*): **Outro tipo de segredo** (*Other type of secret*).
3. Em **Pares de chave/valor** (*Key/value pairs*), clique na aba **Texto simples** (*Plaintext*) e
   substitua o conteúdo por:

   ```json
   { "bucket": "<NOME_BUCKET>" }
   ```

   (pela aba **Chave/valor** dá no mesmo: chave `bucket`, valor `<NOME_BUCKET>` — o console monta
   esse mesmo JSON.)
4. **Chave de criptografia** (*Encryption key*): `aws/secretsmanager`, a chave gerenciada pela AWS
   (padrão). **Próximo**.
5. **Nome do segredo**: `<NOME_SEGREDO>`. A descrição é opcional. **Próximo**.
6. **Rotação automática** (*Automatic rotation*): deixe desligada. **Próximo** → revise →
   **Armazenar** (*Store*).
7. Abra o segredo criado e **copie o ARN** que aparece no topo. Ele termina com um sufixo aleatório
   de 6 caracteres:

   ```
   arn:aws:secretsmanager:us-east-1:<ID_CONTA>:secret:<NOME_SEGREDO>-a1B2c3
   ```

> Note que o ARN **não é traduzido**: mesmo com o console em português, a região dentro do ARN
> continua sendo `us-east-1`, nunca "Norte da Virgínia".
>
> Esse ARN entra na política do passo 3. Como o sufixo é aleatório, na política usamos
> `<NOME_SEGREDO>-*` para não depender de tê-lo decorado.

---

## 3. Perfil do IAM (*role*) com permissões mínimas

### 3.1 Criar a política

Há duas formas de criar exatamente a mesma política. A **opção A** é toda por cliques, no **Editor
visual**, sem escrever uma linha de JSON — é a que ensina onde cada permissão mora no console. A
**opção B** é colar o documento pronto. Faça uma das duas.

#### Opção A — Editor visual (sem JSON)

1. Console → busque **IAM** → **Políticas** (*Policies*) → **Criar política** (*Create policy*).
2. Em **Editor de políticas**, deixe a aba **Visual** selecionada (é a que abre por padrão).

**Bloco 1 — listar o bucket**

3. **Selecionar um serviço** (*Select a service*) → digite `S3` → clique em **S3**.
4. **Ações permitidas** (*Actions allowed*) → no campo **Filtrar ações** digite `ListBucket` e marque
   **ListBucket**. Ele aparece sob o nível de acesso **Listar** (*List*).
5. **Recursos** (*Resources*) → tipo de recurso **bucket** → escolha **Específico** (*Specific*) →
   **Adicionar ARNs** (*Add ARNs*).
   - Na janela, em **Nome do bucket**, digite `<NOME_BUCKET>` → **Adicionar ARNs**.
   - O console monta `arn:aws:s3:::<NOME_BUCKET>` sozinho. **Não** marque **Qualquer** (*Any*) — é
     justamente isso que tornaria a permissão ampla.
6. Desça até o fim da página e clique em **Adicionar mais permissões** (*Add more permissions*).

**Bloco 2 — ler os objetos**

7. Serviço **S3** outra vez → **Ações permitidas** → filtre `GetObject` → marque **GetObject**
   (nível de acesso **Ler** / *Read*).
8. **Recursos** → tipo de recurso **object** → **Específico** → **Adicionar ARNs**:
   - **Nome do bucket**: `<NOME_BUCKET>`
   - **Chave de objeto** (*Object key*): marque **Qualquer** — é isso que gera o `/*` do ARN.
   - **Adicionar ARNs**.
9. **Adicionar mais permissões**.

**Bloco 3 — ler o segredo**

10. Serviço **Secrets Manager** → **Ações permitidas** → filtre `GetSecretValue` → marque
    **GetSecretValue** (nível **Ler**).
11. **Recursos** → tipo **Secret** → **Específico** → **Adicionar ARNs**. Preencha os campos
    separados — **Região** `us-east-1`, **Conta** `<ID_CONTA>`, **ID do segredo**
    `<NOME_SEGREDO>-*` — ou troque para **inserir o ARN manualmente** e cole:

    ```
    arn:aws:secretsmanager:us-east-1:<ID_CONTA>:secret:<NOME_SEGREDO>-*
    ```

    O `*` no lugar do sufixo aleatório de 6 caracteres é o que evita depender de tê-lo decorado.
12. **Adicionar mais permissões**.

**Bloco 4 — listar os segredos**

13. Serviço **Secrets Manager** → filtre `ListSecrets` → marque **ListSecrets** (nível **Listar**).
14. Em **Recursos**, o console não oferece **Específico** aqui: fica só **Todos** (*All*), às vezes
    com o aviso *"as ações a seguir não são compatíveis com permissões em nível de recurso"*. Aceite
    — o motivo está na nota ao fim desta seção.
15. **Próximo** → **Nome da política**: `lab-s3-secrets-ro` → **Criar política**.
16. Abra a política criada e clique na aba **JSON**: dá para conferir que o editor gerou o mesmo
    documento da opção B. A única diferença são os `Sid`, que o editor visual nomeia sozinho como
    `VisualEditor0`, `VisualEditor1` etc. — são só etiquetas, sem efeito nenhum sobre a permissão.

#### Opção B — colando o JSON

1. **IAM** → **Políticas** → **Criar política** → clique na aba **JSON**.
2. Cole o documento abaixo, trocando `<NOME_BUCKET>`, `<ID_CONTA>` e `<NOME_SEGREDO>`:

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Sid": "AllowListBucket",
      "Effect": "Allow",
      "Action": "s3:ListBucket",
      "Resource": "arn:aws:s3:::<NOME_BUCKET>"
    },
    {
      "Sid": "AllowGetBucketObjects",
      "Effect": "Allow",
      "Action": "s3:GetObject",
      "Resource": "arn:aws:s3:::<NOME_BUCKET>/*"
    },
    {
      "Sid": "AllowReadSecretValue",
      "Effect": "Allow",
      "Action": "secretsmanager:GetSecretValue",
      "Resource": "arn:aws:secretsmanager:us-east-1:<ID_CONTA>:secret:<NOME_SEGREDO>-*"
    },
    {
      "Sid": "AllowListSecrets",
      "Effect": "Allow",
      "Action": "secretsmanager:ListSecrets",
      "Resource": "*"
    }
  ]
}
```

3. **Próximo** → **Nome da política**: `lab-s3-secrets-ro` → **Criar política**.

> **Por que o JSON fica todo em inglês.** O documento de política é a linguagem da própria API do
> IAM, não da interface: `Version`, `Statement`, `Effect`, `Allow`, `Action`, `Resource` são palavras
> reservadas e **não mudam** com o idioma do console. O `Sid` é campo livre, mas segue a mesma
> convenção em inglês por dois motivos práticos: é o que você vai encontrar em toda a documentação e
> nos exemplos da AWS, e ele aceita **somente letras e números** — nada de espaços, acentos ou `_`,
> então `LerOSegredo` funcionaria, mas `Ler_o_segredo` ou `LerSegredoDoLaboratório` seriam recusados.

> **Por que `ListSecrets` ficou num bloco separado, com `"Resource": "*"`?**
> O enunciado pede as duas ações de Secrets Manager restritas ao ARN do segredo, mas
> `secretsmanager:ListSecrets` **não aceita permissão em nível de recurso** — ela opera sobre a conta
> inteira e só funciona com `"Resource": "*"`. Se ela for colocada junto com o ARN do segredo, o
> `list-secrets` da validação (passo 6.1) falha com *AccessDenied*. As outras permissões continuam
> estritas: só este bucket, só este segredo.
>
> `s3:ListBucket` também precisa ficar separado de `s3:GetObject`: um age no ARN do **bucket**, o
> outro no ARN dos **objetos**.

### 3.2 Criar o perfil (*role*)

1. IAM → no menu lateral, **Perfis** (*Roles*) → botão **Criar perfil** (*Create role*).
   Não existe "Criar função" nesta tela: **Perfil** é como o console traduz *role* hoje.
2. **Tipo de entidade confiável** (*Trusted entity type*): **Serviço da AWS**. **Caso de uso**
   (*Use case*): **EC2** → **Próximo**.
   Isso gera automaticamente a política de confiança abaixo — confira depois na aba
   **Relações de confiança** (*Trust relationships*) do perfil:

   ```json
   {
     "Version": "2012-10-17",
     "Statement": [
       {
         "Effect": "Allow",
         "Principal": { "Service": "ec2.amazonaws.com" },
         "Action": "sts:AssumeRole"
       }
     ]
   }
   ```

3. Em **Adicionar permissões** (*Add permissions*), busque e marque `lab-s3-secrets-ro` →
   **Próximo**.
4. **Nome do perfil** (*Role name*): `<NOME_ROLE>` → revise → **Criar perfil**.

> **Os dois "perfis" — não confunda.** Com a tradução atual, dois nomes parecidos convivem na conta e
> são coisas diferentes:
>
> - **Perfil** (*role*) — o que você acabou de criar: um conjunto de permissões que alguém, ou algum
>   serviço, pode assumir.
> - **Perfil de instância** (*instance profile*) — o invólucro que entrega esse perfil a uma EC2. É o
>   nome do campo que você vai selecionar no passo 8 da seção 4.
>
> Quando o perfil é criado pelo console com o caso de uso **EC2**, a AWS cria junto o perfil de
> instância, de mesmo nome e já associado — e é por isso que os dois aparecem com o mesmo texto.
> Só quem cria perfil por CLI ou Terraform precisa criar o perfil de instância na mão; pelo console,
> nada a fazer aqui.

---

## 4. Instância EC2 com o perfil anexado

1. Console → **EC2** → **Instâncias** → **Executar instâncias** (*Launch instances*).
2. **Nome e tags** → **Nome**: `lab-ec2-validacao`.
3. **Imagens de aplicação e de SO** (*Application and OS Images*): **Amazon Linux** → AMI
   **Amazon Linux 2023 AMI** (a marcada como **Qualificada para o nível gratuito** / *Free tier
   eligible*), arquitetura **64 bits (x86)**.
4. **Tipo de instância** (*Instance type*): `t3.micro`.
5. **Par de chaves (login)** (*Key pair*):
   - Já tem um par de chaves? Selecione-o.
   - Se não: **Criar novo par de chaves** → nome `lab-ec2-key` → tipo **RSA** → formato **.pem** →
     **Criar par de chaves**. O arquivo `.pem` baixa **uma única vez** — guarde-o.
6. **Configurações de rede** (*Network settings*) → **Editar**:
   - **VPC**: a padrão (*default*). **Sub-rede**: qualquer uma. **Atribuir automaticamente IP
     público** (*Auto-assign public IP*): **Habilitar** — sem IP público não há SSH.
   - **Firewall (grupos de segurança)**: **Criar grupo de segurança**, nome `lab-ec2-sg`.
   - Regra de entrada: **Tipo** `ssh`, **Porta** 22, **Tipo de origem** → **Meu IP** (*My IP*).
     Prefira **Meu IP** a `0.0.0.0/0` — libera a porta só para o seu endereço atual.
7. **Configurar armazenamento** (*Configure storage*): 8 GiB gp3 (padrão).
8. Expanda **Detalhes avançados** (*Advanced details*) e, em **Perfil de instância do IAM**
   (*IAM instance profile*), selecione **`<NOME_ROLE>`**.
   *Este é o passo que liga a instância ao perfil — é o que faz todo o resto funcionar.*
9. Ainda em Detalhes avançados, confirme **Versão de metadados** (*Metadata version*):
   **Somente V2 (token obrigatório)** — é o padrão e o modo seguro do IMDS.
10. **Executar instância**.
11. Vá em **Instâncias**, espere **Estado da instância** = **Executando** (*Running*) e
    **Verificação de status** = **2/2 verificações aprovadas**, e copie o
    **Endereço IPv4 público**.

> Esqueceu o perfil no lançamento? Não precisa recriar a instância: selecione-a → **Ações** →
> **Segurança** → **Modificar perfil do IAM** (*Modify IAM role*) → escolha `<NOME_ROLE>` →
> **Atualizar perfil do IAM**. A credencial aparece no IMDS em poucos segundos.

---

## 5. Conectar por SSH

No PowerShell (Windows), a partir da pasta onde está o `.pem`:

```powershell
# UMA VEZ por arquivo de chave: a chave precisa ser legível só pelo seu usuário,
# senão o OpenSSH recusa usá-la. A permissão fica gravada no arquivo.
icacls .\lab-ec2-key.pem /inheritance:r
icacls .\lab-ec2-key.pem /grant:r "$($env:USERNAME):(R)"

# troque <IP_PUBLICO> pelo Endereço IPv4 público da instância, SEM os sinais < >
# ex.: ssh -i .\lab-ec2-key.pem ec2-user@54.242.13.7
ssh -i .\lab-ec2-key.pem ec2-user@<IP_PUBLICO>
```

Deixar o marcador como está resulta em
`ssh: Could not resolve hostname <ip_publico>` — o ssh tentou resolver o texto literal. O IP sai em
**EC2 → Instâncias** → a instância → aba **Detalhes** → **Endereço IPv4 público**, e **muda a cada
parada e nova inicialização** da instância (só um Elastic IP o fixa).

Se em vez de erro de nome der **timeout**, o problema é o grupo de segurança: a regra foi criada com
o **Meu IP** daquele momento. Mudou de rede (ou seu provedor trocou seu IP)? **EC2 → Grupos de
segurança → `lab-ec2-sg` → Editar regras de entrada** → **Origem: Meu IP** de novo.

Aceite o fingerprint na primeira conexão (`yes`). O usuário do Amazon Linux 2023 é `ec2-user`.

### O que se repete a cada conexão, e o que não

| Comando | Quando rodar |
|---|---|
| `icacls ...` (as duas linhas) | **uma vez por arquivo `.pem`** — repita só se baixar a chave outra vez, copiar para outra pasta/máquina, ou criar um par de chaves novo. Conferir: `icacls .\lab-ec2-key.pem` deve mostrar apenas o seu usuário com `(R)` |
| `ssh -i ... ec2-user@<IP>` | **sempre** |
| Aceitar o fingerprint (`yes`) | só na primeira conexão a cada host — fica salvo em `~\.ssh\known_hosts` |

Para não digitar caminho e IP todas as vezes, mova a chave para `~\.ssh\` e crie `~\.ssh\config`:

```
Host lab-ec2
  HostName 54.242.13.7
  User ec2-user
  IdentityFile ~/.ssh/lab-ec2-key.pem
```

Daí em diante basta `ssh lab-ec2`, e você só edita o `HostName` quando o IP público mudar.

> Recriou a instância e o IP foi reaproveitado? O ssh avisa
> `WARNING: REMOTE HOST IDENTIFICATION HAS CHANGED`. É o host key novo da máquina nova, não invasão:
> apague a entrada velha com `ssh-keygen -R 54.242.13.7` e conecte de novo.

Alternativa sem SSH e sem abrir a porta 22: **EC2 Instance Connect** (botão **Conectar** → aba *EC2
Instance Connect* → **Conectar**), que abre um terminal no navegador. Serve igualmente para este
laboratório.

---

## 6. Validação dentro da EC2

O Amazon Linux 2023 já traz **AWS CLI v2** e `python3` instalados. Todos os comandos abaixo rodam
**sem nenhuma credencial configurada** — é justamente o ponto do laboratório.

Daqui para frente nada está traduzido: a CLI, as saídas e os erros saem em inglês mesmo com o
console em português, e os códigos de erro da API (`AccessDenied`, `NoSuchBucket`) são sempre os
mesmos em qualquer idioma.

### 6.1 Listar os segredos disponíveis

```bash
aws secretsmanager list-secrets --region us-east-1 \
  --query 'SecretList[].Name' --output table
```

`<NOME_SEGREDO>` deve aparecer na lista.

### 6.2 Ler o segredo e extrair o nome do bucket

```bash
# o valor do segredo vem como string JSON
aws secretsmanager get-secret-value \
  --secret-id <NOME_SEGREDO> \
  --region us-east-1 \
  --query SecretString --output text
# -> {"bucket": "<NOME_BUCKET>"}

# extrai só o campo bucket para uma variável
BUCKET=$(aws secretsmanager get-secret-value \
  --secret-id <NOME_SEGREDO> \
  --region us-east-1 \
  --query SecretString --output text \
  | python3 -c 'import sys, json; print(json.load(sys.stdin)["bucket"])')

echo "$BUCKET"
```

Preferindo `jq`: `sudo dnf install -y jq` e então `... --output text | jq -r .bucket`.

### 6.3 Listar os arquivos do bucket usando esse nome

```bash
aws s3 ls "s3://$BUCKET/"
```

Os arquivos do passo 1 devem aparecer. Baixar um objeto também funciona, porque a política concede
`s3:GetObject`:

```bash
aws s3 cp "s3://$BUCKET/teste1.txt" - | head
```

E o que **não** deve funcionar — prova de que a permissão é mínima:

```bash
aws s3 ls                              # AccessDenied: falta s3:ListAllMyBuckets
aws s3 rm "s3://$BUCKET/teste1.txt"    # AccessDenied: falta s3:DeleteObject
```

### 6.4 Confirmar que a EC2 usa o perfil, e não chaves estáticas

Quatro verificações, da mais direta à mais conclusiva.

**a) Quem a CLI diz que você é**

```bash
aws sts get-caller-identity
```

```json
{
  "UserId": "AROAEXEMPLO...:i-0abc123def456",
  "Account": "<ID_CONTA>",
  "Arn": "arn:aws:sts::<ID_CONTA>:assumed-role/<NOME_ROLE>/i-0abc123def456"
}
```

O ARN é `assumed-role/<NOME_ROLE>/<id-da-instancia>`: credencial **temporária**, obtida assumindo
o perfil, com o ID da própria instância como nome de sessão. Com chave estática de usuário IAM, o
ARN seria `arn:aws:iam::<ID_CONTA>:user/alguem`.

**b) De onde a CLI tirou a credencial**

```bash
aws configure list
```

```
      Name                    Value             Type    Location
      ----                    -----             ----    --------
   profile                <not set>             None    None
access_key     ****************ABCD         iam-role
secret_key     ****************wXyZ         iam-role
    region                us-east-1             imds
```

A coluna **Type** com `iam-role` significa que a credencial veio dos metadados da instância. Com
chaves estáticas apareceria `shared-credentials-file` ou `env`.

**c) Não existe credencial estática na máquina**

```bash
ls -la ~/.aws/ 2>/dev/null     # não deve existir arquivo "credentials"
env | grep -i AWS              # sem AWS_ACCESS_KEY_ID / AWS_SECRET_ACCESS_KEY
sudo ls -la /root/.aws/ 2>/dev/null
```

**d) Ler a credencial direto do IMDSv2**

```bash
TOKEN=$(curl -sX PUT "http://169.254.169.254/latest/api/token" \
  -H "X-aws-ec2-metadata-token-ttl-seconds: 21600")

# qual perfil está anexado
curl -s -H "X-aws-ec2-metadata-token: $TOKEN" \
  http://169.254.169.254/latest/meta-data/iam/security-credentials/
# -> <NOME_ROLE>

# a credencial temporária em si
curl -s -H "X-aws-ec2-metadata-token: $TOKEN" \
  http://169.254.169.254/latest/meta-data/iam/security-credentials/<NOME_ROLE>
```

```json
{
  "Code": "Success",
  "Type": "AWS-HMAC",
  "AccessKeyId": "ASIA...",
  "SecretAccessKey": "...",
  "Token": "...",
  "Expiration": "2026-09-27T03:14:00Z"
}
```

Dois sinais definitivos: o `AccessKeyId` começa com **`ASIA`** (chave temporária de sessão STS —
chaves estáticas de usuário IAM começam com `AKIA`) e existe um campo **`Expiration`**, ou seja, a
credencial tem prazo e é renovada sozinha pelo agente da instância. Nada disso é gravado em disco.

> **Teste de contraste (opcional).** No console: **Ações → Segurança → Modificar perfil do IAM →
> Remover perfil do IAM**. Em seguida, na EC2, repita `aws sts get-caller-identity`: a resposta passa
> a ser *Unable to locate credentials*. Reanexe o perfil para continuar.

---

## 7. Limpeza

Nesta ordem, para não deixar cobrança pendurada:

1. **EC2** → selecione a instância → **Estado da instância** → **Encerrar instância**
   (*Terminate instance*) — é a exclusão definitiva; não confunda com **Parar instância** (*Stop*),
   que mantém o disco e continua gerando cobrança de EBS.
2. **EC2** → **Grupos de segurança** → apague `lab-ec2-sg` (só depois que a instância estiver
   **Encerrada** / *terminated*).
3. **EC2** → **Pares de chaves** → apague `lab-ec2-key`, se não for reusar.
4. **S3** → selecione o bucket → **Esvaziar** (*Empty*) → digite exatamente a frase de confirmação
   que o console mostrar (`permanently delete` em inglês, **`excluir permanentemente`** na versão
   traduzida) → depois **Excluir** (digite o nome do bucket). Um bucket só é apagado vazio.
5. **Secrets Manager** → abra `<NOME_SEGREDO>` → **Ações** → **Excluir segredo**. O prazo mínimo de
   recuperação é de **7 dias**: a cobrança para no momento do agendamento, mas o nome só fica livre
   para reuso quando a exclusão se efetiva.
6. **IAM** → **Perfis** → apague `<NOME_ROLE>` → **Políticas** → apague `lab-s3-secrets-ro`.
   Apagar o perfil pelo console remove também o perfil de instância criado junto com ele.

---

## Referência rápida dos ARNs

| Recurso | Formato do ARN |
|---|---|
| Bucket | `arn:aws:s3:::<NOME_BUCKET>` |
| Objetos do bucket | `arn:aws:s3:::<NOME_BUCKET>/*` |
| Segredo | `arn:aws:secretsmanager:us-east-1:<ID_CONTA>:secret:<NOME_SEGREDO>-??????` |
| Perfil (*role*) | `arn:aws:iam::<ID_CONTA>:role/<NOME_ROLE>` |
| Perfil de instância | `arn:aws:iam::<ID_CONTA>:instance-profile/<NOME_ROLE>` |
| Sessão da EC2 com o perfil | `arn:aws:sts::<ID_CONTA>:assumed-role/<NOME_ROLE>/<id-da-instancia>` |

ARNs de S3 não têm região nem conta (o nome do bucket já é global), enquanto os de Secrets Manager
têm as duas coisas mais o sufixo aleatório. E note que no ARN o perfil continua sendo `role` —
nunca `perfil` nem `funcao`: o console traduz a tela, a API não.

---

## Glossário console em inglês → português

Para quando você achar um tutorial em inglês e precisar encontrar o botão equivalente:

| Inglês | Português (Brasil) |
|---|---|
| Role / Roles | **Perfil / Perfis** (material antigo: Função) |
| Create role / Role name | Criar perfil / Nome do perfil |
| Modify IAM role / Remove IAM role | Modificar perfil do IAM / Remover perfil do IAM |
| Policy / Permissions policy | Política / Política de permissões |
| Policy editor: Visual / JSON | Editor de políticas: Visual / JSON |
| Select a service / Actions allowed | Selecionar um serviço / Ações permitidas |
| Access level: List / Read / Write | Nível de acesso: Listar / Ler / Gravar |
| Resources: Specific / Any / All | Recursos: Específico / Qualquer / Todos |
| Add ARNs / Add more permissions | Adicionar ARNs / Adicionar mais permissões |
| Trusted entity / Trust relationships | Entidade confiável / Relações de confiança |
| Instance profile | Perfil de instância |
| Launch instance | Executar instância |
| Stop / Terminate instance | Parar / Encerrar instância |
| Instance state: Running / Pending | Estado da instância: Executando / Pendente |
| Status check: 2/2 checks passed | Verificação de status: 2/2 verificações aprovadas |
| Key pair | Par de chaves |
| Security group / Inbound rules | Grupo de segurança / Regras de entrada |
| Subnet / Auto-assign public IP | Sub-rede / Atribuir automaticamente IP público |
| Advanced details | Detalhes avançados |
| Free tier eligible | Qualificado para o nível gratuito |
| Create bucket / Bucket name | Criar bucket / Nome do bucket |
| Block all public access | Bloquear todo o acesso público |
| Objects / Upload / Add files | Objetos / Fazer upload / Adicionar arquivos |
| Empty (bucket) | Esvaziar |
| Store a new secret / Secret type | Armazenar um novo segredo / Tipo de segredo |
| Other type of secret | Outro tipo de segredo |
| Key/value pairs / Plaintext | Pares de chave/valor / Texto simples |
| Encryption key / Automatic rotation | Chave de criptografia / Rotação automática |
| Next / Actions / Edit / Save | Próximo / Ações / Editar / Salvar |
