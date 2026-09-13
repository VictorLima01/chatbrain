package com.chat.cloudability.ibm.domain.port.in;

import java.util.List;

import com.chat.cloudability.ibm.domain.model.Entities.AnswerMode;
import com.chat.cloudability.ibm.domain.model.Entities.ImageAttachment;
import com.chat.cloudability.ibm.domain.model.Entities.SourceRef;

/**
 * Porta de entrada: fazer uma pergunta e receber a resposta em streaming.
 *
 * <p>Este e o contrato que o mundo externo usa para acionar o caso de uso. Quem
 * chama pode ser um controller REST, um consumidor de fila ou um teste — o
 * dominio nao sabe e nao se importa.
 */
public interface AskQuestionPortIn {

    /**
     * @param question o que perguntar, em qual conversa e em qual modulo
     * @param listener recebe os eventos conforme a resposta e produzida
     */
    void ask(Question question, AnswerListener listener);

    /**
     * Pergunta do usuario.
     *
     * @param conversationId nulo cria uma conversa nova
     * @param moduleSlug     modulo consultado; nulo cai no modulo padrao. A busca
     *                       nao atravessa essa fronteira
     * @param images         prints anexados a esta pergunta. Valem so para este
     *                       turno: nao entram na base de conhecimento nem ficam
     *                       guardados na conversa
     * @param mode           quanto gastar nesta resposta; nulo cai em
     *                       {@link AnswerMode#FULL}
     */
    record Question(Long conversationId, String text, String moduleSlug,
            List<ImageAttachment> images, AnswerMode mode) {

        public Question {
            images = images == null ? List.of() : List.copyOf(images);
            mode = mode == null ? AnswerMode.FULL : mode;
        }

        /** Pergunta so de texto, no modo completo. */
        public Question(Long conversationId, String text, String moduleSlug) {
            this(conversationId, text, moduleSlug, List.of(), AnswerMode.FULL);
        }

        /** Pergunta com prints, no modo completo. */
        public Question(Long conversationId, String text, String moduleSlug,
                List<ImageAttachment> images) {
            this(conversationId, text, moduleSlug, images, AnswerMode.FULL);
        }

        public boolean hasImages() {
            return !images.isEmpty();
        }
    }

    /**
     * Callback dos eventos da resposta.
     *
     * <p>Um callback, e nao um {@code Stream} ou {@code Flux}, para nao amarrar o
     * dominio a nenhuma biblioteca reativa: o adaptador HTTP traduz isso para SSE,
     * um teste apenas acumula os tokens numa lista.
     */
    interface AnswerListener {

        /** Disparado assim que a conversa e identificada ou criada. */
        void onConversationStarted(long conversationId);

        /** Fontes recuperadas da base, antes do texto comecar. */
        void onSources(List<SourceRef> sources);

        /** Cada pedaco de texto gerado pelo modelo. */
        void onToken(String text);

        /** Resposta concluida e persistida. */
        void onCompleted();
    }
}
