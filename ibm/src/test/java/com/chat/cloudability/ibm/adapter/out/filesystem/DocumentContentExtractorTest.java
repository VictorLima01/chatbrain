package com.chat.cloudability.ibm.adapter.out.filesystem;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class DocumentContentExtractorTest {

    private final DocumentContentExtractor extractor = new DocumentContentExtractor();

    @Test
    void removeTimestampsIndicesEHeaderDoVtt() {
        String vtt = """
                WEBVTT
                Kind: captions
                Language: en

                1
                00:00:01.000 --> 00:00:03.000
                Hey everybody, this is Justin Keane.

                2
                00:00:03.000 --> 00:00:05.000
                Today we talk about the Commitment Manager.
                """;

        String text = DocumentContentExtractor.extractVtt(vtt);

        assertFalse(text.contains("WEBVTT"));
        assertFalse(text.contains("-->"));
        assertFalse(text.contains("Kind:"));
        assertTrue(text.contains("Hey everybody, this is Justin Keane."));
        assertTrue(text.contains("Today we talk about the Commitment Manager."));
    }

    @Test
    void removeLinhasDuplicadasDasLegendasAutomaticas() {
        String vtt = """
                WEBVTT

                1
                00:00:01.000 --> 00:00:03.000
                the default limit is 10,000 rows

                2
                00:00:03.000 --> 00:00:05.000
                the default limit is 10,000 rows

                3
                00:00:05.000 --> 00:00:07.000
                you can increase it to 64,000
                """;

        String text = DocumentContentExtractor.extractVtt(vtt);

        assertEquals(1, countOccurrences(text, "the default limit is 10,000 rows"));
        assertTrue(text.contains("you can increase it to 64,000"));
    }

    @Test
    void reconheceOsFormatosSuportados() {
        assertTrue(extractor.supports("aula.vtt"));
        assertTrue(extractor.supports("guia.PDF"));
        assertTrue(extractor.supports("deck.pptx"));
        // imagens sao responsabilidade do ImageTranscriberPortOut
        assertFalse(extractor.supports("Perguntas.png"));
        assertFalse(extractor.supports("video.mp4"));
    }

    @Test
    void derivaOTipoDoArquivo() {
        assertEquals("vtt", extractor.kindOf("aula.VTT"));
        assertEquals("pdf", extractor.kindOf("guia.pdf"));
        assertEquals("txt", extractor.kindOf("semextensao"));
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int index = haystack.indexOf(needle);
        while (index >= 0) {
            count++;
            index = haystack.indexOf(needle, index + needle.length());
        }
        return count;
    }
}
