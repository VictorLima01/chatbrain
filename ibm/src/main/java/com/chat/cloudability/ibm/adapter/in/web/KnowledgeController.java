package com.chat.cloudability.ibm.adapter.in.web;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.chat.cloudability.ibm.adapter.in.web.dto.WebDtos;
import com.chat.cloudability.ibm.config.AppProperties;
import com.chat.cloudability.ibm.domain.model.Entities.KnowledgeStats;
import com.chat.cloudability.ibm.domain.model.Entities.StudyModule;
import com.chat.cloudability.ibm.domain.port.in.ManageKnowledgePortIn;
import com.chat.cloudability.ibm.domain.port.out.EmbeddingPortOut;
import com.chat.cloudability.ibm.domain.port.out.LanguageModelPortOut;

/**
 * Adaptador de entrada: leitura da base de conhecimento.
 *
 * <p>So consulta. Alimentar a base — enviar arquivo, colar texto, sincronizar,
 * remover documento — e alcada de administrador e vive em
 * {@link AdminController}.
 */
@RestController
@RequestMapping("/api/knowledge")
public class KnowledgeController {

    private final ManageKnowledgePortIn knowledge;
    private final LanguageModelPortOut languageModel;
    private final EmbeddingPortOut embeddings;
    private final AppProperties props;

    public KnowledgeController(
            ManageKnowledgePortIn knowledge,
            LanguageModelPortOut languageModel,
            EmbeddingPortOut embeddings,
            AppProperties props) {
        this.knowledge = knowledge;
        this.languageModel = languageModel;
        this.embeddings = embeddings;
        this.props = props;
    }

    @GetMapping
    public Map<String, Object> list(
            @RequestParam(name = "module", defaultValue = StudyModule.DEFAULT_SLUG) String module) {
        List<WebDtos.DocumentResponse> documents = knowledge.listDocuments(module).stream()
                .map(WebDtos.DocumentResponse::from)
                .toList();
        KnowledgeStats stats = knowledge.stats(module);
        return Map.of(
                "module", module,
                "documents", documents,
                "totalDocuments", stats.documents(),
                "totalChunks", stats.chunks());
    }

    /** Estado da configuracao, exibido na tela "Sobre". Tambem serve de healthcheck. */
    @GetMapping("/status")
    public Map<String, Object> status() {
        KnowledgeStats stats = knowledge.totalStats();
        return Map.of(
                "anthropicConfigured", languageModel.isConfigured(),
                "model", languageModel.modelName(),
                "effort", props.anthropic().effort(),
                "embeddingProvider", props.embedding().provider(),
                "embeddingDimensions", embeddings.dimensions(),
                "materialProvider", props.material().provider(),
                "topK", props.rag().topK(),
                "totalDocuments", stats.documents(),
                "totalChunks", stats.chunks());
    }
}
