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

package com.nageoffer.ai.ragent.rag.core.retrieve.channel;

import cn.hutool.core.collection.CollUtil;
import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;
import com.nageoffer.ai.ragent.rag.config.SearchChannelProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
@ConditionalOnProperty(name = "rag.vector.type", havingValue = "pg")
public class KeywordPgSearchChannel implements SearchChannel {

    private final SearchChannelProperties properties;
    private final JdbcTemplate jdbcTemplate;

    public KeywordPgSearchChannel(SearchChannelProperties properties,
                                  JdbcTemplate jdbcTemplate) {
        this.properties = properties;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public String getName() {
        return "KeywordPgSearch";
    }

    @Override
    public int getPriority() {
        return 5;
    }

    @Override
    public boolean isEnabled(SearchContext context) {
        if (!properties.getChannels().getKeywordPg().isEnabled()) {
            return false;
        }
        return context.getMainQuestion() != null
                && !context.getMainQuestion().isBlank();
    }

    @Override
    public SearchChannelResult search(SearchContext context) {
        long startTime = System.currentTimeMillis();

        try {
            String question = context.getMainQuestion();
            String tsConfig = resolveTsConfig();
            int topK = context.getTopK() * properties.getChannels().getKeywordPg().getTopKMultiplier();

            log.info("执行 PG 关键词检索，问题：{}，分词配置：{}，TopK：{}", question, tsConfig, topK);

            List<RetrievedChunk> chunks = doFullTextSearch(question, tsConfig, topK);

            long latency = System.currentTimeMillis() - startTime;
            log.info("PG 关键词检索完成，检索到 {} 个 Chunk，耗时 {}ms", chunks.size(), latency);

            return SearchChannelResult.builder()
                    .channelType(SearchChannelType.KEYWORD_PG)
                    .channelName(getName())
                    .chunks(chunks)
                    .latencyMs(latency)
                    .build();

        } catch (Exception e) {
            log.error("PG 关键词检索失败", e);
            return SearchChannelResult.builder()
                    .channelType(SearchChannelType.KEYWORD_PG)
                    .channelName(getName())
                    .chunks(List.of())
                    .latencyMs(System.currentTimeMillis() - startTime)
                    .build();
        }
    }

    @Override
    public SearchChannelType getType() {
        return SearchChannelType.KEYWORD_PG;
    }

    private List<RetrievedChunk> doFullTextSearch(String question, String tsConfig, int topK) {
        String sql = "SELECT id, content, ts_rank(tsv, query) AS score "
                + "FROM t_knowledge_vector, plainto_tsquery(?, ?) query "
                + "WHERE tsv @@ query "
                + "ORDER BY score DESC LIMIT ?";

        //noinspection SqlDialectInspection,SqlNoDataSourceInspection
        List<RetrievedChunk> results = jdbcTemplate.query(sql,
                (rs, rowNum) -> RetrievedChunk.builder()
                        .id(rs.getString("id"))
                        .text(rs.getString("content"))
                        .score(rs.getFloat("score"))
                        .build(),
                tsConfig, question, topK
        );

        if (CollUtil.isEmpty(results)) {
            String fallbackSql = "SELECT id, content, ts_rank(tsv, query) AS score "
                    + "FROM t_knowledge_vector, plainto_tsquery('simple', ?) query "
                    + "WHERE tsv @@ query "
                    + "ORDER BY score DESC LIMIT ?";

            log.info("jiebacfg 分词无结果，尝试使用 simple 配置降级检索");

            //noinspection SqlDialectInspection,SqlNoDataSourceInspection
            results = jdbcTemplate.query(fallbackSql,
                    (rs, rowNum) -> RetrievedChunk.builder()
                            .id(rs.getString("id"))
                            .text(rs.getString("content"))
                            .score(rs.getFloat("score"))
                            .build(),
                    question, topK
            );
        }

        return results;
    }

    private String resolveTsConfig() {
        return properties.getChannels().getKeywordPg().getTsConfigName();
    }
}
