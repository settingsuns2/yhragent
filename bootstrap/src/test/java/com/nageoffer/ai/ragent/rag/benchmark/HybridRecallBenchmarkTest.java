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

package com.nageoffer.ai.ragent.rag.benchmark;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.nageoffer.ai.ragent.framework.convention.ChatMessage;
import com.nageoffer.ai.ragent.framework.convention.ChatRequest;
import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;
import com.nageoffer.ai.ragent.infra.chat.LLMService;
import com.nageoffer.ai.ragent.infra.rerank.RerankService;
import com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeBaseDO;
import com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeChunkDO;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeBaseMapper;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeChunkMapper;
import com.nageoffer.ai.ragent.rag.core.retrieve.RetrieveRequest;
import com.nageoffer.ai.ragent.rag.core.retrieve.RetrieverService;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import lombok.Builder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@SpringBootTest
@RequiredArgsConstructor(onConstructor = @__(@Autowired))
public class HybridRecallBenchmarkTest {

    private final RetrieverService retrieverService;
    private final LLMService llmService;
    private final RerankService rerankService;
    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final KnowledgeChunkMapper knowledgeChunkMapper;
    private final JdbcTemplate jdbcTemplate;

    private static final int RETRIEVE_K = 10;
    private static final int RERANK_TOP_N = 5;
    private static final String TS_CONFIG = "zh";

    private static final List<CollectionTestCase> TEST_CASES = buildTestCases();

    @Test
    void benchmarkHybridRecall() {
        System.out.println("=".repeat(110));
        System.out.println("  混合检索 + Rerank 召回率评测");
        System.out.printf("  向量 Top%d + BM25 Top%d → RRF 融合 Top%d → Rerank Top%d → LLM-as-Judge%n",
                RETRIEVE_K, RETRIEVE_K, RETRIEVE_K, RERANK_TOP_N);
        System.out.println("  对比：仅向量 / BM25 / 混合(RRF) / 混合+Rerank");
        System.out.println("=".repeat(110));
        System.out.println();

        List<CollectionCompareResult> allResults = new ArrayList<>();

        for (CollectionTestCase ctc : TEST_CASES) {
            System.out.println("=".repeat(110));
            System.out.println("  Collection: " + ctc.collectionName + " (" + ctc.queries.size() + " questions)");
            System.out.println("=".repeat(110));

            List<String> allChunkContents = loadAllChunks(ctc.collectionName);
            if (allChunkContents.isEmpty()) {
                System.out.println("  [SKIP] 无 chunks");
                System.out.println();
                continue;
            }

            System.out.println("  总 chunk 数: " + allChunkContents.size());
            System.out.println();

            List<QueryCompareResult> queryResults = new ArrayList<>();

            for (String query : ctc.queries) {
                QueryCompareResult result = evaluateQuery(query, ctc.collectionName, allChunkContents);
                queryResults.add(result);
                printQueryCompareResult(result);
            }

            CollectionCompareResult cr = CollectionCompareResult.builder()
                    .collectionName(ctc.collectionName)
                    .results(queryResults)
                    .totalChunks(allChunkContents.size())
                    .build();
            allResults.add(cr);

            printCollectionCompare(cr);
            System.out.println();
        }

        printGlobalCompare(allResults);
    }

