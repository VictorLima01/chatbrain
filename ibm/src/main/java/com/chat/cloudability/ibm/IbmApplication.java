package com.chat.cloudability.ibm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import com.chat.cloudability.ibm.config.AppProperties;
import com.chat.cloudability.ibm.domain.model.Entities.IngestReport;
import com.chat.cloudability.ibm.domain.model.Entities.StudyModule;
import com.chat.cloudability.ibm.domain.port.in.IngestMaterialPortIn;
import com.chat.cloudability.ibm.domain.port.in.ManageModulesPortIn;

@SpringBootApplication
@EnableConfigurationProperties(AppProperties.class)
public class IbmApplication {

    private static final Logger log = LoggerFactory.getLogger(IbmApplication.class);

    public static void main(String[] args) {
        SpringApplication.run(IbmApplication.class, args);
    }

    /**
     * Indexa o material de todos os modulos no boot quando KB_AUTO_INGEST=true.
     *
     * <p>Repare que depende das <em>portas</em>, nao das implementacoes.
     */
    @Bean
    CommandLineRunner bootstrapKnowledgeBase(
            AppProperties props, ManageModulesPortIn modules, IngestMaterialPortIn ingest) {
        return args -> {
            if (!props.material().autoSyncOnStartup()) {
                log.info("Ingestao automatica desativada (KB_AUTO_INGEST=false). "
                        + "Use a area de administracao para sincronizar cada modulo.");
                return;
            }
            for (StudyModule module : modules.list()) {
                log.info("Sincronizando o modulo {}...", module.slug());
                try {
                    IngestReport report = ingest.syncModule(module.slug(), false);
                    log.info("Modulo {}: {} indexados, {} ignorados, {} falhas"
                            + " — {} documentos / {} trechos",
                            module.slug(), report.indexed(), report.skipped(), report.failed(),
                            report.totalDocuments(), report.totalChunks());
                } catch (Exception e) {
                    // Um modulo com problema nao pode impedir o backend de subir.
                    log.error("Falha ao sincronizar o modulo {}: {}", module.slug(), e.getMessage());
                }
            }
        };
    }
}
