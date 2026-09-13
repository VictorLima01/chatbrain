package com.chat.cloudability.ibm.domain.port.in;

import com.chat.cloudability.ibm.domain.model.Entities.IngestReport;

/**
 * Porta de entrada: alimentar a base de conhecimento de um modulo.
 *
 * <p>Toda operacao e escopada a um modulo: nao existe ingestao "global". O
 * material enviado entra no armazenamento do modulo e so e recuperavel por
 * perguntas feitas nele.
 */
public interface IngestMaterialPortIn {

    /**
     * Varre o material armazenado do modulo e indexa o que mudou.
     *
     * @param force reprocessa tudo, ignorando o checksum
     */
    IngestReport syncModule(String moduleSlug, boolean force);

    /** Guarda o arquivo no modulo e o indexa. Usado pelo upload do administrador. */
    IngestReport ingestUpload(String moduleSlug, String title, String filename, byte[] content,
            String level);

    /** Guarda o texto colado como um arquivo markdown do modulo e o indexa. */
    IngestReport ingestText(String moduleSlug, String title, String content, String level);
}