    private QueryCompareResult evaluateQuery(String query, String collectionName, List<String> allChunkContents) {
        long t0 = System.currentTimeMillis();
        List<RetrievedChunk> vectorChunks = vectorSearch(query, collectionName, RETRIEVE_K);
        long vectorMs = System.currentTimeMillis() - t0;

        long t1 = System.currentTimeMillis();
        List<RetrievedChunk> bm25Chunks = bm25Search(query, collectionName, RETRIEVE_K);
        long keywordMs = System.currentTimeMillis() - t1;

        long t2 = System.currentTimeMillis();
        List<RetrievedChunk> fusedChunks = rrfFuse(vectorChunks, bm25Chunks, RETRIEVE_K);
        long fusionMs = System.currentTimeMillis() - t2;

        long t3 = System.currentTimeMillis();
        List<RetrievedChunk> rerankedChunks = doRerank(query, fusedChunks, RERANK_TOP_N);
        long rerankMs = System.currentTimeMillis() - t3;

        long t4 = System.currentTimeMillis();
        JudgeResult vectorJudge = llmJudge(query, allChunkContents, vectorChunks);
        long vectorJudgeMs = System.currentTimeMillis() - t4;

        int totalRelevant = vectorJudge.totalRelevant;

        long t5 = System.currentTimeMillis();
        JudgeResult keywordJudge = llmJudgeWithFixedTotal(query, allChunkContents, bm25Chunks, totalRelevant);
        long keywordJudgeMs = System.currentTimeMillis() - t5;

        long t6 = System.currentTimeMillis();
        JudgeResult fusedJudge = llmJudgeWithFixedTotal(query, allChunkContents, fusedChunks, totalRelevant);
        long fusedJudgeMs = System.currentTimeMillis() - t6;

        long t7 = System.currentTimeMillis();
        JudgeResult rerankedJudge = llmJudgeWithFixedTotal(query, allChunkContents, rerankedChunks, totalRelevant);
        long rerankedJudgeMs = System.currentTimeMillis() - t7;

        return QueryCompareResult.builder()
                .query(query)
                .collectionName(collectionName)
                .vectorChunks(vectorChunks.size())
                .keywordChunks(bm25Chunks.size())
                .fusedChunks(fusedChunks.size())
                .rerankedChunks(rerankedChunks.size())
                .vectorOnly(vectorJudge)
                .keywordOnly(keywordJudge)
                .fused(fusedJudge)
                .reranked(rerankedJudge)
                .vectorRecall(calcRecall(vectorJudge))
                .keywordRecall(calcRecall(keywordJudge))
                .fusedRecall(calcRecall(fusedJudge))
                .rerankedRecall(calcRecall(rerankedJudge))
                .vectorPrecision(calcPrecision(vectorJudge, vectorChunks.size()))
                .keywordPrecision(calcPrecision(keywordJudge, bm25Chunks.size()))
                .fusedPrecision(calcPrecision(fusedJudge, fusedChunks.size()))
                .rerankedPrecision(calcPrecision(rerankedJudge, rerankedChunks.size()))
                .vectorHit(vectorJudge.retrievedRelevant > 0)
                .keywordHit(keywordJudge.retrievedRelevant > 0)
                .fusedHit(fusedJudge.retrievedRelevant > 0)
                .rerankedHit(rerankedJudge.retrievedRelevant > 0)
                .vectorMs(vectorMs)
                .keywordMs(keywordMs)
                .fusionMs(fusionMs)
                .rerankMs(rerankMs)
                .vectorJudgeMs(vectorJudgeMs)
                .keywordJudgeMs(keywordJudgeMs)
                .fusedJudgeMs(fusedJudgeMs)
                .rerankedJudgeMs(rerankedJudgeMs)
                .build();
    }

    private List<RetrievedChunk> doRerank(String query, List<RetrievedChunk> candidates, int topN) {
        if (candidates.isEmpty()) return List.of();
        try {
            return rerankService.rerank(query, candidates, topN);
        } catch (Exception e) {
            log.error("Rerank 失败: query={}", query, e);
            return candidates.stream().limit(topN).toList();
        }
    }

    private List<RetrievedChunk> vectorSearch(String query, String collectionName, int topK) {
        try {
            return retrieverService.retrieve(
                    RetrieveRequest.builder()
                            .query(query)
                            .topK(topK)
                            .collectionName(collectionName)
                            .build()
            );
        } catch (Exception e) {
            log.error("向量检索失败: query={}", query, e);
            return List.of();
        }
    }

