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
import com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeBaseDO;
import com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeChunkDO;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeBaseMapper;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeChunkMapper;
import com.nageoffer.ai.ragent.rag.core.retrieve.RetrieveRequest;
import com.nageoffer.ai.ragent.rag.core.retrieve.RetrieverService;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import lombok.Builder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@SpringBootTest
@RequiredArgsConstructor(onConstructor = @__(@Autowired))
public class LLMJudgeRecallBenchmarkTest {

    private final RetrieverService retrieverService;
    private final LLMService llmService;
    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final KnowledgeChunkMapper knowledgeChunkMapper;

    private static final int TOP_K = 10;

    private static final List<CollectionTestCase> TEST_CASES = buildTestCases();

    @Test
    @Disabled("手动触发，LLM-as-Judge 召回率评测")
    void benchmarkRecallWithLLMJudge() {
        System.out.println("=".repeat(90));
        System.out.println("  LLM-as-Judge 召回率评测");
        System.out.println("  每个 collection 的全部 chunks 作为 Ground Truth 候选池");
        System.out.println("  LLM 判断每个 chunk 与问题的相关性，计算真正召 recall");
        System.out.println("=".repeat(90));
        System.out.println();

        List<CollectionResult> allResults = new ArrayList<>();

        for (CollectionTestCase ctc : TEST_CASES) {
            System.out.println("=".repeat(90));
            System.out.println("  Collection: " + ctc.collectionName + " (" + ctc.queries.size() + " questions)");
            System.out.println("=".repeat(90));

            List<String> allChunkContents = loadAllChunks(ctc.collectionName);
            if (allChunkContents.isEmpty()) {
                System.out.println("  [SKIP] 该 collection 无 chunks，跳过");
                System.out.println();
                continue;
            }

            System.out.println("  总 chunk 数: " + allChunkContents.size());
            System.out.println();

            List<QueryJudgeResult> queryResults = new ArrayList<>();

            for (String query : ctc.queries) {
                QueryJudgeResult result = evaluateSingleQuery(query, ctc.collectionName, allChunkContents);
                queryResults.add(result);
                printQueryResult(result);
            }

            CollectionResult cr = CollectionResult.builder()
                    .collectionName(ctc.collectionName)
                    .results(queryResults)
                    .totalChunks(allChunkContents.size())
                    .build();
            allResults.add(cr);

            printCollectionSummary(cr);
            System.out.println();
        }

        printGlobalSummary(allResults);
    }

    private QueryJudgeResult evaluateSingleQuery(String query, String collectionName, List<String> allChunkContents) {
        long startRetrieval = System.currentTimeMillis();
        List<RetrievedChunk> retrieved;
        try {
            retrieved = retrieverService.retrieve(
                    RetrieveRequest.builder()
                            .query(query)
                            .topK(TOP_K)
                            .collectionName(collectionName)
                            .build()
            );
        } catch (Exception e) {
            log.error("检索失败: query={}, collection={}", query, collectionName, e);
            retrieved = List.of();
        }
        long retrievalMs = System.currentTimeMillis() - startRetrieval;

        List<String> retrievedContents = retrieved.stream()
                .map(RetrievedChunk::getText)
                .toList();

        long startJudge = System.currentTimeMillis();
        LLMJudgeResult judgeResult = llmJudge(query, allChunkContents, retrievedContents);
        long judgeMs = System.currentTimeMillis() - startJudge;

        double recall = judgeResult.totalRelevant > 0
                ? (double) judgeResult.retrievedRelevant / judgeResult.totalRelevant
                : 0.0;
        double precision = retrievedContents.size() > 0
                ? (double) judgeResult.retrievedRelevant / retrievedContents.size()
                : 0.0;

        return QueryJudgeResult.builder()
                .query(query)
                .collectionName(collectionName)
                .retrievedCount(retrievedContents.size())
                .totalRelevant(judgeResult.totalRelevant)
                .retrievedRelevant(judgeResult.retrievedRelevant)
                .recall(recall)
                .precision(precision)
                .hit(judgeResult.retrievedRelevant > 0)
                .retrievalMs(retrievalMs)
                .judgeMs(judgeMs)
                .judgeReasoning(judgeResult.reasoning)
                .build();
    }

    private LLMJudgeResult llmJudge(String query, List<String> allChunks, List<String> retrievedChunks) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是一个 RAG 系统的检索质量评估专家。请根据以下信息评估检索召回率。\n\n");
        sb.append("## 用户问题\n").append(query).append("\n\n");

