package com.chat.cloudability.ibm.adapter.in.web;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.chat.cloudability.ibm.adapter.in.web.dto.WebDtos;
import com.chat.cloudability.ibm.domain.model.Entities.ImageAttachment;
import com.chat.cloudability.ibm.domain.model.Entities.SourceRef;
import com.chat.cloudability.ibm.domain.model.Entities.StudyModule;
import com.chat.cloudability.ibm.domain.port.in.AskQuestionPortIn;
import com.chat.cloudability.ibm.domain.port.in.ManageConversationsPortIn;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.annotation.PreDestroy;

/**
 * Adaptador de entrada: HTTP.
 *
 * <p>Traduz requisicoes REST em chamadas as portas de entrada e devolve o
 * resultado como Server-Sent Events. Nao ha regra de negocio aqui — se amanha
 * este chat virar um bot de WhatsApp, escreve-se outro adaptador e o dominio
 * permanece intacto.
 *
 * <p>Toda rota carrega o modulo: conversas de modulos diferentes nao se veem.
 */
@RestController
@RequestMapping("/api")
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);
    private static final long SSE_TIMEOUT_MS = 10 * 60 * 1000L;

    private final AskQuestionPortIn askQuestion;
    private final ManageConversationsPortIn conversations;
    private final ObjectMapper mapper = new ObjectMapper();
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public ChatController(AskQuestionPortIn askQuestion, ManageConversationsPortIn conversations) {
        this.askQuestion = askQuestion;
        this.conversations = conversations;
    }

    @PreDestroy
    void shutdown() {
        executor.shutdown();
    }

    // ------------------------------------------------------------------ conversas

    @GetMapping("/conversations")
    public List<WebDtos.ConversationResponse> listConversations(
            @RequestParam(name = "module", defaultValue = StudyModule.DEFAULT_SLUG) String module) {
        return conversations.list(module).stream().map(WebDtos.ConversationResponse::from).toList();
    }

    @PostMapping("/conversations")
    public Map<String, Object> createConversation(
            @RequestParam(name = "module", defaultValue = StudyModule.DEFAULT_SLUG) String module) {
        return Map.of("id", conversations.create(module), "title", "Nova conversa");
    }

    @GetMapping("/conversations/{id}/messages")
    public List<WebDtos.MessageResponse> messages(@PathVariable long id) {
        return conversations.messagesOf(id).stream().map(WebDtos.MessageResponse::from).toList();
    }

    @DeleteMapping("/conversations/{id}")
    public Map<String, Object> deleteConversation(@PathVariable long id) {
        conversations.delete(id);
        return Map.of("deleted", true);
    }

    // ------------------------------------------------------------------ chat

    /**
     * Envia uma pergunta e devolve a resposta em streaming.
     *
     * <p>Eventos: {@code meta}, {@code sources}, {@code delta} (repetido),
     * {@code done} — ou {@code error}.
     */
    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chat(@RequestBody WebDtos.ChatRequest request) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);

        executor.submit(() -> {
            try {
                askQuestion.ask(
                        new AskQuestionPortIn.Question(
                                request.conversationId(), request.message(), request.module(),
                                toAttachments(request.images()), request.answerMode()),
                        new SseAnswerListener(emitter));
                emitter.complete();
            } catch (Exception e) {
                log.error("Falha ao responder: {}", e.getMessage(), e);
                sendError(emitter, rootMessage(e));
            }
        });

        return emitter;
    }

    /** Decodifica os prints do corpo da requisicao para entidades do dominio. */
    private static List<ImageAttachment> toAttachments(List<WebDtos.ChatImage> images) {
        return images == null
                ? List.of()
                : images.stream().map(WebDtos.ChatImage::toDomain).toList();
    }

    /** Traduz os eventos do dominio para o protocolo SSE. */
    private final class SseAnswerListener implements AskQuestionPortIn.AnswerListener {

        private final SseEmitter emitter;

        private SseAnswerListener(SseEmitter emitter) {
            this.emitter = emitter;
        }

        @Override
        public void onConversationStarted(long conversationId) {
            send("meta", Map.of("conversationId", conversationId));
        }

        @Override
        public void onSources(List<SourceRef> sources) {
            send("sources", sources.stream().map(WebDtos.SourceResponse::from).toList());
        }

        @Override
        public void onToken(String text) {
            send("delta", Map.of("text", text));
        }

        @Override
        public void onCompleted() {
            send("done", Map.of());
        }

        private void send(String event, Object payload) {
            try {
                emitter.send(SseEmitter.event().name(event).data(mapper.writeValueAsString(payload)));
            } catch (Exception e) {
                throw new IllegalStateException("Cliente desconectou", e);
            }
        }
    }

    private void sendError(SseEmitter emitter, String message) {
        try {
            emitter.send(SseEmitter.event().name("error")
                    .data(mapper.writeValueAsString(Map.of("message", message))));
            emitter.complete();
        } catch (Exception ignored) {
            emitter.completeWithError(new IllegalStateException(message));
        }
    }

    private static String rootMessage(Throwable e) {
        Throwable current = e;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current.getMessage() == null ? e.toString() : current.getMessage();
    }
}
