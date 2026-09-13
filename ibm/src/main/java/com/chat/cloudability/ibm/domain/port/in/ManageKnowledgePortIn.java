package com.chat.cloudability.ibm.domain.port.in;

import java.util.List;

import com.chat.cloudability.ibm.domain.model.Entities.DocumentSummary;
import com.chat.cloudability.ibm.domain.model.Entities.KnowledgeStats;

/** Porta de entrada: consultar e podar a base de conhecimento. */
public interface ManageKnowledgePortIn {

    List<DocumentSummary> listDocuments(String moduleSlug);

    KnowledgeStats stats(String moduleSlug);

    /** Contagens somadas de todos os modulos, para a tela "Sobre". */
    KnowledgeStats totalStats();

    void deleteDocument(long documentId);
}