    private List<RetrievedChunk> bm25Search(String query, String collectionName, int topK) {
        List<String> terms = tokenizeZh(query);
        if (terms.isEmpty()) {
            return List.of();
        }

        String orConditions = terms.stream()
                .map(t -> "tsv @@ plainto_tsquery('" + TS_CONFIG + "'::regconfig, '" + t.replace("'", "''") + "')")
                .collect(Collectors.joining(" OR "));

        String sql = "SELECT id, content, ts_rank(tsv, plainto_tsquery(?::regconfig, ?)) AS score "
                + "FROM t_knowledge_vector "
                + "WHERE (" + orConditions + ") AND metadata->>'collection_name' = ? "
                + "ORDER BY score DESC LIMIT ?";

        try {
            return jdbcTemplate.query(sql,
                    (rs, rowNum) -> RetrievedChunk.builder()
                            .id(rs.getString("id"))
                            .text(rs.getString("content"))
                            .score(rs.getFloat("score"))
                            .build(),
                    TS_CONFIG, query, collectionName, topK);
        } catch (Exception e) {
            log.error("BM25检索失败: query={}", query, e);
            return List.of();
        }
    }

    private List<String> tokenizeZh(String query) {
        try {
            String sql = "SELECT to_tsvector(?::regconfig, ?)::text";
            String raw = jdbcTemplate.queryForObject(sql, String.class, TS_CONFIG, query);
            if (raw == null || raw.isBlank() || raw.equals("''") || raw.equals("' '")) {
                return List.of();
            }
            return Arrays.stream(raw.split(" "))
                    .map(t -> t.split(":")[0].replace("'", ""))
                    .filter(t -> !t.isBlank())
                    .distinct()
                    .toList();
        } catch (Exception e) {
            log.warn("中文分词失败: {}", e.getMessage());
            return List.of();
        }
    }

    private List<RetrievedChunk> rrfFuse(List<RetrievedChunk> vectorChunks, List<RetrievedChunk> keywordChunks, int topK) {
        int k = 60;
        Map<String, Double> scores = new LinkedHashMap<>();
        Map<String, RetrievedChunk> chunkMap = new LinkedHashMap<>();

        for (int i = 0; i < vectorChunks.size(); i++) {
            RetrievedChunk c = vectorChunks.get(i);
            chunkMap.put(c.getId(), c);
            scores.merge(c.getId(), 1.0 / (k + i + 1), Double::sum);
        }

        for (int i = 0; i < keywordChunks.size(); i++) {
            RetrievedChunk c = keywordChunks.get(i);
            chunkMap.put(c.getId(), c);
            scores.merge(c.getId(), 1.0 / (k + i + 1), Double::sum);
        }

        return scores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(topK)
                .map(e -> {
                    RetrievedChunk original = chunkMap.get(e.getKey());
                    return RetrievedChunk.builder()
                            .id(original.getId())
                            .text(original.getText())
                            .score(e.getValue().floatValue())
                            .build();
                })
                .toList();
    }

    private JudgeResult llmJudge(String query, List<String> allChunks, List<RetrievedChunk> retrievedChunks) {
        return doLlmJudge(query, allChunks, retrievedChunks, null);
    }

    private JudgeResult llmJudgeWithFixedTotal(String query, List<String> allChunks, List<RetrievedChunk> retrievedChunks, int fixedTotalRelevant) {
        return doLlmJudge(query, allChunks, retrievedChunks, fixedTotalRelevant);
    }

