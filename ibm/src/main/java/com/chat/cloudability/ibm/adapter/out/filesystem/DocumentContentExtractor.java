package com.chat.cloudability.ibm.adapter.out.filesystem;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFShape;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTextShape;
import org.springframework.stereotype.Component;

import com.chat.cloudability.ibm.domain.port.out.ContentExtractorPortOut;

/**
 * Adaptador de saida: extracao de texto com bibliotecas locais.
 *
 * <p>PDFBox e Apache POI ficam confinados aqui. Formatos: VTT, PDF, PPTX, MD e TXT.
 */
@Component
public class DocumentContentExtractor implements ContentExtractorPortOut {

    @Override
    public boolean supports(String filename) {
        String lower = filename.toLowerCase(Locale.ROOT);
        return lower.endsWith(".vtt") || lower.endsWith(".pdf") || lower.endsWith(".pptx")
                || lower.endsWith(".md") || lower.endsWith(".txt");
    }

    @Override
    public String kindOf(String filename) {
        String lower = filename.toLowerCase(Locale.ROOT);
        int dot = lower.lastIndexOf('.');
        return dot < 0 ? "txt" : lower.substring(dot + 1);
    }

    @Override
    public String extract(String filename, byte[] content) throws Exception {
        String lower = filename.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".vtt")) {
            return extractVtt(new String(content, StandardCharsets.UTF_8));
        }
        if (lower.endsWith(".pdf")) {
            return extractPdf(content);
        }
        if (lower.endsWith(".pptx")) {
            return extractPptx(new ByteArrayInputStream(content));
        }
        return new String(content, StandardCharsets.UTF_8);
    }

    /**
     * Limpa uma legenda WebVTT: remove cabecalho, indices, timestamps e as linhas
     * repetidas que as legendas automaticas produzem.
     */
    static String extractVtt(String raw) {
        List<String> lines = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String rawLine : raw.split("\\R")) {
            String line = rawLine.strip();
            if (line.isEmpty()
                    || line.startsWith("WEBVTT")
                    || line.startsWith("NOTE")
                    || line.startsWith("Kind:")
                    || line.startsWith("Language:")
                    || line.matches("\\d+")
                    || line.contains("-->")) {
                continue;
            }
            if (seen.add(line)) {
                lines.add(line);
            }
        }
        return String.join(" ", lines);
    }

    static String extractPdf(byte[] bytes) throws Exception {
        try (PDDocument document = Loader.loadPDF(bytes)) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            return stripper.getText(document);
        }
    }

    static String extractPptx(InputStream in) throws Exception {
        StringBuilder text = new StringBuilder();
        try (XMLSlideShow slideShow = new XMLSlideShow(in)) {
            int slideNumber = 1;
            for (XSLFSlide slide : slideShow.getSlides()) {
                text.append("\n\n--- Slide ").append(slideNumber++).append(" ---\n");
                for (XSLFShape shape : slide.getShapes()) {
                    if (shape instanceof XSLFTextShape textShape) {
                        String value = textShape.getText();
                        if (value != null && !value.isBlank()) {
                            text.append(value.strip()).append('\n');
                        }
                    }
                }
            }
        }
        return text.toString();
    }
}
