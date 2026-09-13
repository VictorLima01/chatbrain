package com.chat.cloudability.ibm.domain.port.out;

import java.util.List;
import java.util.Optional;

import com.chat.cloudability.ibm.domain.model.Entities.NewModule;
import com.chat.cloudability.ibm.domain.model.Entities.StudyModule;

/** Porta de saida: persistencia dos modulos de estudo. */
public interface ModuleRepositoryPortOut {

    /** Todos os modulos, ja com as contagens da base de cada um. */
    List<StudyModule> list();

    Optional<StudyModule> findBySlug(String slug);

    long create(NewModule module);

    void update(long moduleId, NewModule module);

    /** Remove o modulo e, em cascata, seus documentos e conversas. */
    void delete(long moduleId);
}