        sb.append("## 该 Collection 的全部 Chunks（Ground Truth 候选池）\n");
        for (int i = 0; i < allChunks.size(); i++) {
            String preview = truncate(allChunks.get(i), 150);
            sb.append("[").append(i).append("] ").append(preview).append("\n");
        }
        sb.append("\n");

        sb.append("## 检索返回的 TopK Chunks\n");
        for (int i = 0; i < retrievedChunks.size(); i++) {
            String preview = truncate(retrievedChunks.get(i), 150);
            sb.append("[").append(i).append("] ").append(preview).append("\n");
        }
        sb.append("\n");

        sb.append("## 评估任务\n");
        sb.append("1. 从「全部 Chunks」中找出与用户问题**语义相关**的 chunk 序号列表（relevant_all）\n");
        sb.append("2. 从「检索返回的 TopK Chunks」中找出与用户问题**语义相关**的 chunk 序号列表（relevant_retrieved）\n");
        sb.append("   注意：TopK 中的 chunk 可能在全部 Chunks 中有相似/重复的内容，根据语义判断\n");
        sb.append("3. 判断标准：chunk 包含能回答用户问题的关键信息即为相关\n\n");

        sb.append("请严格按以下 JSON 格式输出（不要有其他文字）：\n");
        sb.append("{\"relevant_all\": [0,1,3], \"relevant_retrieved\": [0,2], \"reasoning\": \"简述判断依据\"}\n");

        ChatRequest request = ChatRequest.builder()
                .messages(List.of(
                        ChatMessage.system("你是一个精确的 RAG 检索质量评估器，只输出 JSON。"),
                        ChatMessage.user(sb.toString())
                ))
                .temperature(0.0)
                .topP(0.1)
                .thinking(false)
                .build();

