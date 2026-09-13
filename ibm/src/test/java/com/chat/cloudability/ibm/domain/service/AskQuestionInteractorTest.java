package com.chat.cloudability.ibm.domain.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;

import com.chat.cloudability.ibm.domain.model.Entities.AnswerMode;
import com.chat.cloudability.ibm.domain.model.Entities.ConversationSummary;
import com.chat.cloudability.ibm.domain.model.Entities.DocumentSummary;
import com.chat.cloudability.ibm.domain.model.Entities.ImageAttachment;
import com.chat.cloudability.ibm.domain.model.Entities.KnowledgeStats;
import com.chat.cloudability.ibm.domain.model.Entities.Message;
import com.chat.cloudability.ibm.domain.model.Entities.NewDocument;
import com.chat.cloudability.ibm.domain.model.Entities.NewModule;
import com.chat.cloudability.ibm.domain.model.Entities.RagSettings;
import com.chat.cloudability.ibm.domain.model.Entities.RetrievedChunk;
import com.chat.cloudability.ibm.domain.model.Entities.Role;
import com.chat.cloudability.ibm.domain.model.Entities.SourceRef;
import com.chat.cloudability.ibm.domain.model.Entities.StudyModule;
import com.chat.cloudability.ibm.domain.port.in.AskQuestionPortIn;
import com.chat.cloudability.ibm.domain.port.out.ConversationRepositoryPortOut;
import com.chat.cloudability.ibm.domain.port.out.EmbeddingPortOut;
import com.chat.cloudability.ibm.domain.port.out.KnowledgeRepositoryPortOut;
import com.chat.cloudability.ibm.domain.port.out.LanguageModelPortOut;
import com.chat.cloudability.ibm.domain.port.out.ModuleRepositoryPortOut;

/**
 * O fluxo completo de RAG testado sem Spring, sem Postgres e sem chamar a API.
 *
 * <p>E o ganho concreto da arquitetura hexagonal: como o interactor so conhece
 * interfaces, trocamos cada porta de saida por uma implementacao em memoria e
 * exercitamos a regra de negocio de ponta a ponta em milissegundos.
 */
class AskQuestionInteractorTest {

    private static final StudyModule CLOUDABILITY = new StudyModule(
            1L, "cloudability", "IBM Cloudability", "material oficial",
            "Voce e um tutor de Cloudability.", true, 0, 0, "2026-01-01T00:00:00Z");

    private static final StudyModule KUBERNETES = new StudyModule(
            2L, "kubernetes", "Kubernetes", null, null, false, 0, 0, "2026-01-01T00:00:00Z");

    private final FakeLanguageModel model = new FakeLanguageModel();
    private final FakeKnowledge knowledge = new FakeKnowledge();
    private final FakeConversations conversations = new FakeConversations();
    private final FakeModules modules = new FakeModules();

    private AskQuestionInteractor interactor() {
        return new AskQuestionInteractor(
                model,
                new FakeEmbeddings(),
                knowledge,
                conversations,
                modules,
                new RagSettings(1200, 200, 8, 3));
    }

    @Test
    void produzOsEventosNaOrdemCertaEDevolveOTexto() {
        knowledge.chunks = List.of(chunk("Idle e CPU abaixo de 2%.", "modules/cloudability/L4/rigthsizing.vtt"));
        model.reply = List.of("Idle ", "e CPU ", "<= 2%.");

        RecordingListener listener = new RecordingListener();
        interactor().ask(question("O que e idle?"), listener);

        assertEquals(List.of("conversationStarted", "sources", "token", "token", "token", "completed"),
                listener.events);
        assertEquals("Idle e CPU <= 2%.", listener.text.toString());
    }

    @Test
    void oContextoRecuperadoVaiParaOPromptComAFonte() {
        knowledge.chunks = List.of(chunk("O lookback e de 10 ou 30 dias.", "modules/cloudability/L4/rigthsizing.vtt"));

        interactor().ask(question("Qual o lookback?"), new RecordingListener());

        assertTrue(model.lastUserMessage.contains("O lookback e de 10 ou 30 dias."),
                "o trecho recuperado deve estar no prompt");
        assertTrue(model.lastUserMessage.contains("modules/cloudability/L4/rigthsizing.vtt"),
                "a fonte deve acompanhar o trecho, para o modelo poder cita-la");
        assertTrue(model.lastUserMessage.contains("Qual o lookback?"));
    }

