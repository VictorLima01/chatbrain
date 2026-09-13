package com.chat.cloudability.ibm.domain.service;

import java.util.List;

import com.chat.cloudability.ibm.domain.model.Entities.DocumentSummary;
import com.chat.cloudability.ibm.domain.model.Entities.KnowledgeStats;
import com.chat.cloudability.ibm.domain.model.Entities.StudyModule;
import com.chat.cloudability.ibm.domain.port.in.ManageKnowledgePortIn;
import com.chat.cloudability.ibm.domain.port.out.KnowledgeRepositoryPortOut;
import com.chat.cloudability.ibm.domain.port.out.ModuleRepositoryPortOut;

/** Interactor da consulta e poda da base de conhecimento. */
public class KnowledgeInteractor implements ManageKnowledgePortIn {

    private final KnowledgeRepositoryPortOut knowledge;
    private final ModuleRepositoryPortOut modules;

    public KnowledgeInteractor(KnowledgeRepositoryPortOut knowledge, ModuleRepositoryPortOut modules) {
        this.knowledge = knowledge;
        this.modules = modules;
    }

    @Override
    public List<DocumentSummary> listDocuments(String moduleSlug) {
        StudyModule module = Modules.require(modules, moduleSlug);
        return knowledge.listDocuments(module.id());
    }

    @Override
    public KnowledgeStats stats(String moduleSlug) {
        StudyModule module = Modules.require(modules, moduleSlug);
        return knowledge.stats(module.id());
    }

    @Override
    public KnowledgeStats totalStats() {
        return knowledge.totalStats();
    }

    @Override
    public void deleteDocument(long documentId) {
        knowledge.deleteDocument(documentId);
    }
}
