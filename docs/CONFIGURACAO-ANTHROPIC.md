# Configuração da API da Anthropic

Como obter a credencial que o backend usa para falar com o Claude.

---

## ⚠️ Primeiro: o plano Pro **não** dá acesso à API

Esta é a dúvida mais comum e a resposta é direta: **assinatura do Claude (Pro/Max) e API são produtos
separados, com cobranças separadas.**

| | Claude Pro / Max | API da Anthropic |
|---|---|---|
| Onde se usa | claude.ai (web, desktop, mobile) | seu código, via SDK ou HTTP |
| Onde se contrata | [claude.com/pricing](https://claude.com/pricing) | [platform.claude.com](https://platform.claude.com) |
| Como se paga | mensalidade fixa | por token consumido |
| Serve para este projeto? | ❌ **não** | ✅ **sim** |

A documentação oficial é explícita: os pré-requisitos para a primeira chamada são **uma conta no
Claude Console** e **uma API key** — a assinatura não aparece em lugar nenhum. E o `ant auth login`,
mesmo no fluxo OAuth, autentica *"against the Claude Console"*, com organização e workspace: é a
conta de desenvolvedor, não a de consumidor.

### Então por que o Claude Code funciona com a assinatura?

Porque o **Claude Code é uma exceção** — ele aceita login com Pro/Max. Isso confunde bastante, e com
razão: você está usando o Claude agora mesmo pela assinatura.

Mas a exceção vale só para o Claude Code. Este projeto tem uma aplicação Java chamando
`api.anthropic.com` diretamente pelo SDK — esse caminho exige crédito de API. Não há como apontar o
backend para a sua assinatura.

**Resumo:** para rodar este chat você precisa colocar crédito no Console, mesmo já pagando o Pro.

---

## Passo a passo

### 1. Criar a conta no Console

Acesse **[platform.claude.com](https://platform.claude.com)**. Pode usar o mesmo e-mail da
assinatura — as contas são independentes, mesmo com login igual.

### 2. Adicionar crédito

**Settings → Billing** → adicione um cartão e compre crédito.

O modelo é **pré-pago**: você compra crédito e ele é consumido conforme o uso. Comece com o menor
valor possível — dá para ver o custo real antes de decidir recarregar.

### 3. Criar a API key

**Settings → API Keys** → *Create Key*.

A chave (`sk-ant-api03-...`) **só aparece uma vez**. Copie na hora; se perder, é só apagar e criar
outra.

### 4. Colocar no projeto

No arquivo `.env` da raiz (copiado do `.env.example`):

```bash
ANTHROPIC_API_KEY=sk-ant-api03-...
```

Reinicie o backend. Confirme na tela **Sobre** — o selo no cabeçalho deve mostrar o modelo em vez de
"ANTHROPIC_API_KEY ausente".

### 5. Definir um limite de gasto

**Settings → Limits** → configure um teto mensal e um alerta por e-mail.

Faça isso **antes** de usar. É a proteção contra um loop acidental ou, mais tarde, contra a aplicação
exposta na internet sem autenticação.

---

## Quanto custa

O `claude-opus-5` custa **US$ 5 por milhão de tokens de entrada** e **US$ 25 por milhão de saída**.

### Indexar o material: praticamente nada

Os embeddings rodam **localmente** no container — não passam pela API. A única chamada da ingestão é
a transcrição do `Perguntas.png` pela visão do Claude, que custa centavos e só se repete se o arquivo
mudar.

Indexar todo o material de `ingestao/` sai por **menos de US$ 0,05**.

### Cada pergunta: alguns centavos

Uma pergunta com RAG consome cerca de 4 a 6 mil tokens de entrada (prompt de sistema + 8 trechos
recuperados + histórico) e 800 a 1500 de saída.

| | Estimativa |
|---|---|
| Por pergunta | ~US$ 0,05 |
| 100 perguntas | ~US$ 5 a 7 |
| Uma sessão de estudo (30 perguntas) | ~US$ 1,50 a 2,00 |

O cache do prompt de sistema (já implementado) reduz parte disso em conversas longas.

### Se quiser gastar menos

**Primeiro: use o botão.** O composer tem um toggle **Completo / Econômico** — não precisa mexer em
variável nenhuma nem reiniciar o backend. Ele corta três coisas de uma vez (3 trechos em vez de 8,
`effort: low`, e instrução de resposta direta). Medido na mesma pergunta:

| | Completo | Econômico |
|---|---|---|
| Texto de saída | ~3 500 caracteres | ~250 a 380 |
| Tempo | 19 a 20 s | 2 a 4 s |

São **90–93% menos tokens de saída**, que é o lado caro da conta (a saída custa 5× a entrada). A
citação de fonte e o limite do módulo continuam valendo — o modo encurta a resposta, não afrouxa o
rigor.

Use o econômico para conferir um detalhe ou revisar o que já estudou, e o completo quando estiver
aprendendo o tema.

### Quanto custa mandar um print

Medido na mesma pergunta, com as contagens que o backend registra no log:

| | Entrada | Saída | Custo |
|---|---|---|---|
| Texto, completo | 4 008 | 1 226 | ~US$ 0,051 |
| Texto, econômico | 1 578 | 231 | ~US$ 0,014 |
| **Print, completo** | 5 699 | 1 726 | **~US$ 0,072** |
| **Print, econômico** | 3 219 | 422 | **~US$ 0,027** |

Um print custa ~41% a mais que a mesma pergunta sem imagem. Mas a imagem em si responde por
**menos da metade** desse aumento: ela adiciona ~1 690 tokens de entrada (~US$ 0,008), e o resto vem
de a **resposta ficar mais longa** (+500 tokens de saída, ~US$ 0,013) — porque transcrever o que
está na tela dá mais texto.

Por isso o modo econômico é o lever que mais rende em perguntas com print: **−63%**.

**Reduzir a resolução ajuda?** Depende do tamanho do print. O custo de uma imagem é
`ceil(largura/28) × ceil(altura/28)` tokens:

| Print | Sem redução | Reduzido a 1280 px |
|---|---|---|
| 1296 × 805 (slide) | 1 363 tokens | 1 334 — quase nada |
| 1920 × 1080 (tela cheia) | 2 691 tokens | 1 196 — **56% menos** |
| 3840 × 2160 (4K) | 4 784 tokens | 1 196 — **75% menos** |

A redução é automática e só entra acima de 1280 px de lado maior, então prints pequenos passam
intactos. **O maior ganho, porém, é recortar em vez de capturar a tela inteira** — um recorte de
800 × 600 custa 638 tokens contra 2 691 da tela cheia, sem perder nitidez nenhuma.

> Medido: um slide reduzido até 800 px ainda teve o texto transcrito sem um erro. O padrão de
> 1280 px deixa o dobro dessa folga. Se um enunciado sair mal lido, suba com
> `ANTHROPIC_IMAGE_MAX_EDGE=1568` ou `0` para desligar.

### Vendo o que cada pergunta custou

O backend registra cada chamada:

```bash
docker compose logs -f api | grep Tokens
```

```
Tokens [economy]: entrada 3219 (cache: 920 lido / 0 gravado), saida 422, 1 imagem(ns)
```

`cache: N lido` confirma que o prompt de sistema está sendo reaproveitado — se esse número ficar
sempre em zero, algo está invalidando o cache a cada chamada.

**Depois, se ainda quiser mais:**

```bash
ANTHROPIC_MODEL=claude-sonnet-5   # US$ 2 / US$ 10 — cerca de 60% mais barato
ANTHROPIC_EFFORT=medium           # baixa o padrão do modo completo
RAG_ECONOMY_TOP_K=2               # aperta ainda mais o modo econômico
```

Para revisar conteúdo já estudado o Sonnet costuma bastar. Para questões capciosas de prova, o Opus
erra menos.

> Trocar de modelo tem um custo escondido: o cache do prompt é por modelo, então alternar entre dois
> perde o cache de ambos. O toggle de modo não tem esse problema — cada modo mantém o próprio cache.

---

## Verificando se funcionou

Com o backend no ar:

```bash
curl http://localhost:8080/api/knowledge/status
```

`"anthropicConfigured": true` significa que a chave chegou ao backend — **mas não que ela é válida**.
Só uma chamada real confirma isso. Faça uma pergunta pelo chat; se a chave estiver errada ou sem
crédito, o erro volta no evento `error` do stream e aparece na interface.

Teste isolado da chave, sem subir nada:

```bash
curl https://api.anthropic.com/v1/messages \
  -H "content-type: application/json" \
  -H "x-api-key: $ANTHROPIC_API_KEY" \
  -H "anthropic-version: 2023-06-01" \
  -d '{"model":"claude-opus-5","max_tokens":50,
       "messages":[{"role":"user","content":"diga apenas: ok"}]}'
```

---

## Erros comuns

| Erro | O que aconteceu |
|---|---|
| `401 authentication_error` | Chave inválida, revogada, ou com espaço/aspas sobrando no `.env` |
| `400 invalid_request_error` sobre créditos | Conta sem saldo — recarregue em Billing |
| `429 rate_limit_error` | Limite da sua tier. Contas novas começam em limites baixos, que sobem conforme o histórico de gasto |
| `ANTHROPIC_API_KEY nao configurada` | A variável não chegou ao backend. No Docker, confira se o `.env` está na raiz e reinicie com `docker compose up` |
| `529 overloaded_error` | Sobrecarga temporária da API. Tente de novo |

---

## Segurança

- **A chave nunca vai para o Git.** O `.env` já está no `.gitignore` — confirme antes do primeiro
  commit com `git status`.
- **Se vazar, revogue imediatamente** em Settings → API Keys. Apagar do código não basta: o histórico
  do Git guarda.
- **Na nuvem**, use SSM Parameter Store (SecureString) em vez de variável de ambiente em texto puro —
  ver [FASE-2-AWS.md](FASE-2-AWS.md).
- **Esta aplicação não tem autenticação.** Publicada sem proteção, qualquer visitante gasta o seu
  crédito. O limite de gasto do passo 5 é a rede de segurança, mas não substitui um login.

---

## Nota sobre a versão do SDK

O projeto usa `anthropic-java` **2.34.0**, validada em compilação. A documentação oficial já cita a
**2.58.0** como atual. Atualizar é trocar `<anthropic.version>` no `ibm/pom.xml` e recompilar —
recomendado antes de publicar, mas não é bloqueio para rodar local.
