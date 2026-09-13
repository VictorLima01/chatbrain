package com.chat.cloudability.ibm.domain.service;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import com.chat.cloudability.ibm.domain.model.Entities.AnswerMode;
import com.chat.cloudability.ibm.domain.model.Entities.ImageAttachment;
import com.chat.cloudability.ibm.domain.model.Entities.Message;
import com.chat.cloudability.ibm.domain.model.Entities.RagSettings;
import com.chat.cloudability.ibm.domain.model.Entities.RetrievedChunk;
import com.chat.cloudability.ibm.domain.model.Entities.SourceRef;
import com.chat.cloudability.ibm.domain.model.Entities.StudyModule;
import com.chat.cloudability.ibm.domain.port.in.AskQuestionPortIn;
import com.chat.cloudability.ibm.domain.port.out.ConversationRepositoryPortOut;
import com.chat.cloudability.ibm.domain.port.out.EmbeddingPortOut;
import com.chat.cloudability.ibm.domain.port.out.KnowledgeRepositoryPortOut;
import com.chat.cloudability.ibm.domain.port.out.LanguageModelPortOut;
import com.chat.cloudability.ibm.domain.port.out.ModuleRepositoryPortOut;

/**
 * Interactor do fluxo de pergunta e resposta (RAG).
 *
 * <p>Netflix: <em>"Interactors are classes that orchestrate and perform domain
 * actions."</em> Aqui esta a sequencia inteira — identificar o modulo, vetorizar a
 * pergunta, buscar o contexto <em>dentro daquele modulo</em>, montar o prompt,
 * gerar a resposta e gravar a conversa — sem uma linha de HTTP, SQL ou SDK.
 */
public class AskQuestionInteractor implements AskQuestionPortIn {

    /**
     * Persona usada quando o modulo nao define a sua. O {@code %s} recebe o nome
     * do modulo.
     */
    static final String DEFAULT_PERSONA =
            "Voce e um tutor especialista no material do modulo \"%s\", ajudando a usuaria a estudar.";

    /**
     * Regras de comportamento do tutor, validas em qualquer modulo.
     *
     * <p>Isto e logica de negocio, nao configuracao de integracao: define o que
     * conta como uma boa resposta neste dominio. A persona vem do modulo; as regras
     * vem daqui e nao sao editaveis pela interface — inclusive a de isolamento, que
     * e o contrato do sistema com quem cria um modulo novo.
     */
    static final String GROUNDING_RULES = """
            Responda SEMPRE em portugues do Brasil, mantendo os termos tecnicos oficiais em
            ingles quando forem o padrao do assunto.

            REGRAS DE FUNDAMENTACAO — as mais importantes:
            1. Baseie a resposta no CONTEXTO fornecido abaixo. Ele vem exclusivamente do material
               do modulo "%s" — voce nao tem acesso ao material de nenhum outro modulo, e nao deve
               fingir que tem.
            2. Cite a fonte entre colchetes ao final das afirmacoes relevantes, no formato
               [nome-do-arquivo]. Use exatamente os nomes que aparecem no contexto.
            3. Se o contexto nao cobrir a pergunta, diga isso explicitamente. Voce pode
               complementar com conhecimento geral da area, mas deixe claro o que vem do
               material do modulo e o que e complemento seu.
            4. NUNCA afirme que um recurso "nao existe" apenas porque ele nao esta no material.
               Diga "isso nao aparece no material deste modulo" — as duas coisas sao diferentes.
            5. Quando estiver inferindo em vez de citar, sinalize isso na resposta.

            PARA QUESTOES DE MULTIPLA ESCOLHA:
            - Marque a alternativa correta com destaque.
            - Explique por que ela esta certa E por que cada uma das outras esta errada.
            - Aponte a pegadinha, quando houver.

            Seja direto e organizado. Use markdown (titulos, listas, tabelas) quando ajudar.
            """;

