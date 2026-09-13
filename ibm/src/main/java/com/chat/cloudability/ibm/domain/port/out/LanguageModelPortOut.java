package com.chat.cloudability.ibm.domain.port.out;

import java.util.List;
import java.util.function.Consumer;

import com.chat.cloudability.ibm.domain.model.Entities.AnswerMode;
import com.chat.cloudability.ibm.domain.model.Entities.ImageAttachment;
import com.chat.cloudability.ibm.domain.model.Entities.Message;

/**
 * Porta de saida: geracao de texto pelo modelo de linguagem.
 *
 * <p>O <em>prompt de sistema</em> e passado pelo dominio, e nao definido aqui: as
 * regras de como o tutor deve responder (citar fonte, nao afirmar que algo "nao
 * existe") sao logica de negocio, nao detalhe de integracao.
 */
public interface LanguageModelPortOut {

    /**
     * Gera a resposta e entrega cada pedaco de texto ao consumidor.
     *
     * @param systemPrompt instrucoes de comportamento, definidas pelo dominio
     * @param history      mensagens anteriores da conversa
     * @param userMessage  a mensagem atual, ja com o contexto recuperado embutido
     * @param images       prints anexados a esta pergunta; lista vazia quando nao
     *                     ha nenhum. Um provedor sem visao pode ignora-los, mas
     *                     deve dizer isso na resposta em vez de fingir que viu
     * @param mode         o quanto o dominio autoriza gastar nesta resposta. O
     *                     dominio diz a intencao; como isso vira parametro do
     *                     provedor e assunto do adaptador
     * @param onToken      recebe cada fragmento conforme e gerado
     */
    void streamAnswer(String systemPrompt, List<Message> history, String userMessage,
            List<ImageAttachment> images, AnswerMode mode, Consumer<String> onToken);

    /** Se falso, o caso de uso falha rapido com mensagem clara em vez de tentar a chamada. */
    boolean isConfigured();

    /** Nome do modelo em uso, exibido na tela "Sobre". */
    String modelName();
}
