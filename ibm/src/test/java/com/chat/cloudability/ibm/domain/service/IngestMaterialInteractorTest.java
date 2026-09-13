package com.chat.cloudability.ibm.domain.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class IngestMaterialInteractorTest {

    @Test
    void derivaNivelEsubpastaDoCaminho() {
        assertEquals("L4 / Optimization Features",
                IngestMaterialInteractor.levelOf("L4/Optimization Features/rigthsizing.vtt"));
        assertEquals("L1", IngestMaterialInteractor.levelOf("L1/finops_1_1.txt"));
    }

    @Test
    void encontraONivelIndependenteDaProfundidade() {
        // O material ja esteve na raiz, depois em docs/, hoje numa pasta por modulo
        // no bucket. A classificacao nao pode depender da posicao no caminho.
        assertEquals("L2 / Guias", IngestMaterialInteractor.levelOf("L2/Guias/estudo.md"));
        assertEquals("L2 / Guias", IngestMaterialInteractor.levelOf("docs/L2/Guias/estudo.md"));
        assertEquals("L2 / Guias", IngestMaterialInteractor.levelOf("ingestao/L2/Guias/estudo.md"));
        assertEquals("L2 / Guias", IngestMaterialInteractor.levelOf("a/b/c/L2/Guias/estudo.md"));
    }

    @Test
    void reconheceOAgenteEspecialistaEOsDemais() {
        assertEquals("Agente especialista",
                IngestMaterialInteractor.levelOf("agents/cloudability-specialist-ibm.md"));
        assertEquals("Geral", IngestMaterialInteractor.levelOf("Perguntas.png"));
    }

    @Test
    void moduloSemAConvencaoDeNivelUsaAPrimeiraPasta() {
        // Um modulo criado pelo administrador nao segue L1/L2/L4: a primeira pasta
        // e a melhor pista de organizacao que existe, e "Geral" quando nem isso ha.
        assertEquals("enviados", IngestMaterialInteractor.levelOf("enviados/aula-01.pdf"));
        assertEquals("Geral", IngestMaterialInteractor.levelOf("aula-01.pdf"));
    }
}
