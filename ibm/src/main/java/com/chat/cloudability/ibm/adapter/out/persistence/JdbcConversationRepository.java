package com.chat.cloudability.ibm.adapter.out.persistence;

import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.chat.cloudability.ibm.domain.model.Entities.ConversationSummary;
import com.chat.cloudability.ibm.domain.model.Entities.Message;
import com.chat.cloudability.ibm.domain.model.Entities.Role;
import com.chat.cloudability.ibm.domain.model.Entities.SourceRef;
import com.chat.cloudability.ibm.domain.port.out.ConversationRepositoryPortOut;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Adaptador de saida: conversas e mensagens no Postgres. */
@Repository
public class JdbcConversationRepository implements ConversationRepositoryPortOut {

    private static final String DEFAULT_TITLE = "Nova conversa";

    private final JdbcClient jdbc;
    private final ObjectMapper mapper = new ObjectMapper();

    public JdbcConversationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public long create(long moduleId, String title) {
        return jdbc.sql("INSERT INTO conversation (module_id, title) VALUES (?, ?) RETURNING id")
                .params(moduleId, title)
                .query(Long.class)
                .single();
    }

    @Override
    public List<ConversationSummary> list(long moduleId) {
        return jdbc.sql("""
                SELECT c.id, c.title, c.updated_at,
                       (SELECT COUNT(*) FROM chat_message m WHERE m.conversation_id = c.id) AS message_count
                FROM conversation c
                WHERE c.module_id = ?
                ORDER BY c.updated_at DESC
                LIMIT 100
                """)
                .param(moduleId)
                .query((rs, rowNum) -> new ConversationSummary(
                        rs.getLong("id"),
                        rs.getString("title"),
                        rs.getTimestamp("updated_at").toInstant().toString(),
                        rs.getInt("message_count")))
                .list();
    }

    @Override
    public List<Message> messagesOf(long conversationId) {
        return jdbc.sql("""
                SELECT role, content, sources
                FROM chat_message
                WHERE conversation_id = ?
                ORDER BY id
                """)
                .param(conversationId)
                .query((rs, rowNum) -> new Message(
                        Role.of(rs.getString("role")),
                        rs.getString("content"),
                        readSources(rs.getString("sources"))))
                .list();
    }

    @Override
    @Transactional
    public void append(long conversationId, Message message) {
        jdbc.sql("INSERT INTO chat_message (conversation_id, role, content, sources) VALUES (?, ?, ?, ?)")
                .params(conversationId,
                        message.role().wireValue(),
                        message.content(),
                        writeSources(message.sources()))
                .update();
        jdbc.sql("UPDATE conversation SET updated_at = now() WHERE id = ?")
                .param(conversationId)
                .update();
    }

    @Override
    public void renameIfUntitled(long conversationId, String newTitle) {
        jdbc.sql("UPDATE conversation SET title = ? WHERE id = ? AND title = ?")
                .params(newTitle, conversationId, DEFAULT_TITLE)
                .update();
    }

    @Override
    public void delete(long conversationId) {
        jdbc.sql("DELETE FROM conversation WHERE id = ?").param(conversationId).update();
    }

    // A serializacao das fontes e detalhe de armazenamento, nao do dominio.

    private String writeSources(List<SourceRef> sources) {
        if (sources == null || sources.isEmpty()) {
            return null;
        }
        try {
            return mapper.writeValueAsString(sources);
        } catch (Exception e) {
            return null;
        }
    }

    private List<SourceRef> readSources(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return mapper.readValue(json, new TypeReference<List<SourceRef>>() {
            });
        } catch (Exception e) {
            return List.of();
        }
    }
}