    private JudgeResult doLlmJudge(String query, List<String> allChunks, List<RetrievedChunk> retrievedChunks, Integer fixedTotalRelevant) {
        List<String> retrievedContents = retrievedChunks.stream()
                .map(RetrievedChunk::getText)
                .toList();

        if (retrievedContents.isEmpty()) {
            return new JudgeResult(0, 0, "检索结果为空");
        }

        StringBuilder sb = new StringBuilder();
        sb.append("你是 RAG 检索质量评估专家。\n\n");
        sb.append("## 用户问题\n").append(query).append("\n\n");

        if (fixedTotalRelevant == null) {
            sb.append("## 全部 Chunks\n");
            for (int i = 0; i < allChunks.size(); i++) {
                sb.append("[").append(i).append("] ").append(truncate(allChunks.get(i), 120)).append("\n");
            }
            sb.append("\n");
        } else {
            sb.append("## 已知信息\n");
            sb.append("全库中与问题相关的 chunk 总数已经确定为 ").append(fixedTotalRelevant).append(" 个。\n\n");
        }

        sb.append("## 检索返回的 Chunks\n");
        for (int i = 0; i < retrievedContents.size(); i++) {
            sb.append("[").append(i).append("] ").append(truncate(retrievedContents.get(i), 120)).append("\n");
        }
        sb.append("\n");

        if (fixedTotalRelevant == null) {
            sb.append("## 任务\n");
            sb.append("1. 从「全部 Chunks」找出与问题语义相关的 chunk 序号列表（relevant_all）\n");
            sb.append("2. 从「检索返回的 Chunks」找出与问题语义相关的 chunk 序号列表（relevant_retrieved）\n");
            sb.append("3. 判断标准：chunk 包含能回答问题的关键信息即为相关\n\n");
            sb.append("严格输出 JSON：{\"relevant_all\": [0,1,3], \"relevant_retrieved\": [0,2], \"reasoning\": \"简述依据\"}\n");
        } else {
            sb.append("## 任务\n");
            sb.append("从「检索返回的 Chunks」找出与问题语义相关的 chunk 序号列表（relevant_retrieved）\n");
            sb.append("判断标准：chunk 包含能回答问题的关键信息即为相关\n\n");
            sb.append("严格输出 JSON：{\"relevant_retrieved\": [0,2], \"reasoning\": \"简述依据\"}\n");
        }

        ChatRequest request = ChatRequest.builder()
                .messages(List.of(
                        ChatMessage.system("你是精确的 RAG 检索评估器，只输出 JSON。"),
                        ChatMessage.user(sb.toString())
                ))
                .temperature(0.0)
                .topP(0.1)
                .thinking(false)
                .build();

        try {
            String raw = llmService.chat(request);
            return parseJudgeResponse(raw, fixedTotalRelevant);
        } catch (Exception e) {
            log.error("LLM Judge 失败: query={}", query, e);
            return new JudgeResult(fixedTotalRelevant != null ? fixedTotalRelevant : 0, 0, "LLM调用失败");
        }
    }

    private JudgeResult parseJudgeResponse(String raw, Integer fixedTotalRelevant) {
        try {
            String cleaned = raw.trim();
            if (cleaned.startsWith("```")) {
                cleaned = cleaned.replaceAll("```json\\s*", "").replaceAll("```\\s*", "").trim();
            }
            JsonObject obj = JsonParser.parseString(cleaned).getAsJsonObject();
            int totalRelevant = fixedTotalRelevant != null
                    ? fixedTotalRelevant
                    : (obj.has("relevant_all") ? obj.getAsJsonArray("relevant_all").size() : 0);
            int retrievedRelevant = obj.has("relevant_retrieved") ? obj.getAsJsonArray("relevant_retrieved").size() : 0;
            String reasoning = obj.has("reasoning") ? obj.get("reasoning").getAsString() : "";
            return new JudgeResult(totalRelevant, retrievedRelevant, reasoning);
        } catch (Exception e) {
            log.warn("解析 LLM Judge 响应失败: {}", raw, e);
            return new JudgeResult(0, 0, "解析失败");
        }
    }

    private double calcRecall(JudgeResult j) {
        return j.totalRelevant > 0 ? (double) j.retrievedRelevant / j.totalRelevant : 0.0;
    }

