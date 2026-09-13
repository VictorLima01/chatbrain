package com.chat.cloudability.ibm.domain.port.out;

/**
 * Porta de saida: extracao de texto de documentos.
 *
 * <p>Cobre os formatos que se resolvem com bibliotecas locais (VTT, PDF, PPTX, MD,
 * TXT). Imagens ficam com {@link ImageTranscriberPortOut} — a separacao existe
 * porque transcrever imagem custa tokens de API, e essa diferenca de custo e uma
 * decisao que o dominio precisa enxergar.
 */
public interface ContentExtractorPortOut {

    boolean supports(String filename);

    /** Tipo do arquivo (`vtt`, `pdf`, ...), guardado como metadado do documento. */
    String kindOf(String filename);

    /** Texto puro extraido do arquivo. */
    String extract(String filename, byte[] content) throws Exception;
}
