package com.chat.cloudability.ibm.adapter.in.web;

import java.util.List;
import java.util.Map;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.chat.cloudability.ibm.adapter.in.web.dto.WebDtos;
import com.chat.cloudability.ibm.domain.model.Entities.IngestReport;
import com.chat.cloudability.ibm.domain.model.Entities.NewModule;
import com.chat.cloudability.ibm.domain.port.in.IngestMaterialPortIn;
import com.chat.cloudability.ibm.domain.port.in.ManageKnowledgePortIn;
import com.chat.cloudability.ibm.domain.port.in.ManageModulesPortIn;

/**
 * Adaptador de entrada: area administrativa.
 *
 * <p>Tudo aqui exige alcada de administrador — o {@link AdminAccessInterceptor}
 * barra o que estiver sob {@code /api/admin} antes de chegar a este controller.
 *
 * <p>E o unico caminho para alimentar a base: criar um modulo e mandar material
 * para ele. O material enviado por estas rotas fica guardado na pasta daquele
 * modulo e so alimenta as perguntas feitas nele.
 */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final ManageModulesPortIn modules;
    private final IngestMaterialPortIn ingest;
    private final ManageKnowledgePortIn knowledge;

    public AdminController(
            ManageModulesPortIn modules,
            IngestMaterialPortIn ingest,
            ManageKnowledgePortIn knowledge) {
        this.modules = modules;
        this.ingest = ingest;
        this.knowledge = knowledge;
    }

    // ------------------------------------------------------------------ modulos

    @GetMapping("/modules")
    public List<WebDtos.ModuleResponse> list() {
        return modules.list().stream().map(WebDtos.ModuleResponse::from).toList();
    }

    @PostMapping("/modules")
    public WebDtos.ModuleResponse create(@RequestBody WebDtos.ModuleRequest request) {
        return WebDtos.ModuleResponse.from(modules.create(toNewModule(request)));
    }

    @PutMapping("/modules/{slug}")
    public WebDtos.ModuleResponse update(
            @PathVariable String slug,
            @RequestBody WebDtos.ModuleRequest request) {
        return WebDtos.ModuleResponse.from(modules.update(slug, toNewModule(request)));
    }

    @DeleteMapping("/modules/{slug}")
    public Map<String, Object> delete(@PathVariable String slug) {
        modules.delete(slug);
        return Map.of("deleted", true);
    }

    // ------------------------------------------------------------------ material

    /** Varre a pasta do modulo no armazenamento e indexa o que mudou. */
    @PostMapping("/modules/{slug}/sync")
    public IngestReport sync(
            @PathVariable String slug,
            @RequestParam(defaultValue = "false") boolean force) {
        return ingest.syncModule(slug, force);
    }

    @PostMapping(value = "/modules/{slug}/material", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public IngestReport upload(
            @PathVariable String slug,
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "title", required = false) String title,
            @RequestParam(value = "level", required = false) String level) throws Exception {
        String filename = file.getOriginalFilename() == null
                ? "arquivo.txt"
                : file.getOriginalFilename();
        String finalTitle = (title == null || title.isBlank()) ? filename : title;
        return ingest.ingestUpload(slug, finalTitle, filename, file.getBytes(), level);
    }

    @PostMapping("/modules/{slug}/text")
    public IngestReport addText(
            @PathVariable String slug,
            @RequestBody WebDtos.TextIngestRequest request) {
        return ingest.ingestText(slug, request.title(), request.content(), request.level());
    }

    @DeleteMapping("/knowledge/{id}")
    public Map<String, Object> deleteDocument(@PathVariable long id) {
        knowledge.deleteDocument(id);
        return Map.of("deleted", true);
    }

    private static NewModule toNewModule(WebDtos.ModuleRequest request) {
        return new NewModule(
                request.slug(), request.name(), request.description(), request.persona());
    }
}
