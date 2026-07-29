package com.zhida.store;

import com.zhida.dto.ConversationSnapshot;
import com.zhida.dto.StoredConversation;
import com.zhida.dto.StoredMessage;
import org.sqlite.SQLiteDataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class LocalConversationStore {
    private final SQLiteDataSource dataSource;

    public LocalConversationStore(@Value("${zhida.data-dir:./data}") String dataDirectory) {
        try {
            Path directory = Path.of(dataDirectory).toAbsolutePath().normalize();
            Files.createDirectories(directory);
            dataSource = new SQLiteDataSource();
            dataSource.setUrl("jdbc:sqlite:" + directory.resolve("zhida.db"));
            initialize();
        } catch (IOException | SQLException exception) {
            throw new IllegalStateException("无法初始化本地会话数据库", exception);
        }
    }

    public synchronized ConversationSnapshot load() {
        Map<String, ConversationBuilder> conversations = new LinkedHashMap<>();
        String conversationSql = """
                SELECT id, title, provider, model, created_at, updated_at
                FROM conversations ORDER BY updated_at DESC, rowid DESC
                """;
        String messageSql = """
                SELECT id, conversation_id, role, content, provider, model, created_at, status
                FROM messages ORDER BY conversation_id, position
                """;
        try (Connection connection = connection();
             Statement conversationStatement = connection.createStatement();
             ResultSet conversationRows = conversationStatement.executeQuery(conversationSql)) {
            while (conversationRows.next()) {
                ConversationBuilder builder = new ConversationBuilder(
                        conversationRows.getString("id"),
                        conversationRows.getString("title"),
                        valueOrEmpty(conversationRows.getString("provider")),
                        valueOrEmpty(conversationRows.getString("model")),
                        conversationRows.getString("created_at"),
                        conversationRows.getString("updated_at"));
                conversations.put(builder.id, builder);
            }
            try (Statement messageStatement = connection.createStatement();
                 ResultSet messageRows = messageStatement.executeQuery(messageSql)) {
                while (messageRows.next()) {
                    ConversationBuilder owner = conversations.get(messageRows.getString("conversation_id"));
                    if (owner == null) {
                        continue;
                    }
                    String status = messageRows.getString("status");
                    if ("streaming".equals(status)) {
                        status = "stopped";
                    }
                    owner.messages.add(new StoredMessage(
                            messageRows.getString("id"),
                            messageRows.getString("role"),
                            messageRows.getString("content"),
                            valueOrEmpty(messageRows.getString("provider")),
                            valueOrEmpty(messageRows.getString("model")),
                            messageRows.getString("created_at"),
                            status));
                }
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("无法读取本地会话数据库", exception);
        }
        return new ConversationSnapshot(conversations.values().stream().map(ConversationBuilder::build).toList());
    }

    public synchronized void replace(ConversationSnapshot snapshot) {
        String now = Instant.now().toString();
        String insertConversation = """
                INSERT INTO conversations(id, title, provider, model, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """;
        String insertMessage = """
                INSERT INTO messages(id, conversation_id, position, role, content, provider, model, created_at, status)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (Connection connection = connection()) {
            connection.setAutoCommit(false);
            try (Statement delete = connection.createStatement();
                 PreparedStatement conversations = connection.prepareStatement(insertConversation);
                 PreparedStatement messages = connection.prepareStatement(insertMessage)) {
                delete.executeUpdate("DELETE FROM conversations");
                for (StoredConversation conversation : snapshot.conversations()) {
                    String createdAt = valueOr(conversation.createdAt(), now);
                    conversations.setString(1, conversation.id());
                    conversations.setString(2, valueOr(conversation.title(), "新建对话"));
                    conversations.setString(3, valueOrEmpty(conversation.provider()));
                    conversations.setString(4, valueOrEmpty(conversation.model()));
                    conversations.setString(5, createdAt);
                    conversations.setString(6, valueOr(conversation.updatedAt(), createdAt));
                    conversations.executeUpdate();

                    int position = 0;
                    for (StoredMessage message : conversation.messages()) {
                        messages.setString(1, message.id());
                        messages.setString(2, conversation.id());
                        messages.setInt(3, position++);
                        messages.setString(4, message.role());
                        messages.setString(5, message.content());
                        messages.setString(6, valueOrEmpty(message.provider()));
                        messages.setString(7, valueOrEmpty(message.model()));
                        messages.setString(8, valueOr(message.createdAt(), now));
                        messages.setString(9, valueOr(message.status(), "complete"));
                        messages.executeUpdate();
                    }
                }
                connection.commit();
            } catch (SQLException exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("无法保存本地会话数据库", exception);
        }
    }

    private void initialize() throws SQLException {
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            try (ResultSet version = statement.executeQuery("PRAGMA user_version")) {
                if (version.next() && version.getInt(1) > ConversationSnapshot.CURRENT_VERSION) {
                    throw new SQLException("数据库版本高于当前应用支持的版本");
                }
            }
            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS conversations (
                        id TEXT PRIMARY KEY,
                        title TEXT NOT NULL,
                        provider TEXT NOT NULL DEFAULT '',
                        model TEXT NOT NULL DEFAULT '',
                        created_at TEXT NOT NULL,
                        updated_at TEXT NOT NULL
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS messages (
                        id TEXT PRIMARY KEY,
                        conversation_id TEXT NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
                        position INTEGER NOT NULL,
                        role TEXT NOT NULL CHECK(role IN ('user', 'assistant')),
                        content TEXT NOT NULL,
                        provider TEXT NOT NULL DEFAULT '',
                        model TEXT NOT NULL DEFAULT '',
                        created_at TEXT NOT NULL,
                        status TEXT NOT NULL DEFAULT 'complete',
                        UNIQUE(conversation_id, position)
                    )
                    """);
            statement.execute("CREATE INDEX IF NOT EXISTS idx_conversations_updated ON conversations(updated_at DESC)");
            statement.execute("PRAGMA user_version=" + ConversationSnapshot.CURRENT_VERSION);
        }
    }

    private Connection connection() throws SQLException {
        Connection connection = dataSource.getConnection();
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys=ON");
            statement.execute("PRAGMA busy_timeout=5000");
        }
        return connection;
    }

    private String valueOrEmpty(String value) {
        return value == null ? "" : value;
    }

    private String valueOr(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static final class ConversationBuilder {
        private final String id;
        private final String title;
        private final String provider;
        private final String model;
        private final String createdAt;
        private final String updatedAt;
        private final List<StoredMessage> messages = new ArrayList<>();

        private ConversationBuilder(String id, String title, String provider, String model,
                                    String createdAt, String updatedAt) {
            this.id = id;
            this.title = title;
            this.provider = provider;
            this.model = model;
            this.createdAt = createdAt;
            this.updatedAt = updatedAt;
        }

        private StoredConversation build() {
            return new StoredConversation(id, title, provider, model, createdAt, updatedAt, List.copyOf(messages));
        }
    }
}
