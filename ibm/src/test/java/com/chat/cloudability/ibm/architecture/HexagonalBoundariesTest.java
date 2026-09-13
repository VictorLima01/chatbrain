package com.chat.cloudability.ibm.architecture;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Guarda-corpo da arquitetura hexagonal.
 *
 * <p>A regra de dependencia — o dominio nao aponta para fora — so vale se algo a
 * verificar. Sem isso, um {@code @Autowired} conveniente no meio de um interactor
 * passa despercebido na revisao e o hexagono vaza aos poucos.
 *
 * <p>A verificacao e feita sobre o codigo-fonte (e nao com ArchUnit) para nao
 * adicionar dependencia ao projeto: basta ler os imports.
 */
class HexagonalBoundariesTest {

    private static final Path DOMAIN = Path.of("src/main/java/com/chat/cloudability/ibm/domain");
    private static final Path ADAPTER = Path.of("src/main/java/com/chat/cloudability/ibm/adapter");

    /** Pacotes que o dominio nao pode conhecer. */
    private static final List<String> FORBIDDEN_IN_DOMAIN = List.of(
            "org.springframework",
            "jakarta.",
            "com.anthropic",
            "org.apache.pdfbox",
            "org.apache.poi",
            "com.fasterxml.jackson",
            "java.sql",
            "javax.sql");

    @Test
    @DisplayName("o dominio nao depende de framework, SDK, banco ou biblioteca de parsing")
    void dominioNaoDependeDeInfraestrutura() throws IOException {
        List<String> violations = new ArrayList<>();

        for (Path file : javaFilesIn(DOMAIN)) {
            for (String line : Files.readAllLines(file)) {
                String trimmed = line.strip();
                if (!trimmed.startsWith("import ")) {
                    continue;
                }
                for (String forbidden : FORBIDDEN_IN_DOMAIN) {
                    if (trimmed.contains(forbidden)) {
                        violations.add(file.getFileName() + " -> " + trimmed);
                    }
                }
            }
        }

        if (!violations.isEmpty()) {
            fail("O dominio deve permanecer livre de infraestrutura. Encontrado:\n  "
                    + String.join("\n  ", violations)
                    + "\n\nSe a dependencia e mesmo necessaria, ela pertence a um adaptador,"
                    + " atras de uma porta.");
        }
    }

    @Test
    @DisplayName("o dominio nao conhece os adaptadores")
    void dominioNaoImportaAdaptadores() throws IOException {
        List<String> violations = new ArrayList<>();

        for (Path file : javaFilesIn(DOMAIN)) {
            for (String line : Files.readAllLines(file)) {
                String trimmed = line.strip();
                if (trimmed.startsWith("import ") && trimmed.contains(".adapter.")) {
                    violations.add(file.getFileName() + " -> " + trimmed);
                }
            }
        }

        assertTrue(violations.isEmpty(),
                "A seta de dependencia aponta para dentro. Violacoes:\n  "
                        + String.join("\n  ", violations));
    }

    @Test
    @DisplayName("os adaptadores conversam com o dominio apenas por portas e entidades")
    void adaptadoresNaoImportamInteractors() throws IOException {
        List<String> violations = new ArrayList<>();

        for (Path file : javaFilesIn(ADAPTER)) {
            for (String line : Files.readAllLines(file)) {
                String trimmed = line.strip();
                if (trimmed.startsWith("import ") && trimmed.contains(".domain.service.")) {
                    violations.add(file.getFileName() + " -> " + trimmed);
                }
            }
        }

        assertTrue(violations.isEmpty(),
                "Adaptadores devem depender das portas (domain.port.*), nunca das implementacoes"
                        + " (domain.service.*). Violacoes:\n  " + String.join("\n  ", violations));
    }

    private static List<Path> javaFilesIn(Path root) throws IOException {
        assertTrue(Files.isDirectory(root), "pasta nao encontrada: " + root.toAbsolutePath());
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java"))
                    .toList();
        }
    }
}
