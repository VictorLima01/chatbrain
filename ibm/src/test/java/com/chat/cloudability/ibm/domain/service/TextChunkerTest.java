package com.chat.cloudability.ibm.domain.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.chat.cloudability.ibm.domain.model.Entities.RagSettings;

class TextChunkerTest {

    private TextChunker chunker(int size, int overlap) {
        return new TextChunker(new RagSettings(size, overlap, 8, 3));
    }

    @Test
    void textoCurtoViraUmUnicoTrecho() {
        assertEquals(1, chunker(1200, 200)
                .chunk("Cost list remove o impacto de spot instances.").size());
    }

    @Test
    void textoLongoEQuebradoComSobreposicao() {
        String frase = "O Cloudability calcula o rightsizing usando cost total como metrica base. ";
        List<String> chunks = chunker(300, 80).chunk(frase.repeat(20));

        assertTrue(chunks.size() > 1, "esperava multiplos trechos");
        for (String chunk : chunks) {
            assertFalse(chunk.isBlank());
            assertTrue(chunk.length() <= 400, "trecho grande demais: " + chunk.length());
        }
    }

    @Test
    void naoPerdeConteudoNoMeioDaQuebra() {
        String texto = "Primeira frase sobre commitment. Segunda frase sobre rightsizing. "
                + "Terceira frase sobre anomalias. Quarta frase sobre a API do Cloudability.";
        String juntos = String.join(" ", chunker(80, 20).chunk(texto));

        assertTrue(juntos.contains("commitment"));
        assertTrue(juntos.contains("rightsizing"));
        assertTrue(juntos.contains("anomalias"));
        assertTrue(juntos.contains("API do Cloudability"));
    }

    @Test
    void textoVazioNaoGeraTrecho() {
        assertTrue(chunker(1200, 200).chunk("   \n\n  ").isEmpty());
    }
}
