package com.chat.cloudability.ibm.domain.model;

import java.util.List;

/**
 * Entidades do dominio.
 *
 * <p>Na definicao da Netflix, <em>"Entities are domain objects (e.g., a Movie or
 * a Shooting Location) — they have no knowledge of where they're stored"</em>.
 * Nenhum tipo aqui sabe se veio do Postgres, de um bucket S3 ou de uma chamada
 * HTTP: sao apenas os conceitos do problema.
 *
 * <p>Agrupados em uma classe-container para manter o pacote enxuto — cada um e
 * um {@code record} imutavel e independente.
 */
public final class Entities {

    private Entities() {
    }

    // ------------------------------------------------------------------ alcadas

    /**
     * Alcada de quem esta usando o sistema.
     *
     * <p>{@code STUDENT} consulta: conversa com os modulos e enxerga a base.
     * {@code ADMIN} tambem administra: cria modulos e envia o material que os
     * alimenta. A autenticacao ainda nao existe — quem resolve a alcada de uma
     * requisicao e o adaptador de entrada, e esse e o unico ponto a trocar
     * quando o login entrar.
     */
    public enum AccessLevel {
        ADMIN,
        STUDENT;

        public static AccessLevel of(String value) {
            return "admin".equalsIgnoreCase(value) ? ADMIN : STUDENT;
        }

        public String wireValue() {
            return name().toLowerCase();
        }

        public boolean isAdmin() {
            return this == ADMIN;
        }
    }

    // --------------------------------------------------------- modo de resposta

    /**
     * Quanto esforco (e quanto token) gastar numa resposta.
     *
     * <p>E uma decisao de negocio, nao de integracao: define que tipo de resposta
     * conta como boa agora. Estudando um tema novo vale a explicacao longa com
     * contexto largo; conferindo um detalhe, uma frase direta resolve — e custa
     * uma fracao.
     *
     * <p>Cada modo mexe em tres coisas ao mesmo tempo: quantos trechos entram no
     * prompt, quanto o modelo pode raciocinar e quao longa deve ser a resposta.
     * Mexer numa so nao economiza muito — e o conjunto que faz diferenca.
     */
    public enum AnswerMode {
        /** Padrao: resposta explicada, contexto largo. */
        FULL,
        /** Economico: resposta direta, menos contexto, menos raciocinio. */
        ECONOMY;

        public static AnswerMode of(String value) {
            return "economy".equalsIgnoreCase(value) || "economico".equalsIgnoreCase(value)
                    ? ECONOMY
                    : FULL;
        }

        public String wireValue() {
            return name().toLowerCase();
        }

        public boolean isEconomy() {
            return this == ECONOMY;
        }
    }

    // ------------------------------------------------------------------ modulos

    /**
     * Um modulo de estudo: um recorte fechado de material.
     *
     * <p>E a fronteira de isolamento do sistema. Uma pergunta feita no modulo X
     * so recupera trechos de documentos do modulo X — o material de Cloudability
     * nao vaza para um modulo novo, e vice-versa.
     *
     * @param persona    instrucao de papel usada no prompt; as regras de
     *                   fundamentacao sao do dominio e vem sempre junto
     * @param builtIn    modulo de fabrica, que a interface nao deixa remover
     */
    public record StudyModule(
            long id,
            String slug,
            String name,
            String description,
            String persona,
            boolean builtIn,
            int documentCount,
            int chunkCount,
            String createdAt) {

        /** Modulo assumido quando a requisicao nao diz qual. */
        public static final String DEFAULT_SLUG = "cloudability";
    }

    /** Modulo a criar ou atualizar, antes de existir no repositorio. */
    public record NewModule(String slug, String name, String description, String persona) {
    }

    // ------------------------------------------------------------------ conversa

    public enum Role {
        USER,
        ASSISTANT;

        public static Role of(String value) {
            return "assistant".equalsIgnoreCase(value) ? ASSISTANT : USER;
        }

        public String wireValue() {
            return name().toLowerCase();
        }
    }

    /** Uma mensagem trocada no chat. */
    public record Message(Role role, String content, List<SourceRef> sources) {
        public static Message user(String content) {
            return new Message(Role.USER, content, List.of());
        }

        public static Message assistant(String content, List<SourceRef> sources) {
            return new Message(Role.ASSISTANT, content, sources);
        }
    }