    private double calcPrecision(JudgeResult j, int totalReturned) {
        return totalReturned > 0 ? (double) j.retrievedRelevant / totalReturned : 0.0;
    }

    private List<String> loadAllChunks(String collectionName) {
        KnowledgeBaseDO kb = knowledgeBaseMapper.selectOne(
                Wrappers.lambdaQuery(KnowledgeBaseDO.class)
                        .eq(KnowledgeBaseDO::getCollectionName, collectionName)
                        .eq(KnowledgeBaseDO::getDeleted, 0)
        );
        if (kb == null) return List.of();

        List<KnowledgeChunkDO> chunks = knowledgeChunkMapper.selectList(
                Wrappers.lambdaQuery(KnowledgeChunkDO.class)
                        .eq(KnowledgeChunkDO::getKbId, kb.getId())
                        .eq(KnowledgeChunkDO::getDeleted, 0)
                        .eq(KnowledgeChunkDO::getEnabled, 1)
                        .orderByAsc(KnowledgeChunkDO::getChunkIndex)
        );
        return chunks.stream().map(KnowledgeChunkDO::getContent).toList();
    }

    private void printQueryCompareResult(QueryCompareResult r) {
        System.out.printf("  Q: %s%n", r.query);
        System.out.printf("    向量:      %2d chunks (%4dms) | R=%.4f P=%.4f | Hit=%-5s%n",
                r.vectorChunks, r.vectorMs, r.vectorRecall, r.vectorPrecision, r.vectorHit);
        System.out.printf("    BM25:      %2d chunks (%4dms) | R=%.4f P=%.4f | Hit=%-5s%n",
                r.keywordChunks, r.keywordMs, r.keywordRecall, r.keywordPrecision, r.keywordHit);
        System.out.printf("    混合(RRF): %2d chunks (%4dms) | R=%.4f P=%.4f | Hit=%-5s%n",
                r.fusedChunks, r.fusionMs, r.fusedRecall, r.fusedPrecision, r.fusedHit);
        System.out.printf("    混合+Rerank: %d chunks (%4dms) | R=%.4f P=%.4f | Hit=%-5s%n",
                r.rerankedChunks, r.rerankMs, r.rerankedRecall, r.rerankedPrecision, r.rerankedHit);
        System.out.printf("    最优: %s%n%n", bestMode(r));
    }

    private String bestMode(QueryCompareResult r) {
        double max = Math.max(r.vectorRecall, Math.max(r.keywordRecall, Math.max(r.fusedRecall, r.rerankedRecall)));
        List<String> winners = new ArrayList<>();
        if (r.vectorRecall == max) winners.add("向量");
        if (r.keywordRecall == max) winners.add("BM25");
        if (r.fusedRecall == max) winners.add("混合");
        if (r.rerankedRecall == max) winners.add("混合+Rerank");
        return String.join("=", winners) + " (R=" + String.format("%.4f", max) + ")";
    }