    /**
     * Bloco extra do modo economico.
     *
     * <p>Encurtar a resposta e a parte que mais economiza: na tabela de precos a
     * saida custa varias vezes a entrada. Reduzir o contexto ajuda, mas sozinho
     * renderia pouco.
     *
     * <p>Repare no que ele <strong>nao</strong> afrouxa: a citacao de fonte e o
     * limite do modulo continuam valendo. Economico e responder com menos
     * palavras, nao com menos rigor — se fosse para abrir mao disso, o modo seria
     * so uma forma cara de errar mais barato.
     */
    static final String ECONOMY_RULES = """

            MODO ECONOMICO — a usuaria pediu resposta enxuta:
            - Responda em ate 5 linhas, ou uma tabela curta quando comparar coisas.
            - Va direto ao ponto: sem introducao, sem recapitular a pergunta, sem fechamento.
            - Em multipla escolha, diga a alternativa correta e uma linha de justificativa.
              So explique as erradas se a usuaria pedir.
            - Continue citando a fonte entre colchetes e continue preso ao material do modulo.
              Brevidade nao afrouxa nenhuma das duas regras acima.
            - Se a resposta honesta nao couber no espaco, dê a versao curta e ofereca detalhar.
            """;

    private static final String DEFAULT_TITLE = "Nova conversa";

    /** Usada quando so vem imagem, sem texto — colar um print ja e a pergunta. */
    static final String DEFAULT_IMAGE_QUESTION =
            "Analise a imagem anexada e explique o que ela mostra.";

    private final LanguageModelPortOut languageModel;
    private final EmbeddingPortOut embeddings;
    private final KnowledgeRepositoryPortOut knowledge;
    private final ConversationRepositoryPortOut conversations;
    private final ModuleRepositoryPortOut modules;
    private final RagSettings settings;

    public AskQuestionInteractor(
            LanguageModelPortOut languageModel,
            EmbeddingPortOut embeddings,
            KnowledgeRepositoryPortOut knowledge,
            ConversationRepositoryPortOut conversations,
            ModuleRepositoryPortOut modules,
            RagSettings settings) {
        this.languageModel = languageModel;
        this.embeddings = embeddings;
        this.knowledge = knowledge;
        this.conversations = conversations;
        this.modules = modules;
        this.settings = settings;
    }

    @Override
    public void ask(Question question, AnswerListener listener) {
        if (question == null) {
            throw new IllegalArgumentException("A pergunta nao pode ser vazia");
        }
        boolean blankText = question.text() == null || question.text().isBlank();
        if (blankText && !question.hasImages()) {
            throw new IllegalArgumentException("A pergunta nao pode ser vazia");
        }
        if (question.images().size() > ImageAttachment.MAX_PER_QUESTION) {
            throw new IllegalArgumentException(
                    "No maximo %d imagens por pergunta".formatted(ImageAttachment.MAX_PER_QUESTION));
        }
        if (!languageModel.isConfigured()) {
            throw new IllegalStateException(
                    "ANTHROPIC_API_KEY nao configurada. Defina a variavel de ambiente e reinicie o backend.");
        }

        StudyModule module = Modules.require(modules, question.moduleSlug());

        // Um print sem texto ainda e uma pergunta: vale "olhe isto e me explique".
        String text = blankText ? DEFAULT_IMAGE_QUESTION : question.text().strip();

        long conversationId = question.conversationId() != null && question.conversationId() > 0
                ? question.conversationId()
                : conversations.create(module.id(), DEFAULT_TITLE);
        listener.onConversationStarted(conversationId);

        List<Message> history = conversations.messagesOf(conversationId);

        float[] queryVector = embeddings.embedQuery(text);
        List<RetrievedChunk> context = knowledge.search(
                queryVector, text, settings.topKFor(question.mode()), module.id());
        listener.onSources(context.stream().map(RetrievedChunk::toSourceRef).toList());

        String userMessage = buildContextBlock(context, module)
                + imageNotice(question.images())
                + "\n\n### Pergunta\n" + text;

        StringBuilder answer = new StringBuilder();
        languageModel.streamAnswer(systemPromptFor(module, question.mode()), history, userMessage,
                question.images(), question.mode(),
                token -> {
                    answer.append(token);
                    listener.onToken(token);
                });

        List<SourceRef> sources = distinctSources(context);
        // A imagem em si nao e guardada — so a marca de que existiu, para que os
        // turnos seguintes leiam a conversa sem lacuna.
        conversations.append(conversationId, Message.user(text + attachmentMarker(question.images())));
        conversations.append(conversationId, Message.assistant(answer.toString(), sources));
        conversations.renameIfUntitled(conversationId, deriveTitle(text));

        listener.onCompleted();
    }

