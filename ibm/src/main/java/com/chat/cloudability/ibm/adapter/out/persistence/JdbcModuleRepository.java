package com.chat.cloudability.ibm.adapter.out.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.chat.cloudability.ibm.domain.model.Entities.NewModule;
import com.chat.cloudability.ibm.domain.model.Entities.StudyModule;
import com.chat.cloudability.ibm.domain.port.out.ModuleRepositoryPortOut;

/** Adaptador de saida: modulos de estudo no Postgres. */
@Repository
public class JdbcModuleRepository implements ModuleRepositoryPortOut {

    /**
     * As contagens vem junto do modulo numa consulta so — a alternativa (listar
     * modulos e depois contar de um em um) faz N+1 idas ao banco para montar uma
     * tela que sempre mostra as duas coisas.
     */
    private static final String SELECT = """
            SELECT m.id, m.slug, m.name, m.description, m.persona, m.built_in, m.created_at,
                   (SELECT COUNT(*) FROM kb_document d WHERE d.module_id = m.id) AS document_count,
                   (SELECT COUNT(*) FROM kb_chunk c
                      JOIN kb_document d ON d.id = c.document_id
                     WHERE d.module_id = m.id) AS chunk_count
            FROM study_module m
            """;

    private final JdbcClient jdbc;

    public JdbcModuleRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<StudyModule> list() {
        return jdbc.sql(SELECT + " ORDER BY m.built_in DESC, m.name")
                .query(JdbcModuleRepository::toModule)
                .list();
    }

    @Override
    public Optional<StudyModule> findBySlug(String slug) {
        return jdbc.sql(SELECT + " WHERE m.slug = ?")
                .param(slug)
                .query(JdbcModuleRepository::toModule)
                .optional();
    }

    @Override
    public long create(NewModule module) {
        return jdbc.sql("""
                INSERT INTO study_module (slug, name, description, persona)
                VALUES (?, ?, ?, ?)
                RETURNING id
                """)
                .params(module.slug(), module.name(), module.description(), module.persona())
                .query(Long.class)
                .single();
    }

    @Override
    public void update(long moduleId, NewModule module) {
        jdbc.sql("UPDATE study_module SET name = ?, description = ?, persona = ? WHERE id = ?")
                .params(module.name(), module.description(), module.persona(), moduleId)
                .update();
    }

    @Override
    @Transactional
    public void delete(long moduleId) {
        // Documentos, trechos e conversas somem por cascata (ver V2__modules.sql).
        jdbc.sql("DELETE FROM study_module WHERE id = ?").param(moduleId).update();
    }

    private static StudyModule toModule(ResultSet rs, int rowNum) throws SQLException {
        return new StudyModule(
                rs.getLong("id"),
                rs.getString("slug"),
                rs.getString("name"),
                rs.getString("description"),
                rs.getString("persona"),
                rs.getBoolean("built_in"),
                rs.getInt("document_count"),
                rs.getInt("chunk_count"),
                rs.getTimestamp("created_at").toInstant().toString());
    }
}
