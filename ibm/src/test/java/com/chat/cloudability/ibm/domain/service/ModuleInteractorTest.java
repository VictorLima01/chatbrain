package com.chat.cloudability.ibm.domain.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.chat.cloudability.ibm.domain.model.Entities.MaterialRef;
import com.chat.cloudability.ibm.domain.model.Entities.NewModule;
import com.chat.cloudability.ibm.domain.model.Entities.StudyModule;
import com.chat.cloudability.ibm.domain.port.out.MaterialCatalogPortOut;
import com.chat.cloudability.ibm.domain.port.out.ModuleRepositoryPortOut;

/** As regras de criacao e remocao de modulos, sem banco e sem bucket. */
class ModuleInteractorTest {

    private final FakeModules modules = new FakeModules();
    private final FakeCatalog catalog = new FakeCatalog();
    private final ModuleInteractor interactor = new ModuleInteractor(modules, catalog);

    @Test
    void derivaOSlugDoNomeQuandoNaoVemPronto() {
        StudyModule created = interactor.create(
                new NewModule(null, "FinOps Avancado 2026!", "descricao", null));

        assertEquals("finops-avancado-2026", created.slug());
    }

    @Test
    void naoAceitaDoisModulosComOMesmoIdentificador() {
        interactor.create(new NewModule(null, "Kubernetes", null, null));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> interactor.create(new NewModule("kubernetes", "Kubernetes de novo", null, null)));

        assertTrue(error.getMessage().contains("kubernetes"));
    }

    @Test
    void exigeNome() {
        assertThrows(IllegalArgumentException.class,
                () -> interactor.create(new NewModule("sem-nome", "  ", null, null)));
    }

    @Test
    void oSlugNaoMudaNaEdicao() {
        interactor.create(new NewModule(null, "Kubernetes", "antes", null));

        StudyModule updated = interactor.update("kubernetes",
                new NewModule("outro-slug", "Kubernetes na pratica", "depois", null));

        assertEquals("kubernetes", updated.slug(), "o slug e o endereco do material no storage");
        assertEquals("Kubernetes na pratica", updated.name());
        assertEquals("depois", updated.description());
    }

    @Test
    void removerOModuloLevaOMaterialJunto() {
        interactor.create(new NewModule(null, "Kubernetes", null, null));

        interactor.delete("kubernetes");

        assertTrue(modules.stored.stream().noneMatch(m -> m.slug().equals("kubernetes")));
        assertEquals(List.of("kubernetes"), catalog.deleted,
                "deixar os arquivos orfaos no bucket so gera custo e confusao");
    }

    @Test
    void moduloDeFabricaNaoSeApaga() {
        IllegalArgumentException error =
                assertThrows(IllegalArgumentException.class, () -> interactor.delete("cloudability"));

        assertTrue(error.getMessage().contains("fabrica"));
        assertTrue(catalog.deleted.isEmpty());
    }

    @Test
    void moduloInexistenteFalhaComOSlugNaMensagem() {
        IllegalArgumentException error =
                assertThrows(IllegalArgumentException.class, () -> interactor.bySlug("nao-existe"));

        assertTrue(error.getMessage().contains("nao-existe"));
    }

    // ------------------------------------------------------------ dubles das portas

    private static final class FakeModules implements ModuleRepositoryPortOut {
        final List<StudyModule> stored = new ArrayList<>(List.of(new StudyModule(
                1L, "cloudability", "IBM Cloudability", null, null, true, 0, 0, "2026-01-01T00:00:00Z")));
        private long nextId = 2L;

        @Override
        public List<StudyModule> list() {
            return List.copyOf(stored);
        }

        @Override
        public Optional<StudyModule> findBySlug(String slug) {
            return stored.stream().filter(m -> m.slug().equals(slug)).findFirst();
        }

        @Override
        public long create(NewModule module) {
            long id = nextId++;
            stored.add(new StudyModule(id, module.slug(), module.name(), module.description(),
                    module.persona(), false, 0, 0, "2026-01-01T00:00:00Z"));
            return id;
        }

        @Override
        public void update(long moduleId, NewModule module) {
            stored.replaceAll(m -> m.id() != moduleId ? m
                    : new StudyModule(m.id(), m.slug(), module.name(), module.description(),
                            module.persona(), m.builtIn(), m.documentCount(), m.chunkCount(),
                            m.createdAt()));
        }

        @Override
        public void delete(long moduleId) {
            stored.removeIf(m -> m.id() == moduleId);
        }
    }

    private static final class FakeCatalog implements MaterialCatalogPortOut {
        final List<String> deleted = new ArrayList<>();

        @Override
        public List<MaterialRef> list(String moduleSlug) {
            return List.of();
        }

        @Override
        public byte[] read(MaterialRef ref) {
            return new byte[0];
        }

        @Override
        public MaterialRef store(String moduleSlug, String filename, byte[] content) {
            return new MaterialRef(moduleSlug + "/" + filename, filename, filename);
        }

        @Override
        public void deleteModule(String moduleSlug) {
            deleted.add(moduleSlug);
        }
    }
}
