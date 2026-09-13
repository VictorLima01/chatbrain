package com.chat.cloudability.ibm.domain.port.out;

import java.util.List;

/**
 * Porta de saida: geracao de vetores.
 *
 * <p>O dominio sabe que precisa transformar texto em vetor para poder buscar por
 * significado. Se isso acontece num container local, na Voyage AI ou no Bedrock e
 * decisao do adaptador.
 */
public interface EmbeddingPortOut {

    /** Vetor de uma pergunta. */
    float[] embedQuery(String text);

    /** Vetores de varios trechos de documento, na mesma ordem da entrada. */
    List<float[]> embedDocuments(List<String> texts);

    int dimensions();
}
