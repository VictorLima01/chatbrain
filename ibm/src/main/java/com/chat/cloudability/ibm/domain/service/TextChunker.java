package com.chat.cloudability.ibm.domain.service;

import java.util.ArrayList;
import java.util.List;

import com.chat.cloudability.ibm.domain.model.Entities.RagSettings;

/**
 * Servico de dominio: quebra o texto em trechos com sobreposicao.
 *
 * <p>E logica de negocio pura, sem dependencia externa: onde cortar um texto para
 * que a recuperacao funcione bem e uma regra do problema, nao um detalhe de
 * infraestrutura. Cortar no meio de uma frase degrada a busca — o trecho perde o
 * sujeito ou o complemento e deixa de casar com a pergunta.
 */
public class TextChunker {

    private final RagSettings settings;

    public TextChunker(RagSettings settings) {
        this.settings = settings;
    }

    public List<String> chunk(String text) {
        int size = settings.chunkSize();
        int overlap = settings.chunkOverlap();
        String normalized = text.replaceAll("[ \\t]+", " ").replaceAll("\\n{3,}", "\n\n").strip();

        List<String> chunks = new ArrayList<>();
        if (normalized.isEmpty()) {
            return chunks;
        }
        if (normalized.length() <= size) {
            chunks.add(normalized);
            return chunks;
        }

        int start = 0;
        while (start < normalized.length()) {
            int end = Math.min(start + size, normalized.length());
            if (end < normalized.length()) {
                int boundary = findBoundary(normalized, start, end);
                if (boundary > start) {
                    end = boundary;
                }
            }
            String piece = normalized.substring(start, end).strip();
            if (!piece.isEmpty()) {
                chunks.add(piece);
            }
            if (end >= normalized.length()) {
                break;
            }
            start = Math.max(start + 1, end - overlap);
        }
        return chunks;
    }

    /** Procura, de tras para frente, uma quebra de paragrafo ou fim de frase. */
    private int findBoundary(String text, int start, int end) {
        int minimum = start + (end - start) / 2;
        int paragraph = text.lastIndexOf("\n\n", end);
        if (paragraph > minimum) {
            return paragraph;
        }
        for (int i = end - 1; i > minimum; i--) {
            char c = text.charAt(i);
            if ((c == '.' || c == '!' || c == '?' || c == '\n')
                    && i + 1 < text.length() && Character.isWhitespace(text.charAt(i + 1))) {
                return i + 1;
            }
        }
        return end;
    }
}