    @Test
    void gravaPerguntaERespostaEBatizaAConversa() {
        knowledge.chunks = List.of(chunk("qualquer coisa", "modules/cloudability/L1/a.vtt"));
        model.reply = List.of("resposta final");

        interactor().ask(question("Uma pergunta qualquer"), new RecordingListener());

        assertEquals(2, conversations.saved.size());
        assertEquals(Role.USER, conversations.saved.get(0).role());
        assertEquals("Uma pergunta qualquer", conversations.saved.get(0).content());
        assertEquals(Role.ASSISTANT, conversations.saved.get(1).role());
        assertEquals("resposta final", conversations.saved.get(1).content());
        assertEquals("Uma pergunta qualquer", conversations.renamedTo);
    }

    @Test
    void semChaveConfiguradaFalhaAntesDeChamarOModelo() {
        model.configured = false;

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> interactor().ask(question("oi"), new RecordingListener()));

        assertTrue(error.getMessage().contains("ANTHROPIC_API_KEY"));
        assertTrue(conversations.saved.isEmpty(), "nao deve gravar nada se nem tentou responder");
    }

    @Test
    void perguntaVaziaERejeitada() {
        assertThrows(IllegalArgumentException.class,
                () -> interactor().ask(question("   "), new RecordingListener()));
    }

    @Test
    void semContextoRecuperadoAindaResponde() {
        knowledge.chunks = List.of();
        model.reply = List.of("nao encontrei isso no material");

        RecordingListener listener = new RecordingListener();
        interactor().ask(question("pergunta fora do material"), listener);

        assertTrue(model.lastUserMessage.contains("Nenhum trecho relevante"));
        assertEquals("nao encontrei isso no material", listener.text.toString());
    }

    @Test
    void fontesRepetidasAparecemUmaVezSo() {
        knowledge.chunks = List.of(
                chunk("trecho 1", "modules/cloudability/L4/a.vtt"),
                chunk("trecho 2", "modules/cloudability/L4/a.vtt"),
                chunk("trecho 3", "modules/cloudability/L4/b.pdf"));

        RecordingListener listener = new RecordingListener();
        interactor().ask(question("pergunta"), listener);

        assertEquals(3, listener.sources.size(), "todos os trechos sao informados ao cliente");
        assertEquals(2, conversations.saved.get(1).sources().size(),
                "mas a mensagem guarda uma entrada por arquivo");
    }

    // --------------------------------------------------------------- isolamento

    @Test
    void aBuscaEsempreLimitadaAoModuloDaPergunta() {
        interactor().ask(new AskQuestionPortIn.Question(null, "pergunta", "kubernetes"),
                new RecordingListener());

        assertEquals(KUBERNETES.id(), knowledge.searchedModuleId,
                "o id do modulo precisa chegar ao repositorio — e ele que impede o vazamento");
        assertEquals(KUBERNETES.id(), conversations.createdInModuleId,
                "a conversa nasce dentro do modulo em que foi feita");
    }

    @Test
    void moduloDesconhecidoFalhaEmVezDeCairNoPadrao() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> interactor().ask(
                        new AskQuestionPortIn.Question(null, "pergunta", "inexistente"),
                        new RecordingListener()));

        assertTrue(error.getMessage().contains("inexistente"));
        assertTrue(conversations.saved.isEmpty(),
                "responder com o material do modulo errado seria pior do que nao responder");
    }

    @Test
    void semModuloInformadoUsaOPadrao() {
        interactor().ask(new AskQuestionPortIn.Question(null, "pergunta", null),
                new RecordingListener());

        assertEquals(CLOUDABILITY.id(), knowledge.searchedModuleId);
    }

    // --------------------------------------------------------- modo de resposta

    @Test
    void oModoPadraoEOCompleto() {
        interactor().ask(question("pergunta"), new RecordingListener());

        assertEquals(AnswerMode.FULL, model.lastMode);
        assertEquals(8, knowledge.searchedTopK);
        assertFalse(model.lastSystemPrompt.contains("MODO ECONOMICO"));
    }

    @Test
    void oModoEconomicoEncurtaContextoEResposta() {
        interactor().ask(new AskQuestionPortIn.Question(
                null, "pergunta", "cloudability", List.of(), AnswerMode.ECONOMY),
                new RecordingListener());

        assertEquals(AnswerMode.ECONOMY, model.lastMode,
                "o adaptador precisa do modo para baixar o effort");
        assertEquals(3, knowledge.searchedTopK, "menos trechos recuperados");
        assertTrue(model.lastSystemPrompt.contains("MODO ECONOMICO"));
    }

    @Test
    void oModoEconomicoNaoAfrouxaCitacaoNemIsolamento() {
        String prompt = AskQuestionInteractor.systemPromptFor(CLOUDABILITY, AnswerMode.ECONOMY);

        assertTrue(prompt.contains("REGRAS DE FUNDAMENTACAO"),
                "economizar token nao pode custar rigor");
        assertTrue(prompt.contains("nao tem acesso ao material de nenhum outro modulo"));
        assertTrue(prompt.contains("Continue citando a fonte"));
    }

    @Test
    void modoDesconhecidoCaiNoCompleto() {
        // Um cliente desatualizado deve receber a resposta inteira, nao um erro.
        assertEquals(AnswerMode.FULL, AnswerMode.of("turbo"));
        assertEquals(AnswerMode.FULL, AnswerMode.of(null));
        assertEquals(AnswerMode.ECONOMY, AnswerMode.of("economico"));
    }

    @Test
    void oPromptCarregaAPersonaDoModuloEAsRegrasDoDominio() {
        interactor().ask(question("pergunta"), new RecordingListener());

        assertTrue(model.lastSystemPrompt.startsWith("Voce e um tutor de Cloudability."),
                "a persona do modulo abre o prompt");
        assertTrue(model.lastSystemPrompt.contains("REGRAS DE FUNDAMENTACAO"),
                "as regras vem do dominio e nao sao editaveis pelo administrador");
        assertTrue(model.lastSystemPrompt.contains("nao tem acesso ao material de nenhum outro modulo"));
    }

    @Test
    void moduloSemPersonaGanhaUmaPadraoComOSeuNome() {
        String prompt = AskQuestionInteractor.systemPromptFor(KUBERNETES, AnswerMode.FULL);

        assertTrue(prompt.contains("tutor especialista no material do modulo \"Kubernetes\""));
        assertFalse(prompt.contains("Cloudability"));
    }

    // ------------------------------------------------------------ prints anexados

    @Test
    void oPrintVaiParaOModeloJuntoDaPergunta() {
        ImageAttachment print = print("questao.png");

        interactor().ask(new AskQuestionPortIn.Question(
                null, "Qual a alternativa certa?", "cloudability", List.of(print)),
                new RecordingListener());

        assertEquals(List.of(print), model.lastImages);
        assertTrue(model.lastUserMessage.contains("Imagens anexadas"),
                "o modelo precisa saber que veio print, senao responde so pelo material");
    }

    @Test
    void oPrintNaoEGuardadoNaConversa() {
        interactor().ask(new AskQuestionPortIn.Question(
                null, "Qual a alternativa certa?", "cloudability", List.of(print("questao.png"))),
                new RecordingListener());

        String gravado = conversations.saved.get(0).content();
        assertTrue(gravado.contains("[imagens anexadas: questao.png]"),
                "fica a marca de que existiu, para o historico nao ter lacuna");
        assertFalse(gravado.contains("base64"), "os bytes da imagem nao vao para o banco");
    }

    @Test
    void printSemTextoAindaEUmaPergunta() {
        interactor().ask(new AskQuestionPortIn.Question(
                null, "  ", "cloudability", List.of(print("tela.png"))),
                new RecordingListener());

        assertTrue(model.lastUserMessage.contains(AskQuestionInteractor.DEFAULT_IMAGE_QUESTION));
        assertEquals(1, model.lastImages.size());
    }

    @Test
    void perguntaSemTextoESemPrintContinuaRejeitada() {
        assertThrows(IllegalArgumentException.class,
                () -> interactor().ask(new AskQuestionPortIn.Question(null, "  ", "cloudability"),
                        new RecordingListener()));
    }

    @Test
    void acimaDoTetoDePrintsERejeitado() {
        List<ImageAttachment> muitos = List.of(print("a.png"), print("b.png"), print("c.png"),
                print("d.png"), print("e.png"));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> interactor().ask(
                        new AskQuestionPortIn.Question(null, "ola", "cloudability", muitos),
                        new RecordingListener()));

        assertTrue(error.getMessage().contains("4"));
        assertTrue(conversations.saved.isEmpty());
    }

    @Test
    void formatoNaoSuportadoERecusadoNaEntidade() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new ImageAttachment("doc.bmp", "image/bmp", new byte[] {1, 2, 3}));

        assertTrue(error.getMessage().contains("image/bmp"));
    }

    @Test
    void imagemAcimaDoTamanhoERecusadaNaEntidade() {
        byte[] gigante = new byte[ImageAttachment.MAX_BYTES + 1];

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new ImageAttachment("enorme.png", "image/png", gigante));

        assertTrue(error.getMessage().contains("limite"));
    }

    private static ImageAttachment print(String filename) {
        return new ImageAttachment(filename, "image/png", new byte[] {1, 2, 3, 4});
    }

    private static AskQuestionPortIn.Question question(String text) {
        return new AskQuestionPortIn.Question(null, text, "cloudability");
    }

    private static RetrievedChunk chunk(String content, String source) {
        return new RetrievedChunk(1, content, 0, 0.9, "titulo", source, "L4", "vtt");
    }

    // ------------------------------------------------------------ dubles das portas

    private static final class FakeLanguageModel implements LanguageModelPortOut {
        boolean configured = true;
        List<String> reply = List.of("ok");
        String lastUserMessage;
        String lastSystemPrompt;
        List<ImageAttachment> lastImages = List.of();
        AnswerMode lastMode;

        @Override
        public void streamAnswer(String systemPrompt, List<Message> history, String userMessage,
                List<ImageAttachment> images, AnswerMode mode, Consumer<String> onToken) {
            this.lastSystemPrompt = systemPrompt;
            this.lastUserMessage = userMessage;
            this.lastImages = images;
            this.lastMode = mode;
            reply.forEach(onToken);
        }

        @Override
        public boolean isConfigured() {
            return configured;
        }

        @Override
        public String modelName() {
            return "fake-model";
        }
    }

    private static final class FakeEmbeddings implements EmbeddingPortOut {
        @Override
        public float[] embedQuery(String text) {
            return new float[] {1, 0, 0};
        }

        @Override
        public List<float[]> embedDocuments(List<String> texts) {
            return texts.stream().map(t -> new float[] {1, 0, 0}).toList();
        }

        @Override
        public int dimensions() {
            return 3;
        }
    }

    private static final class FakeModules implements ModuleRepositoryPortOut {
        @Override
        public List<StudyModule> list() {
            return List.of(CLOUDABILITY, KUBERNETES);
        }

        @Override
        public Optional<StudyModule> findBySlug(String slug) {
            return list().stream().filter(m -> m.slug().equals(slug)).findFirst();
        }

        @Override
        public long create(NewModule module) {
            return 99L;
        }

        @Override
        public void update(long moduleId, NewModule module) {
        }

        @Override
        public void delete(long moduleId) {
        }
    }

    private static final class FakeKnowledge implements KnowledgeRepositoryPortOut {
        List<RetrievedChunk> chunks = List.of();
        Long searchedModuleId;
        Integer searchedTopK;

        @Override
        public List<RetrievedChunk> search(float[] queryEmbedding, String queryText, int topK,
                long moduleId) {
            this.searchedModuleId = moduleId;
            this.searchedTopK = topK;
            return chunks;
        }

        @Override
        public Optional<String> checksumOf(String source) {
            return Optional.empty();
        }

        @Override
        public long saveDocument(NewDocument document) {
            return 1L;
        }

        @Override
        public void saveChunks(long documentId, List<String> contents, List<float[]> embeddings) {
        }

        @Override
        public List<DocumentSummary> listDocuments(long moduleId) {
            return List.of();
        }

        @Override
        public void deleteDocument(long documentId) {
        }

        @Override
        public KnowledgeStats stats(long moduleId) {
            return new KnowledgeStats(0, 0);
        }

        @Override
        public KnowledgeStats totalStats() {
            return new KnowledgeStats(0, 0);
        }
    }

    private static final class FakeConversations implements ConversationRepositoryPortOut {
        final List<Message> saved = new ArrayList<>();
        String renamedTo;
        Long createdInModuleId;

        @Override
        public long create(long moduleId, String title) {
            this.createdInModuleId = moduleId;
            return 42L;
        }

        @Override
        public List<ConversationSummary> list(long moduleId) {
            return List.of();
        }

        @Override
        public List<Message> messagesOf(long conversationId) {
            return List.of();
        }

        @Override
        public void append(long conversationId, Message message) {
            saved.add(message);
        }

        @Override
        public void renameIfUntitled(long conversationId, String newTitle) {
            renamedTo = newTitle;
        }

        @Override
        public void delete(long conversationId) {
        }
    }

    private static final class RecordingListener implements AskQuestionPortIn.AnswerListener {
        final List<String> events = new ArrayList<>();
        final StringBuilder text = new StringBuilder();
        List<SourceRef> sources = List.of();

        @Override
        public void onConversationStarted(long conversationId) {
            events.add("conversationStarted");
        }

        @Override
        public void onSources(List<SourceRef> refs) {
            events.add("sources");
            this.sources = refs;
        }

        @Override
        public void onToken(String token) {
            events.add("token");
            text.append(token);
        }

        @Override
        public void onCompleted() {
            events.add("completed");
        }
    }
}