    private void printCollectionCompare(CollectionCompareResult cr) {
        System.out.println("  " + "-".repeat(80));
        System.out.printf("  [%s] 汇总 (chunks=%d, queries=%d)%n",
                cr.collectionName, cr.totalChunks, cr.results.size());

        double avgVecR = cr.results.stream().mapToDouble(r -> r.vectorRecall).average().orElse(0);
        double avgKwR = cr.results.stream().mapToDouble(r -> r.keywordRecall).average().orElse(0);
        double avgFusR = cr.results.stream().mapToDouble(r -> r.fusedRecall).average().orElse(0);
        double avgRerR = cr.results.stream().mapToDouble(r -> r.rerankedRecall).average().orElse(0);

        double avgVecP = cr.results.stream().mapToDouble(r -> r.vectorPrecision).average().orElse(0);
        double avgKwP = cr.results.stream().mapToDouble(r -> r.keywordPrecision).average().orElse(0);
        double avgFusP = cr.results.stream().mapToDouble(r -> r.fusedPrecision).average().orElse(0);
        double avgRerP = cr.results.stream().mapToDouble(r -> r.rerankedPrecision).average().orElse(0);

        double vecHR = (double) cr.results.stream().filter(r -> r.vectorHit).count() / cr.results.size();
        double kwHR = (double) cr.results.stream().filter(r -> r.keywordHit).count() / cr.results.size();
        double fusHR = (double) cr.results.stream().filter(r -> r.fusedHit).count() / cr.results.size();
        double rerHR = (double) cr.results.stream().filter(r -> r.rerankedHit).count() / cr.results.size();

        System.out.printf("    %-14s AvgRecall=%.4f  AvgPrec=%.4f  HitRate=%.4f%n", "向量", avgVecR, avgVecP, vecHR);
        System.out.printf("    %-14s AvgRecall=%.4f  AvgPrec=%.4f  HitRate=%.4f%n", "BM25", avgKwR, avgKwP, kwHR);
        System.out.printf("    %-14s AvgRecall=%.4f  AvgPrec=%.4f  HitRate=%.4f%n", "混合(RRF)", avgFusR, avgFusP, fusHR);
        System.out.printf("    %-14s AvgRecall=%.4f  AvgPrec=%.4f  HitRate=%.4f%n", "混合+Rerank", avgRerR, avgRerP, rerHR);

        long vecWins = countWins(cr, QueryCompareResult::vectorRecall);
        long kwWins = countWins(cr, QueryCompareResult::keywordRecall);
        long fusWins = countWins(cr, QueryCompareResult::fusedRecall);
        long rerWins = countWins(cr, QueryCompareResult::rerankedRecall);
        System.out.printf("    最优次数: 向量=%d, BM25=%d, 混合=%d, 混合+Rerank=%d%n", vecWins, kwWins, fusWins, rerWins);
    }

    private long countWins(CollectionCompareResult cr, java.util.function.ToDoubleFunction<QueryCompareResult> extractor) {
        return cr.results.stream().filter(r -> {
            double target = extractor.applyAsDouble(r);
            return target >= r.vectorRecall && target >= r.keywordRecall
                    && target >= r.fusedRecall && target >= r.rerankedRecall;
        }).count();
    }

