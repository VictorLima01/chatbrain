package com.chat.cloudability.ibm.domain.service;

import java.util.Locale;

import com.chat.cloudability.ibm.domain.model.Entities.StudyModule;
import com.chat.cloudability.ibm.domain.port.out.ModuleRepositoryPortOut;

/**
 * Resolucao de modulo, compartilhada pelos interactors.
 *
 * <p>Todo caso de uso que recebe um slug precisa da mesma resposta para as mesmas
 * duas perguntas: "qual modulo e este?" e "o que fazer se ele nao existir?".
 * Concentrar isso aqui evita que cada interactor invente a sua propria.
 */
final class Modules {

    private Modules() {
    }

    /**
     * Modulo do slug informado; em branco cai no padrao.
     *
     * @throws IllegalArgumentException se o slug nao corresponder a nenhum modulo —
     *         falhar e mais seguro do que responder com o material do modulo errado
     */
    static StudyModule require(ModuleRepositoryPortOut modules, String slug) {
        String wanted = slug == null || slug.isBlank() ? StudyModule.DEFAULT_SLUG : slug.strip();
        return modules.findBySlug(wanted)
                .orElseThrow(() -> new IllegalArgumentException("Modulo desconhecido: " + wanted));
    }

    /** Normaliza um nome livre em um slug utilizavel como pasta e como identificador. */
    static String slugify(String raw) {
        String slug = raw == null ? "" : raw.strip().toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-+)|(-+$)", "");
        if (slug.isBlank()) {
            throw new IllegalArgumentException("Nao foi possivel derivar um identificador de: " + raw);
        }
        return slug.length() <= 48 ? slug : slug.substring(0, 48).replaceAll("-+$", "");
    }
}