    /**
     * Avisa o modelo de que veio print junto, e do que fazer com ele.
     *
     * <p>Sem isso, o modelo tende a responder so pelo material recuperado e ignorar
     * a imagem quando o texto da pergunta e curto ou generico.
     */
    static String imageNotice(List<ImageAttachment> images) {
        if (images.isEmpty()) {
            return "";
        }
        return """

                ### Imagens anexadas
                A usuaria anexou %d imagem(ns) a esta pergunta. Leia o conteudo delas e responda
                sobre o que aparece ali. Se a imagem trouxer questoes de prova, responda-as seguindo
                as regras de multipla escolha. Continue citando o material do modulo quando ele
                sustentar a resposta, e diga quando algo vem apenas da imagem.""".formatted(images.size());
    }

    /** Marca deixada no historico no lugar da imagem, que nao e persistida. */
    static String attachmentMarker(List<ImageAttachment> images) {
        if (images.isEmpty()) {
            return "";
        }
        return images.stream()
                .map(ImageAttachment::filename)
                .collect(Collectors.joining(", ", "\n\n[imagens anexadas: ", "]"));
    }

    /**
     * Prompt de sistema do modulo: a persona que o administrador escreveu, seguida
     * das regras que o dominio impoe a todos e, no modo economico, das regras de
     * brevidade.
     *
     * <p>Sao dois prompts estaveis, um por modo — e nao um prompt montado a cada
     * pergunta. Isso importa para o cache: cada modo mantem a propria entrada
     * quente, em vez de invalidar a cada turno.
     */
    static String systemPromptFor(StudyModule module, AnswerMode mode) {
        String persona = module.persona() == null || module.persona().isBlank()
                ? DEFAULT_PERSONA.formatted(module.name())
                : module.persona().strip();
        String prompt = persona + "\n\n" + GROUNDING_RULES.formatted(module.name());
        return mode == AnswerMode.ECONOMY ? prompt + ECONOMY_RULES : prompt;
    }

    /** Monta o bloco de contexto que precede a pergunta. */
    static String buildContextBlock(List<RetrievedChunk> chunks, StudyModule module) {
        if (chunks.isEmpty()) {
            return "### Contexto\n(Nenhum trecho relevante foi encontrado no material do modulo \""
                    + module.name() + "\".)";
        }
        StringBuilder sb = new StringBuilder("### Contexto do material do modulo \"")
                .append(module.name())
                .append("\"\n\n");
        for (RetrievedChunk chunk : chunks) {
            sb.append("---\n")
                    .append("Fonte: ").append(chunk.title())
                    .append("  |  Arquivo: ").append(chunk.source())
                    .append("  |  Nivel: ").append(chunk.level() == null ? "-" : chunk.level())
                    .append("\n\n")
                    .append(chunk.content())
                    .append("\n\n");
        }
        return sb.toString();
    }

    /** Titulo curto derivado da primeira pergunta da conversa. */
    static String deriveTitle(String question) {
        String clean = question.strip().replaceAll("\\s+", " ");
        return clean.length() <= 60 ? clean : clean.substring(0, 57) + "...";
    }

    /** Uma entrada por arquivo, preservando a ordem de relevancia. */
    static List<SourceRef> distinctSources(List<RetrievedChunk> chunks) {
        List<SourceRef> sources = new ArrayList<>();
        for (RetrievedChunk chunk : chunks) {
            boolean alreadyThere = sources.stream()
                    .anyMatch(existing -> existing.source().equals(chunk.source()));
            if (!alreadyThere) {
                sources.add(chunk.toSourceRef());
            }
        }
        return sources;
    }
}
