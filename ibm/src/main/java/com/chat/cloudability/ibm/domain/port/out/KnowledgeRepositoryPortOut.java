package com.chat.cloudability.ibm.domain.port.out;

import java.util.List;
import java.util.Optional;

import com.chat.cloudability.ibm.domain.model.Entities.DocumentSummary;
import com.chat.cloudability.ibm.domain.model.Entities.KnowledgeStats;
import com.chat.cloudability.ibm.domain.model.Entities.NewDocument;
import com.chat.cloudability.ibm.domain.model.Entities.RetrievedChunk;

/**
 * Porta de saida: persistencia e busca da base de conhecimento.
 *
 * <p>Netflix: <em>"Repositories are the interfaces to getting entities as well as
 * creating and changing them."</em> Note que nada aqui menciona SQL, pgvector ou
 * indice HNSW — sao detalhes do adaptador.
 */
public interface KnowledgeRepositoryPortOut {

    /** Checksum ja registrado para essa fonte, se houver. Base do controle incremental. */
    Optional<String> checksumOf(String source);

    /** Grava (ou regrava) o documento e devolve seu id. */
    long saveDocument(NewDocument document);

    /** Grava os trechos e seus vetores. As listas tem o mesmo tamanho e ordem. */
    void saveChunks(long documentId, List<String> contents, List<float[]> embeddings);

    List<DocumentSummary> listDocuments(long moduleId);

    void deleteDocument(long documentId);

    KnowledgeStats stats(long moduleId);

    /** Contagens de todos os modulos somados. */
    KnowledgeStats totalStats();

    /**
     * Busca os trechos mais relevantes para a consulta, <strong>dentro de um unico
     * modulo</strong>.
     *
     * <p>O recorte por modulo nao e um filtro opcional: e o que garante que uma
     * pergunta feita num modulo novo nunca traga material de outro. O dominio pede
     * "os melhores trechos deste modulo"; se o adaptador resolve isso com busca
     * vetorial, lexical ou a combinacao das duas nao muda nada aqui.
     */
    List<RetrievedChunk> search(float[] queryEmbedding, String queryText, int topK, long moduleId);
}
