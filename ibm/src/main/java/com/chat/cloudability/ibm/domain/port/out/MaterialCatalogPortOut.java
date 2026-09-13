package com.chat.cloudability.ibm.domain.port.out;

import java.util.List;

import com.chat.cloudability.ibm.domain.model.Entities.MaterialRef;

/**
 * Porta de saida: onde o material de estudo esta guardado.
 *
 * <p>O armazenamento e particionado por modulo — cada modulo tem sua pasta, e o
 * catalogo nunca devolve arquivos de um modulo quando perguntam por outro. Se
 * isso e um prefixo num bucket S3 ou um diretorio em disco e decisao do
 * adaptador; o interactor de ingestao so conhece slugs.
 */
public interface MaterialCatalogPortOut {

    /** Arquivos elegiveis do modulo, sem carregar o conteudo. */
    List<MaterialRef> list(String moduleSlug);

    /** Le o conteudo de um arquivo listado. */
    byte[] read(MaterialRef ref) throws Exception;

    /** Guarda um arquivo novo na pasta do modulo e devolve a referencia criada. */
    MaterialRef store(String moduleSlug, String filename, byte[] content) throws Exception;

    /** Apaga a pasta inteira do modulo. Chamado quando o modulo e removido. */
    void deleteModule(String moduleSlug);
}
