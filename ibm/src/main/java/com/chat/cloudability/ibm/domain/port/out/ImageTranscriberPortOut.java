package com.chat.cloudability.ibm.domain.port.out;

/**
 * Porta de saida: transcricao de imagens para texto.
 *
 * <p>Separada da extracao de documentos porque tem caracteristicas diferentes:
 * depende de um servico externo, custa tokens e pode estar indisponivel. O
 * dominio decide quando vale a pena aciona-la.
 */
public interface ImageTranscriberPortOut {

    /** Se este arquivo e uma imagem que esta porta sabe transcrever. */
    boolean handles(String filename);

    boolean isAvailable();

    String transcribe(String filename, byte[] content);
}
