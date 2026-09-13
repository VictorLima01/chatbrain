package com.chat.cloudability.ibm.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.chat.cloudability.ibm.domain.model.Entities.RagSettings;
import com.chat.cloudability.ibm.domain.port.in.AskQuestionPortIn;
import com.chat.cloudability.ibm.domain.port.in.IngestMaterialPortIn;
import com.chat.cloudability.ibm.domain.port.in.ManageConversationsPortIn;
import com.chat.cloudability.ibm.domain.port.in.ManageKnowledgePortIn;
import com.chat.cloudability.ibm.domain.port.in.ManageModulesPortIn;
import com.chat.cloudability.ibm.domain.port.out.ContentExtractorPortOut;
import com.chat.cloudability.ibm.domain.port.out.ConversationRepositoryPortOut;
import com.chat.cloudability.ibm.domain.port.out.EmbeddingPortOut;
import com.chat.cloudability.ibm.domain.port.out.ImageTranscriberPortOut;
import com.chat.cloudability.ibm.domain.port.out.KnowledgeRepositoryPortOut;
import com.chat.cloudability.ibm.domain.port.out.LanguageModelPortOut;
import com.chat.cloudability.ibm.domain.port.out.MaterialCatalogPortOut;
import com.chat.cloudability.ibm.domain.port.out.ModuleRepositoryPortOut;
import com.chat.cloudability.ibm.domain.service.AskQuestionInteractor;
import com.chat.cloudability.ibm.domain.service.ConversationInteractor;
import com.chat.cloudability.ibm.domain.service.IngestMaterialInteractor;
import com.chat.cloudability.ibm.domain.service.KnowledgeInteractor;
import com.chat.cloudability.ibm.domain.service.ModuleInteractor;
import com.chat.cloudability.ibm.domain.service.TextChunker;

/**
 * Montagem do hexagono.
 *
 * <p>O dominio nao tem uma unica anotacao do Spring — nem {@code @Service}, nem
 * {@code @Component}. Isso e proposital: as classes de negocio sao POJOs que se
 * instanciam com {@code new} num teste, sem contexto de aplicacao. E aqui, na
 * borda, que o framework liga as pecas.
 *
 * <p>E tambem aqui que a configuracao do Spring vira um tipo do dominio
 * ({@link RagSettings}), para que os interactors nao dependam de
 * {@code @ConfigurationProperties}.
 */
@Configuration
public class DomainConfig {

    @Bean
    public RagSettings ragSettings(AppProperties props) {
        return new RagSettings(
                props.rag().chunkSize(),
                props.rag().chunkOverlap(),
                props.rag().topK(),
                props.rag().economyTopK());
    }

    @Bean
    public TextChunker textChunker(RagSettings settings) {
        return new TextChunker(settings);
    }

    @Bean
    public AskQuestionPortIn askQuestionInteractor(
            LanguageModelPortOut languageModel,
            EmbeddingPortOut embeddings,
            KnowledgeRepositoryPortOut knowledge,
            ConversationRepositoryPortOut conversations,
            ModuleRepositoryPortOut modules,
            RagSettings settings) {
        return new AskQuestionInteractor(
                languageModel, embeddings, knowledge, conversations, modules, settings);
    }

    @Bean
    public ManageModulesPortIn moduleInteractor(
            ModuleRepositoryPortOut modules,
            MaterialCatalogPortOut catalog) {
        return new ModuleInteractor(modules, catalog);
    }

    @Bean
    public ManageConversationsPortIn conversationInteractor(
            ConversationRepositoryPortOut conversations,
            ModuleRepositoryPortOut modules) {
        return new ConversationInteractor(conversations, modules);
    }

    @Bean
    public ManageKnowledgePortIn knowledgeInteractor(
            KnowledgeRepositoryPortOut knowledge,
            ModuleRepositoryPortOut modules) {
        return new KnowledgeInteractor(knowledge, modules);
    }

    @Bean
    public IngestMaterialPortIn ingestMaterialInteractor(
            MaterialCatalogPortOut catalog,
            ModuleRepositoryPortOut modules,
            ContentExtractorPortOut extractor,
            ImageTranscriberPortOut transcriber,
            EmbeddingPortOut embeddings,
            KnowledgeRepositoryPortOut knowledge,
            TextChunker chunker) {
        return new IngestMaterialInteractor(
                catalog, modules, extractor, transcriber, embeddings, knowledge, chunker);
    }
}
