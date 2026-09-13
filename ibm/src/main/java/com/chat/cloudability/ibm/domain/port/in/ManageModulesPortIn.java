package com.chat.cloudability.ibm.domain.port.in;

import java.util.List;

import com.chat.cloudability.ibm.domain.model.Entities.NewModule;
import com.chat.cloudability.ibm.domain.model.Entities.StudyModule;

/**
 * Porta de entrada: gerenciar os modulos de estudo.
 *
 * <p>Listar e leitura para qualquer alcada; criar, alterar e remover sao acoes
 * de administrador. Quem verifica a alcada e o adaptador de entrada — o dominio
 * define o que a operacao faz, nao quem pode chama-la.
 */
public interface ManageModulesPortIn {

    List<StudyModule> list();

    /** @throws IllegalArgumentException se o slug nao existir */
    StudyModule bySlug(String slug);

    StudyModule create(NewModule module);

    StudyModule update(String slug, NewModule module);

    void delete(String slug);
}
