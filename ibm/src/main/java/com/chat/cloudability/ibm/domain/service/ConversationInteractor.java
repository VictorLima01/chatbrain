package com.chat.cloudability.ibm.domain.service;

import java.util.List;

import com.chat.cloudability.ibm.domain.model.Entities.ConversationSummary;
import com.chat.cloudability.ibm.domain.model.Entities.Message;
import com.chat.cloudability.ibm.domain.model.Entities.StudyModule;
import com.chat.cloudability.ibm.domain.port.in.ManageConversationsPortIn;
import com.chat.cloudability.ibm.domain.port.out.ConversationRepositoryPortOut;
import com.chat.cloudability.ibm.domain.port.out.ModuleRepositoryPortOut;

/** Interactor da gestao de conversas, sempre no escopo de um modulo. */
public class ConversationInteractor implements ManageConversationsPortIn {

    private static final String DEFAULT_TITLE = "Nova conversa";

    private final ConversationRepositoryPortOut conversations;
    private final ModuleRepositoryPortOut modules;

    public ConversationInteractor(ConversationRepositoryPortOut conversations,
            ModuleRepositoryPortOut modules) {
        this.conversations = conversations;
        this.modules = modules;
    }

    @Override
    public List<ConversationSummary> list(String moduleSlug) {
        StudyModule module = Modules.require(modules, moduleSlug);
        return conversations.list(module.id());
    }

    @Override
    public long create(String moduleSlug) {
        StudyModule module = Modules.require(modules, moduleSlug);
        return conversations.create(module.id(), DEFAULT_TITLE);
    }

    @Override
    public List<Message> messagesOf(long conversationId) {
        return conversations.messagesOf(conversationId);
    }

    @Override
    public void delete(long conversationId) {
        conversations.delete(conversationId);
    }
}