    /**
     * Imagem anexada a uma pergunta — tipicamente um print de tela.
     *
     * <p>Vive so pelo tempo da pergunta: vai para o modelo junto da mensagem e
     * <strong>nao</strong> e guardada nem no banco nem no armazenamento de
     * material. Anexar um print e tirar uma duvida; mandar material para a base e
     * uma operacao administrativa, com outro caminho e outra intencao.
     */
    public record ImageAttachment(String filename, String mediaType, byte[] data) {

        /** Formatos que a visao do Claude entende. */
        public static final List<String> SUPPORTED_MEDIA_TYPES =
                List.of("image/png", "image/jpeg", "image/gif", "image/webp");

        /**
         * Teto por imagem, em bytes.
         *
         * <p>A API aceita 10 MB <em>ja em base64</em>, e a codificacao infla o
         * tamanho em cerca de um terco. 5 MB de bytes crus viram ~6,7 MB
         * codificados e ficam com folga abaixo do limite.
         */
        public static final int MAX_BYTES = 5 * 1024 * 1024;

        /** Teto de imagens por pergunta. */
        public static final int MAX_PER_QUESTION = 4;

        public ImageAttachment {
            if (data == null || data.length == 0) {
                throw new IllegalArgumentException("Imagem vazia: " + filename);
            }
            if (data.length > MAX_BYTES) {
                throw new IllegalArgumentException(
                        "Imagem %s tem %d MB; o limite e %d MB"
                                .formatted(filename, data.length / (1024 * 1024),
                                        MAX_BYTES / (1024 * 1024)));
            }
            if (mediaType == null || !SUPPORTED_MEDIA_TYPES.contains(mediaType.toLowerCase())) {
                throw new IllegalArgumentException(
                        "Formato nao suportado em %s: %s. Aceitos: %s"
                                .formatted(filename, mediaType,
                                        String.join(", ", SUPPORTED_MEDIA_TYPES)));
            }
        }
    }

    /** Resumo de uma conversa, para a listagem lateral. */
    public record ConversationSummary(long id, String title, String updatedAt, int messageCount) {
    }

    // ------------------------------------------------------- base de conhecimento

    /** Referencia a um documento usado como fonte de uma resposta. */
    public record SourceRef(String title, String source, String level, double score) {
    }

    /** Trecho recuperado da base durante a busca. */
    public record RetrievedChunk(
            long id,
            String content,
            int ordinal,
            double score,
            String title,
            String source,
            String level,
            String kind) {

        public SourceRef toSourceRef() {
            return new SourceRef(title, source, level == null ? "" : level, score);
        }
    }

    /** Documento indexado, como exibido na tela de gestao da base. */
    public record DocumentSummary(
            long id,
            String title,
            String source,
            String kind,
            String level,
            int charCount,
            int chunkCount,
            String createdAt) {
    }

    /** Documento pronto para ser persistido, apos extracao do texto. */
    public record NewDocument(
            long moduleId,
            String title,
            String source,
            String kind,
            String level,
            String checksum,
            String text) {
    }

    /** Contagens gerais da base. */
    public record KnowledgeStats(int documents, int chunks) {
    }

    // ------------------------------------------------------------------ ingestao

    /**
     * Ponteiro para um arquivo do material, sem carregar o conteudo.
     *
     * @param location endereco no armazenamento (chave S3 ou caminho em disco);
     *                 e unico no sistema todo e vira o {@code source} do documento
     * @param path     caminho dentro do modulo, usado para classificar o nivel
     */
    public record MaterialRef(String location, String path, String filename) {
    }

    /** Resultado de uma rodada de ingestao. */
    public record IngestReport(
            int indexed,
            int skipped,
            int failed,
            int totalDocuments,
            int totalChunks,
            List<String> errors) {
    }

    // ------------------------------------------------------------------ ajustes

    /**
     * Parametros de recuperacao e fatiamento. O dominio define o que precisa;
     * a camada de configuracao decide de onde os valores vem.
     *
     * @param economyTopK quantos trechos o modo economico recupera. Menos contexto
     *                    e a metade da economia; a outra metade e a resposta curta
     */
    public record RagSettings(int chunkSize, int chunkOverlap, int topK, int economyTopK) {

        public int topKFor(AnswerMode mode) {
            return mode == AnswerMode.ECONOMY ? economyTopK : topK;
        }
    }
}
