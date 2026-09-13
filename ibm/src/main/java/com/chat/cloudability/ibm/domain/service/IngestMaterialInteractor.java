package com.chat.cloudability.ibm.domain.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

import com.chat.cloudability.ibm.domain.model.Entities.IngestReport;
import com.chat.cloudability.ibm.domain.model.Entities.KnowledgeStats;
import com.chat.cloudability.ibm.domain.model.Entities.MaterialRef;
import com.chat.cloudability.ibm.domain.model.Entities.NewDocument;
import com.chat.cloudability.ibm.domain.model.Entities.StudyModule;
import com.chat.cloudability.ibm.domain.port.in.IngestMaterialPortIn;
import com.chat.cloudability.ibm.domain.port.out.ContentExtractorPortOut;
import com.chat.cloudability.ibm.domain.port.out.EmbeddingPortOut;
import com.chat.cloudability.ibm.domain.port.out.ImageTranscriberPortOut;
import com.chat.cloudability.ibm.domain.port.out.KnowledgeRepositoryPortOut;
import com.chat.cloudability.ibm.domain.port.out.MaterialCatalogPortOut;
import com.chat.cloudability.ibm.domain.port.out.ModuleRepositoryPortOut;

/**
 * Interactor da ingestao: transforma material bruto em base pesquisavel.
 *
 * <p>As decisoes que moram aqui sao todas de negocio: a qual modulo o documento
 * pertence, quando pular um arquivo (checksum inalterado), como classificar o
 * nivel a partir do caminho, quando gastar tokens transcrevendo uma imagem, e em
 * que tamanho de lote vetorizar.
 *
 * <p>Nada aqui sabe que o material mora num bucket. O que sabe e que cada modulo
 * tem a sua pasta e que nenhum documento entra na base sem carimbo de modulo.
 */
public class IngestMaterialInteractor implements IngestMaterialPortIn {

    private static final int EMBED_BATCH = 16;

    private final MaterialCatalogPortOut catalog;
    private final ModuleRepositoryPortOut modules;
    private final ContentExtractorPortOut extractor;
    private final ImageTranscriberPortOut transcriber;
    private final EmbeddingPortOut embeddings;
    private final KnowledgeRepositoryPortOut knowledge;
    private final TextChunker chunker;

    public IngestMaterialInteractor(
            MaterialCatalogPortOut catalog,
            ModuleRepositoryPortOut modules,
            ContentExtractorPortOut extractor,
            ImageTranscriberPortOut transcriber,
            EmbeddingPortOut embeddings,
            KnowledgeRepositoryPortOut knowledge,
            TextChunker chunker) {
        this.catalog = catalog;
        this.modules = modules;
        this.extractor = extractor;
        this.transcriber = transcriber;
        this.embeddings = embeddings;
        this.knowledge = knowledge;
        this.chunker = chunker;
    }

    @Override
    public IngestReport syncModule(String moduleSlug, boolean force) {
        StudyModule module = Modules.require(modules, moduleSlug);
        List<MaterialRef> files = catalog.list(module.slug());

        int indexed = 0;
        int skipped = 0;
        int failed = 0;
        List<String> errors = new ArrayList<>();

        for (MaterialRef ref : files) {
            try {
                byte[] bytes = catalog.read(ref);
                String checksum = sha256(bytes);

                boolean unchanged = knowledge.checksumOf(ref.location())
                        .filter(checksum::equals)
                        .isPresent();
                if (!force && unchanged) {
                    skipped++;
                    continue;
                }

                String text = extractText(ref.filename(), bytes);
                if (text == null || text.isBlank()) {
                    skipped++;
                    continue;
                }

                index(new NewDocument(
                        module.id(),
                        titleOf(ref.filename()),
                        ref.location(),
                        extractor.kindOf(ref.filename()),
                        levelOf(ref.path()),
                        checksum,
                        text));
                indexed++;
            } catch (Exception e) {
                failed++;
                errors.add(ref.path() + ": " + e.getMessage());
            }
        }

        return reportFor(module, indexed, skipped, failed, errors);
    }

