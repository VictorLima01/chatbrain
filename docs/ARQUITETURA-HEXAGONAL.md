# Arquitetura hexagonal

O backend segue **Ports & Adapters** (arquitetura hexagonal), usando como referência o artigo
[*Ready for changes with Hexagonal Architecture*](https://netflixtechblog.com/ready-for-changes-with-hexagonal-architecture-b315ec967749),
de Damir Svrtan e Sergii Makagon, do time de Studio Workflows da Netflix.

> **Nota de fidelidade:** o Medium bloqueia acesso automatizado (HTTP 403), então as definições
> abaixo vêm dos trechos verbatim recuperados do artigo, indicados com citação. O restante do
> documento aplica esses conceitos a este projeto e não deve ser lido como palavra da Netflix.

---

## O problema que a arquitetura resolve

A Netflix precisou construir uma aplicação nova acessando dados espalhados por muitos serviços, com
protocolos diferentes — gRPC, JSON API, GraphQL. A pergunta era: como escrever a regra de negócio sem
que ela fique refém de qual protocolo está do outro lado hoje?

Aqui o problema tem a mesma forma. Este projeto já trocou de fundação três vezes em poucos dias:

| Já mudou | Pode mudar |
|---|---|
| A pasta do material (raiz → `docs/` → `ingestao/`) | Postgres local vira Neon gerenciado |
| **O material saiu do disco e foi para o S3** | Embeddings saem do container local e vão para a Voyage AI ([FASE-2-AWS.md](FASE-2-AWS.md)) |
| **A base virou vários módulos isolados** | O modelo troca de `claude-opus-5` para `claude-sonnet-5` |

Nenhuma dessas mudanças tem a ver com **o que o sistema faz**: recuperar trechos relevantes e
responder citando a fonte. A arquitetura existe para que elas não encostem nessa regra.

> A mudança para o S3 é o caso de teste que a arquitetura estava esperando. O adaptador novo
> (`S3MaterialCatalog`) tem ~170 linhas e o antigo continuou existindo, atrás de um
> `@ConditionalOnProperty`. O `IngestMaterialInteractor` — onde moram as regras de quando pular um
> arquivo e como classificar o nível — não mudou por causa disso; mudou por causa dos módulos, que
> **são** regra de negócio.

---

## Os três conceitos, na definição da Netflix

O artigo organiza a lógica de negócio em três peças. As definições abaixo são verbatim:

**Entities**
> *"Entities are domain objects (e.g., a Movie or a Shooting Location) — they have no knowledge of
> where they're stored."*

Aqui: `Message`, `RetrievedChunk`, `DocumentSummary`, `IngestReport`, `StudyModule`, `AccessLevel` —
todos em `domain/model/Entities.java`. Um `RetrievedChunk` não sabe que veio de um `SELECT` com
`pgvector`; um `StudyModule` não sabe que seu material mora num prefixo de bucket.

**Repositories**
> *"Repositories are the interfaces to getting entities as well as creating and changing them. They
> keep a list of methods that are used to communicate with data sources and return a single entity or
> a list of entities."*

Aqui são as **portas de saída** (`domain/port/out/`). `KnowledgeRepositoryPortOut.search(...)` declara
*o que* o domínio precisa; que isso vire busca híbrida com Reciprocal Rank Fusion é assunto do
adaptador.

**Interactors**
> *"Interactors are classes that orchestrate and perform domain actions."*

Aqui: `AskQuestionInteractor`, `IngestMaterialInteractor`, `ConversationInteractor`,
`KnowledgeInteractor`, `ModuleInteractor` (`domain/service/`). São eles que executam a sequência do
RAG, as regras da ingestão e as de criação e remoção de módulos.

---

## A estrutura

```
com.chat.cloudability.ibm/
│
├── domain/                          ◄── o hexágono: zero framework
│   ├── model/Entities.java              Entities
│   ├── port/
│   │   ├── in/                          portas de ENTRADA (driving)
│   │   │   ├── AskQuestionPortIn
│   │   │   ├── IngestMaterialPortIn
│   │   │   ├── ManageConversationsPortIn
│   │   │   ├── ManageKnowledgePortIn
│   │   │   └── ManageModulesPortIn
│   │   └── out/                         portas de SAÍDA (driven) = Repositories
│   │       ├── KnowledgeRepositoryPortOut
│   │       ├── ConversationRepositoryPortOut
│   │       ├── ModuleRepositoryPortOut
│   │       ├── EmbeddingPortOut
│   │       ├── LanguageModelPortOut
│   │       ├── ContentExtractorPortOut
│   │       ├── ImageTranscriberPortOut
│   │       └── MaterialCatalogPortOut
│   └── service/                         Interactors + serviços de domínio
│       ├── AskQuestionInteractor
│       ├── IngestMaterialInteractor
│       ├── ConversationInteractor
│       ├── KnowledgeInteractor
│       ├── ModuleInteractor
│       ├── Modules                      resolução de módulo, compartilhada
│       └── TextChunker
│
├── adapter/
│   ├── in/web/                      ◄── driving adapters
│   │   ├── ChatController               HTTP + SSE
│   │   ├── KnowledgeController          leitura da base
│   │   ├── ModuleController             lista de módulos + alçada da requisição
│   │   ├── AdminController              módulos e material (alçada admin)
│   │   ├── AccessResolver               quem está chamando — hoje, configuração
│   │   ├── AdminAccessInterceptor       porteiro de /api/admin
│   │   ├── RestExceptionHandler         exceções do domínio → status HTTP
│   │   └── dto/WebDtos                  contratos HTTP, separados das entidades
│   └── out/                         ◄── driven adapters
│       ├── anthropic/                   ClaudeLanguageModel · ClaudeImageTranscriber
│       ├── embedding/                   TeiEmbeddingAdapter
│       ├── persistence/                 JdbcKnowledgeRepository · JdbcConversationRepository
│       │                                JdbcModuleRepository
│       ├── s3/                          S3MaterialCatalog          ◄── padrão
│       └── filesystem/                  FileSystemMaterialCatalog  ◄── sem Docker
│                                        DocumentContentExtractor
│
└── config/                          ◄── a montagem
    ├── DomainConfig                     instancia os interactors e liga as portas
    ├── MaterialConfig                   cliente S3: LocalStack ou IAM Role, mesmo bean
    ├── AppProperties · AnthropicConfig · WebConfig
```

### Onde as alçadas moram, e por quê

`AccessLevel` é uma entidade do domínio, mas a checagem fica no **adaptador de entrada**
(`AdminAccessInterceptor`). É uma escolha, não um descuido: "quem está falando comigo" é uma
pergunta sobre o transporte, não sobre o assunto. O domínio define o que cada operação faz; a borda
decide se aquela requisição pode chamá-la.

O ganho prático aparece no dia em que o login existir: muda-se um método —
`AccessResolver.resolve(...)` — e nem o domínio nem os controllers percebem.

### Os dois lados do hexágono

| | **Driving (esquerda)** | **Driven (direita)** |
|---|---|---|
| Quem inicia | o mundo externo chama o domínio | o domínio chama o mundo externo |
| Porta | `port/in/` — casos de uso | `port/out/` — dependências |
| Quem implementa a porta | o **domínio** | o **adaptador** |
| Exemplo | `ChatController` → `AskQuestionPortIn` | `AskQuestionInteractor` → `LanguageModelPortOut` |

A assimetria é o ponto: nos dois lados **a interface pertence ao domínio**. É isso que faz a seta de
dependência apontar sempre para dentro.

---

## A regra de dependência

```
adapter.in  ──────▶  domain  ◀──────  adapter.out
                       │
                    config ──▶ monta tudo
```

- O domínio **não importa** nada de `adapter.*`, nem de Spring, Anthropic SDK, PDFBox, POI, Jackson
  ou JDBC.
- Os adaptadores importam `domain.port.*` e `domain.model.*` — **nunca** `domain.service.*`.
- `config` é o único lugar que conhece os dois lados.

### Isso é verificado automaticamente

`HexagonalBoundariesTest` lê os imports do código-fonte e quebra o build se a regra for violada. Foi
testado injetando um `import org.springframework.stereotype.Service` num interactor — o build falhou
apontando o arquivo e a linha.

Uma regra de arquitetura que depende só de disciplina na revisão vaza em algumas semanas. Esta falha
sozinha.

### Domínio sem anotações

Nenhum interactor tem `@Service` ou `@Component`. Quem os instancia é `DomainConfig`, com `new`:

```java
@Bean
public AskQuestionPortIn askQuestionInteractor(
        LanguageModelPortOut languageModel,
        EmbeddingPortOut embeddings,
        KnowledgeRepositoryPortOut knowledge,
        ConversationRepositoryPortOut conversations,
        ModuleRepositoryPortOut modules,
        RagSettings settings) {
    return new AskQuestionInteractor(
            languageModel, embeddings, knowledge, conversations, modules, settings);
}
```

Repare que os parâmetros são **portas**. O Spring resolve cada uma para o adaptador registrado —
trocar de implementação é mudar qual bean existe, sem tocar no domínio.

O mesmo vale para configuração: `RagSettings` é um record do domínio. Os interactors nunca veem
`@ConfigurationProperties`.

---

## O que ganha, na prática

### 1. Testar a regra de negócio sem infraestrutura

`AskQuestionInteractorTest` exercita o fluxo completo de RAG — resolver o módulo, vetorizar, buscar,
montar o prompt, gerar, persistir — com implementações em memória das portas de saída. **Sem Spring,
sem Postgres, sem chamar a API.** Doze testes rodam em ~120 ms.

Isso permite testar coisas que seriam caras de outra forma:

```java
@Test
void semChaveConfiguradaFalhaAntesDeChamarOModelo() {
    model.configured = false;
    // ... espera IllegalStateException e nada gravado no banco
}

@Test
void aBuscaEsempreLimitadaAoModuloDaPergunta() {
    // o id do módulo tem que chegar ao repositório — é ele que impede o vazamento
}

@Test
void moduloDesconhecidoFalhaEmVezDeCairNoPadrao() {
    // responder com o material do módulo errado seria pior do que não responder
}
```

O isolamento entre módulos é justamente o tipo de garantia difícil de verificar com o sistema
inteiro de pé — exigiria dois módulos populados, um banco e uma chave de API — e trivial de
verificar aqui: a porta falsa registra qual `moduleId` recebeu.

### 2. Trocar implementação sem tocar na regra

As mudanças previstas para a fase 2 viram, cada uma, **uma classe nova**:

| Mudança | O que se escreve | O que muda no domínio |
|---|---|---|
| ✅ **Material vindo do S3** | `S3MaterialCatalog implements MaterialCatalogPortOut` | nada |
| Embeddings via Voyage AI | `VoyageEmbeddingAdapter implements EmbeddingPortOut` | nada |
| Trocar Claude por outro modelo | outra implementação de `LanguageModelPortOut` | nada |
| Expor o chat por WhatsApp | outro adaptador de entrada chamando `AskQuestionPortIn` | nada |
| Login de verdade | `AccessResolver` passa a ler o usuário autenticado | nada |

O S3 já saiu da coluna de previsão. Note que a `MaterialCatalogPortOut` **ganhou métodos**
(`store`, `deleteModule`) — porque o domínio passou a precisar deles, não porque o S3 os oferece. A
porta continua descrevendo a necessidade, não a tecnologia.

### 3. A regra de negócio fica visível

`AskQuestionInteractor` tem ~60 linhas de fluxo legível de cima a baixo. As regras de fundamentação
moram nele — e essa é uma decisão deliberada: as instruções ("cite a fonte", "nunca afirme que algo
não existe apenas porque não está no material", "você não tem acesso ao material de outro módulo")
**são regra de negócio**, não configuração de integração. Se estivessem no adaptador da Anthropic,
trocar de provedor perderia as regras do tutor junto.

A **persona** do módulo, essa sim, é dado: mora em `study_module.persona` e o administrador edita à
vontade. A linha divisória é quem pode mudar o quê — o que o administrador escolhe é dado; o que o
sistema garante é domínio.

---

## O custo

Ser honesto sobre o outro lado da moeda:

- **Mais arquivos.** Foi de 11 classes para ~38. Para um CRUD simples, seria burocracia.
- **Indireção.** Ler o fluxo exige pular da porta para o adaptador.
- **DTOs duplicados.** `WebDtos.SourceResponse` e `Entities.SourceRef` carregam os mesmos campos hoje.
  A separação se paga quando o contrato HTTP precisa mudar sem arrastar o domínio.

Vale aqui porque a aplicação tem **quatro integrações externas** (LLM, embeddings, banco vetorial,
armazenamento do material), e uma delas já foi trocada de fato — do disco para o S3 — sem que a
regra de negócio precisasse ser reaberta.

---

## Mapa rápido: onde mexer

| Quero... | Vou em |
|---|---|
| mudar as regras que valem para todo tutor | `domain/service/AskQuestionInteractor` (`GROUNDING_RULES`) |
| mudar o papel do tutor de um módulo | a persona do módulo, na área de administração |
| mexer nas regras de módulo (slug, remoção) | `domain/service/ModuleInteractor` |
| implementar autenticação | `adapter/in/web/AccessResolver` — e só |
| mudar onde o material fica | `adapter/out/s3/` ou `adapter/out/filesystem/` |
| mudar quantos trechos entram no prompt | `app.rag.top-k` no `application.yml` |
| mudar a estratégia de busca | `adapter/out/persistence/JdbcKnowledgeRepository.search` |
| suportar um novo formato de arquivo | `adapter/out/filesystem/DocumentContentExtractor` |
| mudar as regras de quando pular um arquivo | `domain/service/IngestMaterialInteractor` |
| trocar o provedor de embeddings | nova classe em `adapter/out/embedding/` |
| adicionar um endpoint | `adapter/in/web/` + porta em `domain/port/in/` se for caso de uso novo |

---

## Fonte

- Damir Svrtan e Sergii Makagon. *Ready for changes with Hexagonal Architecture*. Netflix TechBlog.
  [netflixtechblog.com](https://netflixtechblog.com/ready-for-changes-with-hexagonal-architecture-b315ec967749)
