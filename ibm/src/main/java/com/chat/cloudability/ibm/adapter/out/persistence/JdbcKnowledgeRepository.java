package com.chat.cloudability.ibm.adapter.out.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.StringJoiner;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.chat.cloudability.ibm.domain.model.Entities.DocumentSummary;
import com.chat.cloudability.ibm.domain.model.Entities.KnowledgeStats;
import com.chat.cloudability.ibm.domain.model.Entities.NewDocument;
import com.chat.cloudability.ibm.domain.model.Entities.RetrievedChunk;
import com.chat.cloudability.ibm.domain.port.out.KnowledgeRepositoryPortOut;

/**
 * Adaptador de saida: Postgres com a extensao pgvector.
 *
 * <p>Todo o SQL, o dialeto do pgvector e a estrategia de busca hibrida ficam
 * confinados aqui. O dominio pede "os melhores trechos deste modulo" e recebe
 * entidades.
 */
@Repository
public class JdbcKnowledgeRepository implements KnowledgeRepositoryPortOut {

    /**
     * Consulta lexical com os termos ligados por OU, e nao por E.
     *
     * <p>{@code plainto_tsquery} liga tudo com {@code &}: a pergunta "o que e o
     * Cloudability Financial Planning e qual o ciclo de vida de um plano?" vira
     * {@code cloudability & financial & planning & cicl & vid & plan} e exige que
     * um unico trecho contenha <em>todos</em> esses termos. Nenhum contem — o ramo
     * lexical devolvia zero linhas para qualquer pergunta escrita como frase, e a
     * busca "hibrida" era, na pratica, so vetorial.
     *
     * <p>Trocar os operadores no texto ja gerado pelo {@code plainto_tsquery}
     * aproveita o trabalho que ele faz de sanitizar e reduzir ao radical — montar
     * a tsquery na mao a partir do texto do usuario abriria espaco para injecao de
     * operadores. Com OU, o {@code ts_rank} volta a ordenar por quantos termos
     * casaram e com que densidade, que e o comportamento util.
     */
    private static final String OR_TSQUERY =
            "CAST(replace(CAST(plainto_tsquery('portuguese', :query) AS text), '&', '|') AS tsquery)";

    /**
     * Teto de trechos que um mesmo documento pode ocupar na resposta.
     *
     * <p>Sem isso, um arquivo grande e generico monopoliza o contexto: a base de
     * Cloudability tem um resumo curado de 144 trechos que casa bem com quase
     * qualquer pergunta em portugues e levava as 8 vagas sozinho, deixando de fora
     * o material especifico que responderia melhor. O teto forca a resposta a
     * enxergar pelo menos tres fontes distintas.
     */
    private static final int MAX_CHUNKS_PER_DOCUMENT = 3;

    private final JdbcClient jdbc;

    public JdbcKnowledgeRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<String> checksumOf(String source) {
        return jdbc.sql("SELECT checksum FROM kb_document WHERE source = ?")
                .param(source)
                .query(String.class)
                .optional();
    }

    @Override
    @Transactional
    public long saveDocument(NewDocument document) {
        jdbc.sql("DELETE FROM kb_document WHERE source = ?").param(document.source()).update();
        return jdbc.sql("""
                INSERT INTO kb_document (module_id, title, source, kind, level, checksum, char_count)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                RETURNING id
                """)
                .params(document.moduleId(), document.title(), document.source(), document.kind(),
                        document.level(), document.checksum(), document.text().length())
                .query(Long.class)
                .single();
    }

    @Override
    @Transactional
    public void saveChunks(long documentId, List<String> contents, List<float[]> embeddings) {
        for (int i = 0; i < contents.size(); i++) {
            jdbc.sql("""
                    INSERT INTO kb_chunk (document_id, ordinal, content, embedding)
                    VALUES (?, ?, ?, ?::vector)
                    """)
                    .params(documentId, i, contents.get(i), toVectorLiteral(embeddings.get(i)))
                    .update();
        }
        jdbc.sql("UPDATE kb_document SET chunk_count = ? WHERE id = ?")
                .params(contents.size(), documentId)
                .update();
    }

    @Override
    public List<DocumentSummary> listDocuments(long moduleId) {
        return jdbc.sql("""
                SELECT id, title, source, kind, level, char_count, chunk_count, created_at
                FROM kb_document
                WHERE module_id = ?
                ORDER BY level NULLS LAST, title
                """)
                .param(moduleId)
                .query((rs, rowNum) -> new DocumentSummary(
                        rs.getLong("id"),
                        rs.getString("title"),
                        rs.getString("source"),
                        rs.getString("kind"),
                        rs.getString("level"),
                        rs.getInt("char_count"),
                        rs.getInt("chunk_count"),
                        rs.getTimestamp("created_at").toInstant().toString()))
                .list();
    }