    @Override
    public IngestReport ingestUpload(String moduleSlug, String title, String filename, byte[] content,
            String level) {
        StudyModule module = Modules.require(modules, moduleSlug);

        String text;
        try {
            text = extractText(filename, content);
        } catch (Exception e) {
            throw new IllegalArgumentException("Nao foi possivel extrair texto de " + filename, e);
        }
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("Nao foi possivel extrair texto de " + filename);
        }

        // O arquivo enviado passa a fazer parte do material do modulo: guarda-se o
        // original, e nao so o texto extraido, para que um "reindexar tudo" futuro
        // encontre a mesma fonte.
        MaterialRef stored;
        try {
            stored = catalog.store(module.slug(), filename, content);
        } catch (Exception e) {
            throw new IllegalStateException("Falha ao guardar " + filename + ": " + e.getMessage(), e);
        }

        index(new NewDocument(
                module.id(),
                title == null || title.isBlank() ? titleOf(filename) : title.strip(),
                stored.location(),
                extractor.kindOf(filename),
                level == null || level.isBlank() ? levelOf(stored.path()) : level.strip(),
                sha256(content),
                text));

        return reportFor(module, 1, 0, 0, List.of());
    }

    @Override
    public IngestReport ingestText(String moduleSlug, String title, String content, String level) {
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("O texto precisa de um titulo");
        }
        String filename = title.replaceAll("[^a-zA-Z0-9-_ ]", "").strip() + ".md";
        return ingestUpload(moduleSlug, title, filename,
                content.getBytes(StandardCharsets.UTF_8), level);
    }

    /**
     * Escolhe a rota de extracao. Imagens custam tokens de API, entao so vao para
     * o transcritor quando ele esta realmente disponivel.
     */
    private String extractText(String filename, byte[] content) throws Exception {
        if (transcriber.handles(filename)) {
            if (!transcriber.isAvailable()) {
                return "[Imagem nao transcrita: transcritor indisponivel] " + filename;
            }
            return transcriber.transcribe(filename, content);
        }
        if (!extractor.supports(filename)) {
            throw new IllegalArgumentException("Formato nao suportado: " + filename);
        }
        return extractor.extract(filename, content);
    }

    private void index(NewDocument document) {
        List<String> chunks = chunker.chunk(document.text());
        if (chunks.isEmpty()) {
            return;
        }
        List<float[]> vectors = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i += EMBED_BATCH) {
            vectors.addAll(embeddings.embedDocuments(
                    chunks.subList(i, Math.min(i + EMBED_BATCH, chunks.size()))));
        }
        long documentId = knowledge.saveDocument(document);
        knowledge.saveChunks(documentId, chunks, vectors);
    }

    /** As contagens do relatorio sao as do modulo, nao as da base inteira. */
    private IngestReport reportFor(StudyModule module, int indexed, int skipped, int failed,
            List<String> errors) {
        KnowledgeStats stats = knowledge.stats(module.id());
        return new IngestReport(indexed, skipped, failed, stats.documents(), stats.chunks(), errors);
    }

    static String titleOf(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot > 0 ? filename.substring(0, dot) : filename;
    }

    /**
     * Deriva o nivel (L1/L2/L4...) do caminho dentro do modulo.
     *
     * <p>Procura o segmento {@code L<digito>} em qualquer posicao, e nao apenas no
     * inicio: assim a classificacao sobrevive a mudancas na organizacao das pastas.
     * Um modulo novo, sem essa convencao, cai em "Geral" ou na primeira subpasta.
     */
    public static String levelOf(String path) {
        String[] parts = path.split("/");
        for (int i = 0; i < parts.length; i++) {
            if (parts[i].matches("L\\d")) {
                boolean hasSubfolder = i + 1 < parts.length && !parts[i + 1].contains(".");
                return hasSubfolder ? parts[i] + " / " + parts[i + 1] : parts[i];
            }
        }
        if (path.contains("agents")) {
            return "Agente especialista";
        }
        return parts.length > 1 ? parts[0] : "Geral";
    }

    static String sha256(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (Exception e) {
            return HexFormat.of().formatHex(String.valueOf(bytes.length).getBytes(StandardCharsets.UTF_8));
        }
    }
}
