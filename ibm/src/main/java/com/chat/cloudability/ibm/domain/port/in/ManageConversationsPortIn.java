package com.chat.cloudability.ibm.domain.port.in;

import java.util.List;

import com.chat.cloudability.ibm.domain.model.Entities.ConversationSummary;
import com.chat.cloudability.ibm.domain.model.Entities.Message;

/** Porta de entrada: gerenciar o historico de conversas de um modulo. */
public interface ManageConversationsPortIn {

    List<ConversationSummary> list(String moduleSlug);

    long create(String moduleSlug);

    List<Message> messagesOf(long conversationId);

    void delete(long conversationId);
}
