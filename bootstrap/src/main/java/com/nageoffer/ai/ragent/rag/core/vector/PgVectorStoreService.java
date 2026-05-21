/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.nageoffer.ai.ragent.rag.core.vector;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.core.chunk.VectorChunk;
import com.nageoffer.ai.ragent.rag.config.SearchChannelProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "rag.vector.type", havingValue = "pg")
public class PgVectorStoreService implements VectorStoreService {

    private static final String INSERT_WITH_FTS =
            "INSERT INTO t_knowledge_vector (id, content, metadata, embedding, tsv) VALUES (?, ?, ?::jsonb, ?::vector, to_tsvector(?::regconfig, ?))";
    private static final String INSERT_WITHOUT_FTS =
            "INSERT INTO t_knowledge_vector (id, content, metadata, embedding) VALUES (?, ?, ?::jsonb, ?::vector)";
    private static final String UPSERT_WITH_FTS =
            "INSERT INTO t_knowledge_vector (id, content, metadata, embedding, tsv) VALUES (?, ?, ?::jsonb, ?::vector, to_tsvector(?::regconfig, ?)) " +
                    "ON CONFLICT (id) DO UPDATE SET content = EXCLUDED.content, metadata = EXCLUDED.metadata, embedding = EXCLUDED.embedding, tsv = EXCLUDED.tsv";
    private static final String UPSERT_WITHOUT_FTS =
            "INSERT INTO t_knowledge_vector (id, content, metadata, embedding) VALUES (?, ?, ?::jsonb, ?::vector) " +
                    "ON CONFLICT (id) DO UPDATE SET content = EXCLUDED.content, metadata = EXCLUDED.metadata, embedding = EXCLUDED.embedding";

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final SearchChannelProperties searchChannelProperties;

    private volatile Boolean ftsAvailable;

    @Override
    public void indexDocumentChunks(String collectionName, String docId, List<VectorChunk> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return;
        }

        if (isFtsAvailable()) {
            String tsConfig = resolveTsConfig();
            //noinspection SqlDialectInspection,SqlNoDataSourceInspection
            jdbcTemplate.batchUpdate(INSERT_WITH_FTS,
                    chunks, chunks.size(), (ps, chunk) -> {
                        ps.setString(1, chunk.getChunkId());
                        ps.setString(2, chunk.getContent());
                        ps.setString(3, buildMetadataJson(collectionName, docId, chunk));
                        ps.setString(4, toVectorLiteral(chunk.getEmbedding()));
                        ps.setString(5, tsConfig);
                        ps.setString(6, chunk.getContent() == null ? "" : chunk.getContent());
                    });
        } else {
            //noinspection SqlDialectInspection,SqlNoDataSourceInspection
            jdbcTemplate.batchUpdate(INSERT_WITHOUT_FTS,
                    chunks, chunks.size(), (ps, chunk) -> {
                        ps.setString(1, chunk.getChunkId());
                        ps.setString(2, chunk.getContent());
                        ps.setString(3, buildMetadataJson(collectionName, docId, chunk));
                        ps.setString(4, toVectorLiteral(chunk.getEmbedding()));
                    });
        }

        log.info("批量写入向量到 PostgreSQL，collectionName={}, docId={}, count={}, fts={}",
                collectionName, docId, chunks.size(), isFtsAvailable());
    }

    @Override
    public void deleteDocumentVectors(String collectionName, String docId) {
        // noinspection SqlDialectInspection,SqlNoDataSourceInspection
        int deleted = jdbcTemplate.update(
                "DELETE FROM t_knowledge_vector WHERE metadata->>'collection_name' = ? AND metadata->>'doc_id' = ?",
                collectionName, docId);
        log.info("删除文档向量，collectionName={}, docId={}, deleted={}", collectionName, docId, deleted);
    }

    @Override
    public void deleteChunkById(String collectionName, String chunkId) {
        // noinspection SqlDialectInspection,SqlNoDataSourceInspection
        jdbcTemplate.update("DELETE FROM t_knowledge_vector WHERE id = ?", chunkId);
    }

    @Override
    public void deleteChunksByIds(String collectionName, List<String> chunkIds) {
        if (chunkIds == null || chunkIds.isEmpty()) {
            return;
        }
        String placeholders = chunkIds.stream().map(id -> "?").collect(java.util.stream.Collectors.joining(", "));
        // noinspection SqlDialectInspection,SqlNoDataSourceInspection
        int deleted = jdbcTemplate.update("DELETE FROM t_knowledge_vector WHERE id IN (" + placeholders + ")", chunkIds.toArray());
        log.info("批量删除 chunk 向量，collectionName={}, count={}, deleted={}", collectionName, chunkIds.size(), deleted);
    }

    @Override
    public void updateChunk(String collectionName, String docId, VectorChunk chunk) {
        if (isFtsAvailable()) {
            String tsConfig = resolveTsConfig();
            //noinspection SqlDialectInspection,SqlNoDataSourceInspection
            jdbcTemplate.update(UPSERT_WITH_FTS,
                    chunk.getChunkId(),
                    chunk.getContent(),
                    buildMetadataJson(collectionName, docId, chunk),
                    toVectorLiteral(chunk.getEmbedding()),
                    tsConfig,
                    chunk.getContent() == null ? "" : chunk.getContent()
            );
        } else {
            //noinspection SqlDialectInspection,SqlNoDataSourceInspection
            jdbcTemplate.update(UPSERT_WITHOUT_FTS,
                    chunk.getChunkId(),
                    chunk.getContent(),
                    buildMetadataJson(collectionName, docId, chunk),
                    toVectorLiteral(chunk.getEmbedding())
            );
        }
    }

    private String buildMetadataJson(String collectionName, String docId, VectorChunk chunk) {
        Map<String, Object> meta = new LinkedHashMap<>();
        if (chunk.getMetadata() != null) {
            meta.putAll(chunk.getMetadata());
        }

        meta.put("collection_name", collectionName);
        meta.put("doc_id", docId);
        meta.put("chunk_index", chunk.getIndex());
        try {
            return objectMapper.writeValueAsString(meta);
        } catch (Exception e) {
            throw new RuntimeException("元数据序列化失败", e);
        }
    }

    private String toVectorLiteral(float[] embedding) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < embedding.length; i++) {
            if (i > 0) sb.append(",");
            sb.append(embedding[i]);
        }
        return sb.append("]").toString();
    }

    private String resolveTsConfig() {
        return searchChannelProperties.getChannels().getKeywordPg().getTsConfigName();
    }

    private boolean isFtsAvailable() {
        if (ftsAvailable != null) {
            return ftsAvailable;
        }
        try {
            //noinspection SqlDialectInspection,SqlNoDataSourceInspection
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.columns WHERE table_name = 't_knowledge_vector' AND column_name = 'tsv'",
                    Integer.class);
            ftsAvailable = count != null && count > 0;
            log.info("FTS tsvector 列可用，入库将同步写入全文检索索引");
        } catch (Exception e) {
            ftsAvailable = false;
            log.warn("FTS tsvector 列不可用（{}），入库将跳过全文检索索引，请执行 upgrade_v1.2_to_v1.3.sql",
                    e.getMessage());
        }
        return ftsAvailable;
    }
}