        try {
            String raw = llmService.chat(request);
            return parseJudgeResponse(raw);
        } catch (Exception e) {
            log.error("LLM Judge 调用失败: query={}", query, e);
            return new LLMJudgeResult(0, 0, "LLM调用失败: " + e.getMessage());
        }
    }

    private LLMJudgeResult parseJudgeResponse(String raw) {
        try {
            String cleaned = raw.trim();
            if (cleaned.startsWith("```")) {
                cleaned = cleaned.replaceAll("```json\\s*", "").replaceAll("```\\s*", "").trim();
            }
            JsonObject obj = JsonParser.parseString(cleaned).getAsJsonObject();

            int totalRelevant = obj.has("relevant_all") ? obj.getAsJsonArray("relevant_all").size() : 0;
            int retrievedRelevant = obj.has("relevant_retrieved") ? obj.getAsJsonArray("relevant_retrieved").size() : 0;
            String reasoning = obj.has("reasoning") ? obj.get("reasoning").getAsString() : "";

            return new LLMJudgeResult(totalRelevant, retrievedRelevant, reasoning);
        } catch (Exception e) {
            log.warn("解析 LLM Judge 响应失败: {}", raw, e);
            return new LLMJudgeResult(0, 0, "解析失败");
        }
    }

    private List<String> loadAllChunks(String collectionName) {
        KnowledgeBaseDO kb = knowledgeBaseMapper.selectOne(
                Wrappers.lambdaQuery(KnowledgeBaseDO.class)
                        .eq(KnowledgeBaseDO::getCollectionName, collectionName)
                        .eq(KnowledgeBaseDO::getDeleted, 0)
        );
        if (kb == null) {
            return List.of();
        }

        List<KnowledgeChunkDO> chunks = knowledgeChunkMapper.selectList(
                Wrappers.lambdaQuery(KnowledgeChunkDO.class)
                        .eq(KnowledgeChunkDO::getKbId, kb.getId())
                        .eq(KnowledgeChunkDO::getDeleted, 0)
                        .eq(KnowledgeChunkDO::getEnabled, 1)
                        .orderByAsc(KnowledgeChunkDO::getChunkIndex)
        );

        return chunks.stream()
                .map(KnowledgeChunkDO::getContent)
                .toList();
    }

    private void printQueryResult(QueryJudgeResult r) {
        System.out.printf("  Q: %s%n", r.query);
        System.out.printf("     检索: %d chunks (%dms) | LLM评判: %dms%n",
                r.retrievedCount, r.retrievalMs, r.judgeMs);
        System.out.printf("     相关chunks: 全部=%d, 检索到=%d%n",
                r.totalRelevant, r.retrievedRelevant);
        System.out.printf("     Recall=%.4f | Precision=%.4f | Hit=%s%n",
                r.recall, r.precision, r.hit);
        if (r.judgeReasoning != null && !r.judgeReasoning.isBlank()) {
            System.out.printf("     评判理由: %s%n", truncate(r.judgeReasoning, 120));
        }
        System.out.println();
    }

    private void printCollectionSummary(CollectionResult cr) {
        double avgRecall = cr.results.stream().mapToDouble(r -> r.recall).average().orElse(0);
        double avgPrecision = cr.results.stream().mapToDouble(r -> r.precision).average().orElse(0);
        double hitRate = (double) cr.results.stream().filter(r -> r.hit).count() / cr.results.size();
        double avgRetrievalMs = cr.results.stream().mapToLong(r -> r.retrievalMs).average().orElse(0);
        double avgJudgeMs = cr.results.stream().mapToLong(r -> r.judgeMs).average().orElse(0);

        System.out.println("  " + "-".repeat(60));
        System.out.printf("  [%s] 汇总 (chunks=%d, queries=%d)%n",
                cr.collectionName, cr.totalChunks, cr.results.size());
        System.out.printf("    Avg Recall    = %.4f%n", avgRecall);
        System.out.printf("    Avg Precision = %.4f%n", avgPrecision);
        System.out.printf("    Hit Rate      = %.4f (%d/%d)%n",
                hitRate,
                cr.results.stream().filter(r -> r.hit).count(),
                cr.results.size());
        System.out.printf("    Avg Retrieval = %.1fms%n", avgRetrievalMs);
        System.out.printf("    Avg Judge     = %.1fms%n", avgJudgeMs);

        List<QueryJudgeResult> zeroRecall = cr.results.stream()
                .filter(r -> r.recall == 0.0)
                .toList();
        if (!zeroRecall.isEmpty()) {
            System.out.println("    [!] Recall=0 的查询:");
            for (QueryJudgeResult r : zeroRecall) {
                System.out.println("      - \"" + r.query + "\"");
            }
        }
    }

    private void printGlobalSummary(List<CollectionResult> allResults) {
        List<QueryJudgeResult> all = allResults.stream()
                .flatMap(cr -> cr.results.stream())
                .toList();

        if (all.isEmpty()) {
            System.out.println("无测试结果");
            return;
        }

        double globalRecall = all.stream().mapToDouble(r -> r.recall).average().orElse(0);
        double globalPrecision = all.stream().mapToDouble(r -> r.precision).average().orElse(0);
        double globalHitRate = (double) all.stream().filter(r -> r.hit).count() / all.size();

        System.out.println("=".repeat(90));
        System.out.println("  全局汇总");
        System.out.println("=".repeat(90));
        System.out.printf("  总查询数:        %d%n", all.size());
        System.out.printf("  全局 Avg Recall:  %.4f%n", globalRecall);
        System.out.printf("  全局 Avg Precision: %.4f%n", globalPrecision);
        System.out.printf("  全局 Hit Rate:    %.4f (%d/%d)%n",
                globalHitRate, all.stream().filter(r -> r.hit).count(), all.size());
        System.out.println();

        System.out.printf("  %-30s  %8s  %10s  %10s  %10s%n",
                "Collection", "Queries", "Avg Recall", "Avg Prec", "Hit Rate");
        System.out.println("  " + "-".repeat(75));
        for (CollectionResult cr : allResults) {
            double avgR = cr.results.stream().mapToDouble(r -> r.recall).average().orElse(0);
            double avgP = cr.results.stream().mapToDouble(r -> r.precision).average().orElse(0);
            double hr = (double) cr.results.stream().filter(r -> r.hit).count() / cr.results.size();
            System.out.printf("  %-30s  %8d  %10.4f  %10.4f  %10.4f%n",
                    cr.collectionName, cr.results.size(), avgR, avgP, hr);
        }
    }

    private String truncate(String text, int maxLen) {
        if (text == null) return "";
        String flat = text.replace("\n", " ").trim();
        return flat.length() > maxLen ? flat.substring(0, maxLen) + "..." : flat;
    }

    @Builder
    private record QueryJudgeResult(
            String query,
            String collectionName,
            int retrievedCount,
            int totalRelevant,
            int retrievedRelevant,
            double recall,
            double precision,
            boolean hit,
            long retrievalMs,
            long judgeMs,
            String judgeReasoning
    ) {}

    @Builder
    private record CollectionResult(
            String collectionName,
            List<QueryJudgeResult> results,
            int totalChunks
    ) {}

    private record LLMJudgeResult(int totalRelevant, int retrievedRelevant, String reasoning) {}

    @Builder
    private record CollectionTestCase(
            String collectionName,
            List<String> queries
    ) {}

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