    @Override
    @Transactional
    public void deleteDocument(long documentId) {
        jdbc.sql("DELETE FROM kb_document WHERE id = ?").param(documentId).update();
    }

    @Override
    public KnowledgeStats stats(long moduleId) {
        return jdbc.sql("""
                SELECT (SELECT COUNT(*) FROM kb_document d
                         WHERE d.module_id = :module) AS documents,
                       (SELECT COUNT(*) FROM kb_chunk c
                          JOIN kb_document d ON d.id = c.document_id
                         WHERE d.module_id = :module) AS chunks
                """)
                .param("module", moduleId)
                .query(JdbcKnowledgeRepository::toStats)
                .single();
    }

    @Override
    public KnowledgeStats totalStats() {
        return jdbc.sql("""
                SELECT (SELECT COUNT(*) FROM kb_document) AS documents,
                       (SELECT COUNT(*) FROM kb_chunk)    AS chunks
                """)
                .query(JdbcKnowledgeRepository::toStats)
                .single();
    }

    /**
     * Busca hibrida: similaridade vetorial (cosseno) combinada com busca lexical
     * full-text via Reciprocal Rank Fusion. O lexical resgata termos exatos —
     * siglas, nomes de tela e IDs de API — que o vetor as vezes perde.
     *
     * <p>O filtro por modulo entra <em>dentro</em> de cada CTE, e nao no SELECT
     * final: se ficasse so no fim, os 40 candidatos de cada ramo poderiam vir
     * todos de outros modulos e a pergunta acabaria respondida com menos contexto
     * do que existe — ou com nenhum.
     *
     * <p>Outras duas decisoes que parecem detalhe e nao sao: {@link #OR_TSQUERY} e
     * {@link #MAX_CHUNKS_PER_DOCUMENT}.
     */
    @Override
    public List<RetrievedChunk> search(float[] queryEmbedding, String queryText, int topK,
            long moduleId) {
        return jdbc.sql("""
                WITH q AS (
                    SELECT %s AS tsq
                ),
                semantic AS (
                    SELECT c.id,
                           ROW_NUMBER() OVER (ORDER BY c.embedding <=> CAST(:vector AS vector)) AS rank
                    FROM kb_chunk c
                    JOIN kb_document d ON d.id = c.document_id
                    WHERE c.embedding IS NOT NULL
                      AND d.module_id = :module
                    ORDER BY c.embedding <=> CAST(:vector AS vector)
                    LIMIT 40
                ),
                lexical AS (
                    SELECT c.id,
                           ROW_NUMBER() OVER (
                               ORDER BY ts_rank(to_tsvector('portuguese', c.content), q.tsq) DESC) AS rank
                    FROM kb_chunk c
                    JOIN kb_document d ON d.id = c.document_id
                    CROSS JOIN q
                    WHERE to_tsvector('portuguese', c.content) @@ q.tsq
                      AND d.module_id = :module
                    LIMIT 40
                ),
                fused AS (
                    SELECT COALESCE(s.id, l.id) AS id,
                           COALESCE(1.0 / (60 + s.rank), 0) + COALESCE(1.0 / (60 + l.rank), 0) AS score
                    FROM semantic s
                    FULL OUTER JOIN lexical l ON s.id = l.id
                ),
                ranked AS (
                    SELECT c.id, c.content, c.ordinal, f.score,
                           d.title, d.source, d.level, d.kind,
                           ROW_NUMBER() OVER (PARTITION BY d.id ORDER BY f.score DESC) AS per_document
                    FROM fused f
                    JOIN kb_chunk c ON c.id = f.id
                    JOIN kb_document d ON d.id = c.document_id
                )
                SELECT id, content, ordinal, score, title, source, level, kind
                FROM ranked
                WHERE per_document <= %d
                ORDER BY score DESC
                LIMIT :topK
                """.formatted(OR_TSQUERY, MAX_CHUNKS_PER_DOCUMENT))
                .param("vector", toVectorLiteral(queryEmbedding))
                .param("query", queryText)
                .param("module", moduleId)
                .param("topK", topK)
                .query((rs, rowNum) -> new RetrievedChunk(
                        rs.getLong("id"),
                        rs.getString("content"),
                        rs.getInt("ordinal"),
                        rs.getDouble("score"),
                        rs.getString("title"),
                        rs.getString("source"),
                        rs.getString("level"),
                        rs.getString("kind")))
                .list();
    }

    private static KnowledgeStats toStats(ResultSet rs, int rowNum) throws SQLException {
        return new KnowledgeStats(rs.getInt("documents"), rs.getInt("chunks"));
    }

    /** Converte o vetor para o literal que o pgvector entende: {@code [0.1,0.2,...]}. */
    static String toVectorLiteral(float[] vector) {
        StringJoiner joiner = new StringJoiner(",", "[", "]");
        for (float v : vector) {
            joiner.add(Float.toString(v));
        }
        return joiner.toString();
    }
}
