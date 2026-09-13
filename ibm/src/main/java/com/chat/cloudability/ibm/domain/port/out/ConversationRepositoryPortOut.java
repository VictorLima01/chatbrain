package com.chat.cloudability.ibm.domain.port.out;

import java.util.List;

import com.chat.cloudability.ibm.domain.model.Entities.ConversationSummary;
import com.chat.cloudability.ibm.domain.model.Entities.Message;

/** Porta de saida: persistencia das conversas e mensagens. */
public interface ConversationRepositoryPortOut {

    long create(long moduleId, String title);

    /** Conversas de um modulo — o historico tambem nao se mistura entre modulos. */
    List<ConversationSummary> list(long moduleId);

    List<Message> messagesOf(long conversationId);

    void append(long conversationId, Message message);

    /** Renomeia apenas se a conversa ainda estiver com o titulo padrao. */
    void renameIfUntitled(long conversationId, String newTitle);

    void delete(long conversationId);
}