    private void printGlobalCompare(List<CollectionCompareResult> allResults) {
        List<QueryCompareResult> all = allResults.stream()
                .flatMap(cr -> cr.results.stream())
                .toList();

        if (all.isEmpty()) {
            System.out.println("无测试结果");
            return;
        }

        double avgVecR = all.stream().mapToDouble(r -> r.vectorRecall).average().orElse(0);
        double avgKwR = all.stream().mapToDouble(r -> r.keywordRecall).average().orElse(0);
        double avgFusR = all.stream().mapToDouble(r -> r.fusedRecall).average().orElse(0);
        double avgRerR = all.stream().mapToDouble(r -> r.rerankedRecall).average().orElse(0);

        double avgVecP = all.stream().mapToDouble(r -> r.vectorPrecision).average().orElse(0);
        double avgKwP = all.stream().mapToDouble(r -> r.keywordPrecision).average().orElse(0);
        double avgFusP = all.stream().mapToDouble(r -> r.fusedPrecision).average().orElse(0);
        double avgRerP = all.stream().mapToDouble(r -> r.rerankedPrecision).average().orElse(0);

        double vecHR = (double) all.stream().filter(r -> r.vectorHit).count() / all.size();
        double kwHR = (double) all.stream().filter(r -> r.keywordHit).count() / all.size();
        double fusHR = (double) all.stream().filter(r -> r.fusedHit).count() / all.size();
        double rerHR = (double) all.stream().filter(r -> r.rerankedHit).count() / all.size();

        long vecWins = all.stream().filter(r -> r.vectorRecall >= r.keywordRecall && r.vectorRecall >= r.fusedRecall && r.vectorRecall >= r.rerankedRecall).count();
        long kwWins = all.stream().filter(r -> r.keywordRecall >= r.vectorRecall && r.keywordRecall >= r.fusedRecall && r.keywordRecall >= r.rerankedRecall).count();
        long fusWins = all.stream().filter(r -> r.fusedRecall >= r.vectorRecall && r.fusedRecall >= r.keywordRecall && r.fusedRecall >= r.rerankedRecall).count();
        long rerWins = all.stream().filter(r -> r.rerankedRecall >= r.vectorRecall && r.rerankedRecall >= r.keywordRecall && r.rerankedRecall >= r.fusedRecall).count();

        System.out.println("=".repeat(110));
        System.out.println("  全局对比汇总 (" + all.size() + " queries)");
        System.out.println("=".repeat(110));
        System.out.printf("  %-14s %10s %10s %10s %10s %10s%n",
                "模式", "Avg Recall", "Avg Prec", "Hit Rate", "最优次数", "占比");
        System.out.println("  " + "-".repeat(65));
        System.out.printf("  %-14s %10.4f %10.4f %10.4f %10d %9.1f%%%n",
                "向量", avgVecR, avgVecP, vecHR, vecWins, 100.0 * vecWins / all.size());
        System.out.printf("  %-14s %10.4f %10.4f %10.4f %10d %9.1f%%%n",
                "BM25", avgKwR, avgKwP, kwHR, kwWins, 100.0 * kwWins / all.size());
        System.out.printf("  %-14s %10.4f %10.4f %10.4f %10d %9.1f%%%n",
                "混合(RRF)", avgFusR, avgFusP, fusHR, fusWins, 100.0 * fusWins / all.size());
        System.out.printf("  %-14s %10.4f %10.4f %10.4f %10d %9.1f%%%n",
                "混合+Rerank", avgRerR, avgRerP, rerHR, rerWins, 100.0 * rerWins / all.size());
        System.out.println();

        System.out.printf("  %-30s %10s %10s %10s %10s%n", "Collection", "向量R", "BM25R", "混合R", "RerankR");
        System.out.println("  " + "-".repeat(75));
        for (CollectionCompareResult cr : allResults) {
            double vr = cr.results.stream().mapToDouble(r -> r.vectorRecall).average().orElse(0);
            double kr = cr.results.stream().mapToDouble(r -> r.keywordRecall).average().orElse(0);
            double fr = cr.results.stream().mapToDouble(r -> r.fusedRecall).average().orElse(0);
            double rr = cr.results.stream().mapToDouble(r -> r.rerankedRecall).average().orElse(0);
            System.out.printf("  %-30s %10.4f %10.4f %10.4f %10.4f%n", cr.collectionName, vr, kr, fr, rr);
        }

        System.out.println();
        double recallGain = avgRerR - Math.max(avgVecR, Math.max(avgKwR, avgFusR));
        System.out.printf("  混合+Rerank vs 最优前三 Recall 提升: %.4f (%.1f%%)%n", recallGain, recallGain * 100);
        System.out.printf("  Rerank Precision 提升: %.4f → %.4f (+%.1f%%)%n",
                avgFusP, avgRerP, (avgRerP - avgFusP) * 100);
        if (recallGain > 0.05) {
            System.out.println("  → 结论: Rerank 有明显收益");
        } else if (recallGain > 0) {
            System.out.println("  → 结论: Rerank 有轻微收益");
        } else {
            System.out.println("  → 结论: Rerank 在 Recall 上无额外收益，但 Precision 显著提升");
        }
    }

    private String truncate(String text, int maxLen) {
        if (text == null) return "";
        String flat = text.replace("\n", " ").trim();
        return flat.length() > maxLen ? flat.substring(0, maxLen) + "..." : flat;
    }

