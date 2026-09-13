package com.chat.cloudability.ibm.domain.service;

import java.util.List;

import com.chat.cloudability.ibm.domain.model.Entities.NewModule;
import com.chat.cloudability.ibm.domain.model.Entities.StudyModule;
import com.chat.cloudability.ibm.domain.port.in.ManageModulesPortIn;
import com.chat.cloudability.ibm.domain.port.out.MaterialCatalogPortOut;
import com.chat.cloudability.ibm.domain.port.out.ModuleRepositoryPortOut;

/**
 * Interactor dos modulos de estudo.
 *
 * <p>As regras que moram aqui: o slug e derivado do nome quando nao vem pronto e
 * e imutavel depois de criado (ele nomeia a pasta do material — mudar quebraria a
 * ligacao com os arquivos ja enviados), e um modulo de fabrica nao se apaga.
 */
public class ModuleInteractor implements ManageModulesPortIn {

    private final ModuleRepositoryPortOut modules;
    private final MaterialCatalogPortOut catalog;

    public ModuleInteractor(ModuleRepositoryPortOut modules, MaterialCatalogPortOut catalog) {
        this.modules = modules;
        this.catalog = catalog;
    }

    @Override
    public List<StudyModule> list() {
        return modules.list();
    }

    @Override
    public StudyModule bySlug(String slug) {
        return Modules.require(modules, slug);
    }

    @Override
    public StudyModule create(NewModule module) {
        if (module == null || module.name() == null || module.name().isBlank()) {
            throw new IllegalArgumentException("O modulo precisa de um nome");
        }
        String slug = Modules.slugify(
                module.slug() == null || module.slug().isBlank() ? module.name() : module.slug());
        if (modules.findBySlug(slug).isPresent()) {
            throw new IllegalArgumentException("Ja existe um modulo com o identificador " + slug);
        }

        modules.create(new NewModule(slug, module.name().strip(), text(module.description()),
                text(module.persona())));
        return Modules.require(modules, slug);
    }

    @Override
    public StudyModule update(String slug, NewModule changes) {
        StudyModule current = Modules.require(modules, slug);
        // O slug fica de fora de proposito: ele e o endereco do material no storage.
        modules.update(current.id(), new NewModule(
                current.slug(),
                changes.name() == null || changes.name().isBlank()
                        ? current.name() : changes.name().strip(),
                changes.description() == null ? current.description() : text(changes.description()),
                changes.persona() == null ? current.persona() : text(changes.persona())));
        return Modules.require(modules, current.slug());
    }

    @Override
    public void delete(String slug) {
        StudyModule module = Modules.require(modules, slug);
        if (module.builtIn()) {
            throw new IllegalArgumentException(
                    "O modulo " + module.name() + " e de fabrica e nao pode ser removido");
        }
        modules.delete(module.id());
        catalog.deleteModule(module.slug());
    }

    private static String text(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
