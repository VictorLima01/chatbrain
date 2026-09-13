package com.chat.cloudability.ibm.adapter.out.filesystem;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.chat.cloudability.ibm.config.AppProperties;
import com.chat.cloudability.ibm.domain.model.Entities.MaterialRef;
import com.chat.cloudability.ibm.domain.port.out.MaterialCatalogPortOut;

/**
 * Adaptador de saida: material lido do sistema de arquivos.
 *
 * <p>Alternativa ao S3 para rodar o backend sem Docker
 * ({@code MATERIAL_PROVIDER=filesystem}). Reproduz o mesmo layout do bucket —
 * uma pasta por modulo sob {@code <base>/modules/<slug>} — para que trocar de
 * provedor nao mude o que o dominio ve.
 */
@Component
@ConditionalOnProperty(name = "app.material.provider", havingValue = "filesystem")
public class FileSystemMaterialCatalog implements MaterialCatalogPortOut {

    private static final Logger log = LoggerFactory.getLogger(FileSystemMaterialCatalog.class);

    private static final List<String> SUPPORTED_SUFFIXES =
            List.of(".vtt", ".pdf", ".pptx", ".md", ".txt", ".png", ".jpg", ".jpeg");

    private final AppProperties props;

    public FileSystemMaterialCatalog(AppProperties props) {
        this.props = props;
    }

    @Override
    public List<MaterialRef> list(String moduleSlug) {
        Path dir = moduleDir(moduleSlug);
        if (!Files.isDirectory(dir)) {
            log.warn("Pasta do modulo {} nao encontrada: {}", moduleSlug, dir);
            return List.of();
        }

        List<MaterialRef> refs = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.filter(Files::isRegularFile)
                    .filter(p -> isSupported(p.getFileName().toString()))
                    .forEach(p -> refs.add(toRef(moduleSlug, dir, p)));
        } catch (IOException e) {
            log.error("Falha ao varrer {}: {}", dir, e.getMessage());
        }
        log.info("Modulo {}: {} arquivo(s) elegiveis em {}", moduleSlug, refs.size(), dir);
        return refs;
    }

    @Override
    public byte[] read(MaterialRef ref) throws IOException {
        return Files.readAllBytes(basePath().resolve(ref.location()));
    }

    @Override
    public MaterialRef store(String moduleSlug, String filename, byte[] content) throws IOException {
        String safeName = sanitize(filename);
        Path target = moduleDir(moduleSlug).resolve("enviados").resolve(safeName);
        Files.createDirectories(target.getParent());
        Files.write(target, content);
        return toRef(moduleSlug, moduleDir(moduleSlug), target);
    }

    @Override
    public void deleteModule(String moduleSlug) {
        Path dir = moduleDir(moduleSlug);
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            // Profundidade primeiro: um diretorio so some depois do seu conteudo.
            walk.sorted((a, b) -> b.getNameCount() - a.getNameCount()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    log.warn("Nao foi possivel remover {}: {}", path, e.getMessage());
                }
            });
        } catch (IOException e) {
            log.error("Falha ao remover {}: {}", dir, e.getMessage());
        }
    }

    private Path basePath() {
        return Path.of(props.material().basePath()).toAbsolutePath().normalize();
    }

    private Path moduleDir(String moduleSlug) {
        String prefix = props.material().prefix();
        Path root = prefix == null || prefix.isBlank()
                ? basePath()
                : basePath().resolve(prefix.replaceAll("^/+|/+$", ""));
        return root.resolve(moduleSlug);
    }

    private MaterialRef toRef(String moduleSlug, Path moduleDir, Path file) {
        String path = moduleDir.relativize(file).toString().replace('\\', '/');
        String location = basePath().relativize(file).toString().replace('\\', '/');
        return new MaterialRef(location, path, file.getFileName().toString());
    }

    private static String sanitize(String filename) {
        String name = filename == null ? "arquivo.txt" : filename;
        name = name.substring(Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\')) + 1);
        name = name.replaceAll("[^A-Za-z0-9._ -]", "_").strip();
        return name.isBlank() ? "arquivo.txt" : name;
    }

    private static boolean isSupported(String filename) {
        String lower = filename.toLowerCase(Locale.ROOT);
        return SUPPORTED_SUFFIXES.stream().anyMatch(lower::endsWith);
    }
}