    @Builder
    private record QueryCompareResult(
            String query,
            String collectionName,
            int vectorChunks,
            int keywordChunks,
            int fusedChunks,
            int rerankedChunks,
            JudgeResult vectorOnly,
            JudgeResult keywordOnly,
            JudgeResult fused,
            JudgeResult reranked,
            double vectorRecall,
            double keywordRecall,
            double fusedRecall,
            double rerankedRecall,
            double vectorPrecision,
            double keywordPrecision,
            double fusedPrecision,
            double rerankedPrecision,
            boolean vectorHit,
            boolean keywordHit,
            boolean fusedHit,
            boolean rerankedHit,
            long vectorMs,
            long keywordMs,
            long fusionMs,
            long rerankMs,
            long vectorJudgeMs,
            long keywordJudgeMs,
            long fusedJudgeMs,
            long rerankedJudgeMs
    ) {}

    @Builder
    private record CollectionCompareResult(
            String collectionName,
            List<QueryCompareResult> results,
            int totalChunks
    ) {}

    private record JudgeResult(int totalRelevant, int retrievedRelevant, String reasoning) {}

    @Builder
    private record CollectionTestCase(String collectionName, List<String> queries) {}

    private static List<CollectionTestCase> buildTestCases() {
        List<CollectionTestCase> cases = new ArrayList<>();

        cases.add(CollectionTestCase.builder()
                .collectionName("group-it")
                .queries(List.of(
                        "忘记密码了怎么重置？",
                        "VPN连不上怎么办？",
                        "企业邮箱在手机上怎么配置？",
                        "账号被锁定了怎么办？",
                        "Mac电脑怎么删除旧的钥匙串凭据？"
                ))
                .build());

        cases.add(CollectionTestCase.builder()
                .collectionName("group-finance-invoice")
                .queries(List.of(
                        "阿里的发票抬头是什么？",
                        "腾讯的开票信息有哪些？",
                        "字节的纳税人识别号是多少？",
                        "美团的发票抬头和开户银行是什么？",
                        "快手的开票信息有哪些？"
                ))
                .build());

        cases.add(CollectionTestCase.builder()
                .collectionName("biz-oa-intro")
                .queries(List.of(
                        "OA系统主要提供哪些功能？",
                        "OA系统的核心模块有哪些？",
                        "OA系统的典型使用场景是什么？",
                        "OA系统怎么进行流程审批？",
                        "OA系统有哪些待办功能？"
                ))
                .build());

        cases.add(CollectionTestCase.builder()
                .collectionName("biz-oa-security")
                .queries(List.of(
                        "OA系统的数据权限是怎么控制的？",
                        "OA系统如何进行安全审计？",
                        "OA系统如何管理不同角色的访问权限？",
                        "OA系统有哪些数据安全规范？",
                        "OA系统的敏感数据如何保护？"
                ))
                .build());

        cases.add(CollectionTestCase.builder()
                .collectionName("biz-ins-intro")
                .queries(List.of(
                        "互联网保险系统包括哪些子系统？",
                        "保险系统的核心业务流程是什么？",
                        "保险系统有哪些业务模块？",
                        "投保流程是怎样的？",
                        "理赔系统的功能有哪些？"
                ))
                .build());

        cases.add(CollectionTestCase.builder()
                .collectionName("biz-ins-arch")
                .queries(List.of(
                        "保险系统的技术架构是怎样的？",
                        "保险系统是怎么做服务拆分的？",
                        "保险系统的数据库设计是怎样的？",
                        "保险系统用了哪些中间件？",
                        "保险系统的微服务架构是什么样的？"
                ))
                .build());

        cases.add(CollectionTestCase.builder()
                .collectionName("biz-ins-security")
                .queries(List.of(
                        "保险系统的敏感信息如何保护？",
                        "保险系统的数据脱敏规则是什么？",
                        "保险系统如何做权限控制？",
                        "保险系统的合规要求有哪些？",
                        "保险系统如何进行安全审计？"
                ))
                .build());

        return cases;
    }
}
