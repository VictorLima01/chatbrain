package com.chat.cloudability.ibm.adapter.in.web.dto;

import java.util.Base64;
import java.util.List;

import com.chat.cloudability.ibm.domain.model.Entities.AnswerMode;
import com.chat.cloudability.ibm.domain.model.Entities.ConversationSummary;
import com.chat.cloudability.ibm.domain.model.Entities.DocumentSummary;
import com.chat.cloudability.ibm.domain.model.Entities.ImageAttachment;
import com.chat.cloudability.ibm.domain.model.Entities.Message;
import com.chat.cloudability.ibm.domain.model.Entities.SourceRef;
import com.chat.cloudability.ibm.domain.model.Entities.StudyModule;

/**
 * Contratos HTTP.
 *
 * <p>Ficam separados das entidades de proposito: o formato que a interface web
 * consome pode mudar (renomear um campo, achatar uma estrutura) sem obrigar o
 * dominio a mudar junto.
 */
public final class WebDtos {

    private WebDtos() {
    }

    /**
     * @param mode {@code "full"} (padrao) ou {@code "economy"} — aceita tambem
     *             {@code "economico"}. Valor irreconhecivel cai no padrao: um
     *             cliente desatualizado deve receber a resposta completa, nao um
     *             erro
     */
    public record ChatRequest(Long conversationId, String message, String module,
            List<ChatImage> images, String mode) {

        public AnswerMode answerMode() {
            return AnswerMode.of(mode);
        }
    }

    /**
     * Print anexado a uma pergunta.
     *
     * @param data conteudo em base64. Aceita tambem o formato de data URL que o
     *             {@code FileReader} do navegador produz
     *             ({@code data:image/png;base64,...}) — o prefixo e descartado
     */
    public record ChatImage(String filename, String mediaType, String data) {

        private static final String BASE64_MARKER = ";base64,";

        public ImageAttachment toDomain() {
            if (data == null || data.isBlank()) {
                throw new IllegalArgumentException("Imagem sem conteudo: " + filename);
            }
            int marker = data.indexOf(BASE64_MARKER);
            String payload = marker < 0 ? data : data.substring(marker + BASE64_MARKER.length());

            byte[] bytes;
            try {
                bytes = Base64.getDecoder().decode(payload.replaceAll("\\s", ""));
            } catch (IllegalArgumentException e) {
                // O detalhe entra na mensagem em vez de virar causa encadeada: o
                // controller reporta a causa mais funda, e "Illegal base64
                // character 2d" sozinho nao diz de qual imagem se trata.
                throw new IllegalArgumentException(
                        "Imagem %s nao esta em base64 valido (%s)".formatted(filename, e.getMessage()));
            }
            // O construtor de ImageAttachment valida formato e tamanho: as regras
            // de o que e um anexo aceitavel sao do dominio, nao deste DTO.
            return new ImageAttachment(
                    filename == null || filename.isBlank() ? "print.png" : filename,
                    mediaType == null ? mediaTypeFrom(data) : mediaType.toLowerCase(),
                    bytes);
        }

        /** Deduz o tipo do proprio data URL quando o cliente nao manda o campo. */
        private static String mediaTypeFrom(String dataUrl) {
            if (dataUrl.startsWith("data:")) {
                int end = dataUrl.indexOf(BASE64_MARKER);
                if (end > 5) {
                    return dataUrl.substring(5, end).toLowerCase();
                }
            }
            return "image/png";
        }
    }

    public record TextIngestRequest(String title, String content, String level) {
    }

    /** Criacao e edicao de modulo. O slug e opcional: derivado do nome quando falta. */
    public record ModuleRequest(String slug, String name, String description, String persona) {
    }

    public record ModuleResponse(
            String slug, String name, String description, String persona,
            boolean builtIn, int documentCount, int chunkCount, String createdAt) {

        public static ModuleResponse from(StudyModule module) {
            return new ModuleResponse(module.slug(), module.name(), module.description(),
                    module.persona(), module.builtIn(), module.documentCount(),
                    module.chunkCount(), module.createdAt());
        }
    }

    public record ConversationResponse(long id, String title, String updatedAt, int messageCount) {
        public static ConversationResponse from(ConversationSummary summary) {
            return new ConversationResponse(
                    summary.id(), summary.title(), summary.updatedAt(), summary.messageCount());
        }
    }

    public record SourceResponse(String title, String source, String level, double score) {
        public static SourceResponse from(SourceRef ref) {
            return new SourceResponse(ref.title(), ref.source(), ref.level(), ref.score());
        }
    }

    public record MessageResponse(String role, String content, List<SourceResponse> sources) {
        public static MessageResponse from(Message message) {
            return new MessageResponse(
                    message.role().wireValue(),
                    message.content(),
                    message.sources().stream().map(SourceResponse::from).toList());
        }
    }

    public record DocumentResponse(
            long id, String title, String source, String kind,
            String level, int charCount, int chunkCount, String createdAt) {

        public static DocumentResponse from(DocumentSummary doc) {
            return new DocumentResponse(doc.id(), doc.title(), doc.source(), doc.kind(),
                    doc.level(), doc.charCount(), doc.chunkCount(), doc.createdAt());
        }
    }
}
