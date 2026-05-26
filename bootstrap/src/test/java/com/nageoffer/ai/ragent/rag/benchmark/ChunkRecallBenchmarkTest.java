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

import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;
import com.nageoffer.ai.ragent.rag.core.retrieve.RetrieveRequest;
import com.nageoffer.ai.ragent.rag.core.retrieve.RetrieverService;
import com.nageoffer.ai.ragent.infra.rerank.RerankService;
import lombok.Builder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@SpringBootTest
@RequiredArgsConstructor(onConstructor = @__(@Autowired))
public class ChunkRecallBenchmarkTest {

    private final RetrieverService retrieverService;
    private final RerankService rerankService;

    private static final int[] TOP_K_VALUES = {3, 5, 8, 10};

    private static final double KEYWORD_MATCH_RATIO = 0.4;

    private static List<QueryTestCase> testCases;

    @BeforeAll
    static void setUp() {
        testCases = buildTestDataset();
    }

    @Test
    @Disabled("手动触发召回率基准测试，需要连接真实数据库和模型服务")
    void benchmarkRecall() {
        System.out.println("=".repeat(80));
        System.out.println("  Chunk 召回率基准测试 (RetrieverService)");
        System.out.println("  关键词匹配阈值: chunk 中包含 >= " + KEYWORD_MATCH_RATIO
                + " 比例的期望关键词视为相关命中");
        System.out.println("=".repeat(80));
        System.out.println();

        for (int topK : TOP_K_VALUES) {
            System.out.println("-".repeat(70));
            System.out.println("  TopK = " + topK);
            System.out.println("-".repeat(70));

            List<QueryResult> results = new ArrayList<>();
            for (QueryTestCase tc : testCases) {
                results.add(executeQuery(tc, topK));
            }

            printDetailedResults(results, topK);
            printSummary(results, topK);
            System.out.println();
        }
    }

    @Test
    @Disabled("手动触发，对比检索+Rerank后的召回率")
    void benchmarkRecallWithRerank() {
        int candidateK = 15;
        int[] rerankTopNs = {3, 5};

        System.out.println("=".repeat(80));
        System.out.println("  Chunk 召回率基准测试 (RetrieverService + Rerank)");
        System.out.println("  候选集大小: " + candidateK);
        System.out.println("=".repeat(80));
        System.out.println();

        for (int topN : rerankTopNs) {
            System.out.println("-".repeat(70));
            System.out.println("  Rerank TopN = " + topN);
            System.out.println("-".repeat(70));

            List<QueryResult> results = new ArrayList<>();
            for (QueryTestCase tc : testCases) {
                results.add(executeQueryWithRerank(tc, candidateK, topN));
            }

            printDetailedResults(results, topN);
            printSummary(results, topN);
            System.out.println();
        }
    }

    @Test
    @Disabled("手动触发，测试单个collection的召回率")
    void benchmarkRecallByCollection() {
        String collectionName = "group";
        int topK = 5;

        System.out.println("=".repeat(80));
        System.out.println("  单 Collection 召回率测试");
        System.out.println("  Collection: " + collectionName + ", TopK: " + topK);
        System.out.println("=".repeat(80));
        System.out.println();

        List<QueryTestCase> filteredCases = testCases.stream()
                .filter(tc -> collectionName.equals(tc.collectionName))
                .toList();

        if (filteredCases.isEmpty()) {
            System.out.println("当前 collection 没有对应的测试用例，请检查数据集定义");
            return;
        }

        List<QueryResult> results = new ArrayList<>();
        for (QueryTestCase tc : filteredCases) {
            results.add(executeQueryForCollection(tc, collectionName, topK));
        }

        printDetailedResults(results, topK);
        printSummary(results, topK);
    }

    @Test
    @Disabled("手动触发，打印每个查询的详细检索结果，用于人工标注 Ground Truth")
    void dumpRawResultsForAnnotation() {
        int topK = 10;

        System.out.println("=".repeat(80));
        System.out.println("  检索结果原始输出 (用于人工标注 Ground Truth)");
        System.out.println("=".repeat(80));
        System.out.println();

        for (int i = 0; i < testCases.size(); i++) {
            QueryTestCase tc = testCases.get(i);
            System.out.println("【" + (i + 1) + "/" + testCases.size() + "】 " + tc.query);
            System.out.println("  分类: " + tc.category);
            System.out.println("  期望关键词: " + tc.expectedKeywords);

            try {
                List<RetrievedChunk> chunks = retrieverService.retrieve(
                        RetrieveRequest.builder()
                                .query(tc.query)
                                .topK(topK)
                                .build()
                );

                for (int j = 0; j < chunks.size(); j++) {
                    RetrievedChunk chunk = chunks.get(j);
                    String content = chunk.getText();
                    String preview = content.length() > 120 ? content.substring(0, 120) + "..." : content;
                    System.out.printf("  [%d] id=%s  score=%.4f%n", j + 1, chunk.getId(), chunk.getScore());
                    System.out.println("      " + preview.replace("\n", " "));
                }

                if (chunks.isEmpty()) {
                    System.out.println("  (无检索结果)");
                }
            } catch (Exception e) {
                System.out.println("  (检索失败: " + e.getMessage() + ")");
            }

            System.out.println();
        }
    }

    private QueryResult executeQuery(QueryTestCase tc, int topK) {
        long start = System.currentTimeMillis();
        List<RetrievedChunk> chunks;
        try {
            chunks = retrieverService.retrieve(
                    RetrieveRequest.builder()
                            .query(tc.query)
                            .topK(topK)
                            .build()
            );
        } catch (Exception e) {
            log.error("检索失败: query={}", tc.query, e);
            chunks = List.of();
        }
        long latency = System.currentTimeMillis() - start;
        return evaluate(tc, chunks, latency);
    }

    private QueryResult executeQueryWithRerank(QueryTestCase tc, int candidateK, int topN) {
        long start = System.currentTimeMillis();
        List<RetrievedChunk> finalChunks;
        try {
            List<RetrievedChunk> candidates = retrieverService.retrieve(
                    RetrieveRequest.builder()
                            .query(tc.query)
                            .topK(candidateK)
                            .build()
            );
            finalChunks = rerankService.rerank(tc.query, candidates, topN);
        } catch (Exception e) {
            log.error("检索+Rerank失败: query={}", tc.query, e);
            finalChunks = List.of();
        }
        long latency = System.currentTimeMillis() - start;
        return evaluate(tc, finalChunks, latency);
    }

    private QueryResult executeQueryForCollection(QueryTestCase tc, String collectionName, int topK) {
        long start = System.currentTimeMillis();
        List<RetrievedChunk> chunks;
        try {
            chunks = retrieverService.retrieve(
                    RetrieveRequest.builder()
                            .query(tc.query)
                            .topK(topK)
                            .collectionName(collectionName)
                            .build()
            );
        } catch (Exception e) {
            log.error("检索失败: query={}, collection={}", tc.query, collectionName, e);
            chunks = List.of();
        }
        long latency = System.currentTimeMillis() - start;
        return evaluate(tc, chunks, latency);
    }

    private QueryResult evaluate(QueryTestCase tc, List<RetrievedChunk> retrieved, long latencyMs) {
        List<String> keywords = tc.expectedKeywords;
        int minMatches = Math.max(1, (int) Math.ceil(keywords.size() * KEYWORD_MATCH_RATIO));

        int hits = 0;
        int firstHitRank = -1;

        for (int i = 0; i < retrieved.size(); i++) {
            RetrievedChunk chunk = retrieved.get(i);
            if (isRelevant(chunk.getText(), keywords, minMatches)) {
                hits++;
                if (firstHitRank < 0) {
                    firstHitRank = i;
                }
            }
        }

        double mrr = firstHitRank >= 0 ? 1.0 / (firstHitRank + 1) : 0.0;
        double recall = 1.0;
        double precision = retrieved.isEmpty() ? 0.0 : (double) hits / retrieved.size();

        return QueryResult.builder()
                .query(tc.query)
                .category(tc.category)
                .hits(hits)
                .retrievedCount(retrieved.size())
                .recall(recall)
                .precision(precision)
                .mrr(mrr)
                .hit(hits > 0)
                .latencyMs(latencyMs)
                .retrievedChunks(retrieved)
                .expectedKeywords(keywords)
                .build();
    }

    private boolean isRelevant(String chunkText, List<String> keywords, int minMatches) {
        if (chunkText == null || chunkText.isEmpty()) {
            return false;
        }
        String lower = chunkText.toLowerCase();
        int matchCount = 0;
        for (String keyword : keywords) {
            if (lower.contains(keyword.toLowerCase())) {
                matchCount++;
            }
        }
        return matchCount >= minMatches;
    }

    private void printDetailedResults(List<QueryResult> results, int topK) {
        System.out.printf("%-4s  %-30s  %-12s  %6s  %7s  %9s  %5s  %7s%n",
                "#", "查询问题", "分类", "命中数", "Recall", "Precision", "MRR", "延迟ms");
        System.out.println("-".repeat(100));

        for (int i = 0; i < results.size(); i++) {
            QueryResult r = results.get(i);
            String queryDisplay = r.query.length() > 28 ? r.query.substring(0, 26) + ".." : r.query;
            System.out.printf("%-4d  %-30s  %-12s  %6d  %7.2f  %9.2f  %5.2f  %7d%n",
                    i + 1, queryDisplay, r.category, r.hits,
                    r.recall, r.precision, r.mrr, r.latencyMs);
        }
        System.out.println();
    }

    private void printSummary(List<QueryResult> results, int topK) {
        double avgPrecision = results.stream().mapToDouble(r -> r.precision).average().orElse(0);
        double avgMRR = results.stream().mapToDouble(r -> r.mrr).average().orElse(0);
        double hitRate = (double) results.stream().filter(r -> r.hit).count() / results.size();
        double avgLatency = results.stream().mapToLong(r -> r.latencyMs).average().orElse(0);
        double avgHits = results.stream().mapToInt(r -> r.hits).average().orElse(0);

        System.out.println("+--------------------------------------------------------------+");
        System.out.printf("|  汇总统计 (TopK = %-2d)                                       |%n", topK);
        System.out.println("+--------------------------------------------------------------+");
        System.out.printf("|  Hit Rate     = %-6.4f  (%d/%d queries hit)               |%n",
                hitRate, results.stream().filter(r -> r.hit).count(), results.size());
        System.out.printf("|  Avg Hits     = %-6.2f  (平均每个查询命中的chunk数)        |%n", avgHits);
        System.out.printf("|  Avg Precision= %-6.4f                                     |%n", avgPrecision);
        System.out.printf("|  Avg MRR      = %-6.4f  (第一个命中结果的排名倒数均值)     |%n", avgMRR);
        System.out.printf("|  Avg Latency  = %-8.1f ms                                 |%n", avgLatency);
        System.out.println("+--------------------------------------------------------------+");

        System.out.println();
        System.out.println("[按分类统计]");
        Map<String, List<QueryResult>> byCategory = results.stream()
                .collect(Collectors.groupingBy(r -> r.category, LinkedHashMap::new, Collectors.toList()));

        System.out.printf("  %-14s  %8s  %10s  %10s  %10s  %10s%n",
                "分类", "用例数", "Hit Rate", "Avg Hits", "Avg Prec", "Avg MRR");
        System.out.println("  " + "-".repeat(65));
        for (Map.Entry<String, List<QueryResult>> entry : byCategory.entrySet()) {
            List<QueryResult> catResults = entry.getValue();
            double catHitRate = (double) catResults.stream().filter(r -> r.hit).count() / catResults.size();
            double catAvgHits = catResults.stream().mapToInt(r -> r.hits).average().orElse(0);
            double catPrec = catResults.stream().mapToDouble(r -> r.precision).average().orElse(0);
            double catMRR = catResults.stream().mapToDouble(r -> r.mrr).average().orElse(0);
            System.out.printf("  %-14s  %8d  %10.4f  %10.2f  %10.4f  %10.4f%n",
                    entry.getKey(), catResults.size(), catHitRate, catAvgHits, catPrec, catMRR);
        }

        List<QueryResult> zeroHit = results.stream()
                .filter(r -> !r.hit)
                .toList();
        if (!zeroHit.isEmpty()) {
            System.out.println();
            System.out.println("[未命中任何相关chunk的查询（需要重点关注）]");
            for (QueryResult r : zeroHit) {
                System.out.println("  - \"" + r.query + "\" (分类: " + r.category + ")");
                System.out.println("    期望关键词: " + r.expectedKeywords);
                if (!r.retrievedChunks.isEmpty()) {
                    System.out.println("    Top1 返回内容: "
                            + truncate(r.retrievedChunks.get(0).getText(), 100));
                } else {
                    System.out.println("    (无检索结果返回)");
                }
            }
        }
    }

    private String truncate(String text, int maxLen) {
        if (text == null) return "";
        String flat = text.replace("\n", " ").trim();
        return flat.length() > maxLen ? flat.substring(0, maxLen) + "..." : flat;
    }

    @Builder
    private record QueryResult(
            String query,
            String category,
            int hits,
            int retrievedCount,
            double recall,
            double precision,
            double mrr,
            boolean hit,
            long latencyMs,
            List<RetrievedChunk> retrievedChunks,
            List<String> expectedKeywords
    ) {}

    @Builder
    private record QueryTestCase(
            String query,
            String category,
            String collectionName,
            List<String> expectedKeywords
    ) {}

    private static List<QueryTestCase> buildTestDataset() {
        List<QueryTestCase> cases = new ArrayList<>();

        // ==================== 工作时间与加班 (ATTENDANCE) ====================
        cases.add(QueryTestCase.builder()
                .query("正常上班时间是几点到几点？")
                .category("ATTENDANCE")
                .collectionName("group")
                .expectedKeywords(List.of("上班时间", "工作时间", "周一至周五", "9:30", "18:30", "中午休息"))
                .build());

        cases.add(QueryTestCase.builder()
                .query("加班到凌晨第二天可以几点上班？")
                .category("ATTENDANCE")
                .collectionName("group")
                .expectedKeywords(List.of("加班", "次日", "凌晨", "23:00", "0:00", "弹性上班"))
                .build());

        cases.add(QueryTestCase.builder()
                .query("加班有加班费吗？还是只能调休？")
                .category("ATTENDANCE")
                .collectionName("group")
                .expectedKeywords(List.of("加班", "调休", "加班费", "补偿", "效率"))
                .build());

        cases.add(QueryTestCase.builder()
                .query("弹性工作制是怎么规定的？")
                .category("ATTENDANCE")
                .collectionName("group")
                .expectedKeywords(List.of("弹性工作制", "灵活", "工作时长", "上下班"))
                .build());

        cases.add(QueryTestCase.builder()
                .query("几点上班")
                .category("ATTENDANCE")
                .collectionName("group")
                .expectedKeywords(List.of("上班时间", "9:30", "18:30", "工作时间"))
                .build());

        // ==================== 薪酬福利 (COMPENSATION) ====================
        cases.add(QueryTestCase.builder()
                .query("公司每个月几号发工资？")
                .category("COMPENSATION")
                .collectionName("group")
                .expectedKeywords(List.of("发薪日", "次月", "10日", "薪资发放", "发薪"))
                .build());

        cases.add(QueryTestCase.builder()
                .query("年终奖一般发几个月？")
                .category("COMPENSATION")
                .collectionName("group")
                .expectedKeywords(List.of("年终奖", "绩效", "年度", "工资", "激励"))
                .build());

        cases.add(QueryTestCase.builder()
                .query("多久可以调薪一次？")
                .category("COMPENSATION")
                .collectionName("group")
                .expectedKeywords(List.of("调薪", "年度调薪", "绩效结果", "调薪幅度"))
                .build());

        cases.add(QueryTestCase.builder()
                .query("五险一金缴纳比例是多少？")
                .category("COMPENSATION")
                .collectionName("group")
                .expectedKeywords(List.of("五险一金", "商业保险", "福利", "保障"))
                .build());

        cases.add(QueryTestCase.builder()
                .query("发工资的时间")
                .category("COMPENSATION")
                .collectionName("group")
                .expectedKeywords(List.of("发薪日", "次月", "10日", "薪资发放"))
                .build());

        // ==================== 招聘流程 (RECRUITMENT) ====================
        cases.add(QueryTestCase.builder()
                .query("社招的面试流程是什么样的？")
                .category("RECRUITMENT")
                .collectionName("group")
                .expectedKeywords(List.of("社招", "面试流程", "简历", "初筛", "终面"))
                .build());

        cases.add(QueryTestCase.builder()
                .query("校招投递简历后多久会通知面试？")
                .category("RECRUITMENT")
                .collectionName("group")
                .expectedKeywords(List.of("校招", "网申", "面试通知", "投递"))
                .build());

        cases.add(QueryTestCase.builder()
                .query("面试通过后多久会发offer？")
                .category("RECRUITMENT")
                .collectionName("group")
                .expectedKeywords(List.of("offer", "面试", "审批", "录用"))
                .build());

        // ==================== 公司制度 (POLICY) ====================
        cases.add(QueryTestCase.builder()
                .query("试用期离职需要提前多久申请？")
                .category("POLICY")
                .collectionName("group")
                .expectedKeywords(List.of("试用期", "离职", "提前", "申请"))
                .build());

        cases.add(QueryTestCase.builder()
                .query("员工违反纪律会怎么处理？")
                .category("POLICY")
                .collectionName("group")
                .expectedKeywords(List.of("违纪", "纪律", "处理", "行为规范"))
                .build());

        cases.add(QueryTestCase.builder()
                .query("离职流程是怎样的？")
                .category("POLICY")
                .collectionName("group")
                .expectedKeywords(List.of("离职", "流程", "交接", "手续"))
                .build());

        cases.add(QueryTestCase.builder()
                .query("试用期一般是多长时间？")
                .category("POLICY")
                .collectionName("group")
                .expectedKeywords(List.of("试用期", "3", "6", "个月", "转正"))
                .build());

        cases.add(QueryTestCase.builder()
                .query("试用期转正需要满足什么条件？")
                .category("POLICY")
                .collectionName("group")
                .expectedKeywords(List.of("转正", "试用期目标", "转正评审", "中期评估"))
                .build());

        // ==================== IT 支持 (IT_SUPPORT) ====================
        cases.add(QueryTestCase.builder()
                .query("忘记密码了怎么重置？")
                .category("IT_SUPPORT")
                .collectionName("group")
                .expectedKeywords(List.of("密码重置", "忘记密码", "统一身份", "密码过期"))
                .build());

        cases.add(QueryTestCase.builder()
                .query("Mac电脑怎么删除旧的钥匙串凭据？")
                .category("IT_SUPPORT")
                .collectionName("group")
                .expectedKeywords(List.of("macOS", "钥匙串", "凭据", "密码缓存"))
                .build());

        cases.add(QueryTestCase.builder()
                .query("账号被锁定了怎么办？")
                .category("IT_SUPPORT")
                .collectionName("group")
                .expectedKeywords(List.of("账号锁定", "解锁", "安全校验", "尝试次数"))
                .build());

        cases.add(QueryTestCase.builder()
                .query("企业邮箱在手机上怎么配置？")
                .category("IT_SUPPORT")
                .collectionName("group")
                .expectedKeywords(List.of("企业邮箱", "手机", "配置", "客户端"))
                .build());

        cases.add(QueryTestCase.builder()
                .query("VPN连不上怎么办？")
                .category("IT_SUPPORT")
                .collectionName("group")
                .expectedKeywords(List.of("VPN", "网络", "连接", "远程"))
                .build());

        // ==================== 绩效管理 ====================
        cases.add(QueryTestCase.builder()
                .query("绩效评估的流程是怎样的？")
                .category("POLICY")
                .collectionName("group")
                .expectedKeywords(List.of("绩效", "目标制定", "评估", "沟通", "考核"))
                .build());

        cases.add(QueryTestCase.builder()
                .query("晋升和调岗的机制是什么？")
                .category("POLICY")
                .collectionName("group")
                .expectedKeywords(List.of("晋升", "调岗", "发展", "岗位"))
                .build());

        // ==================== 发票/财务 (FINANCE) ====================
        cases.add(QueryTestCase.builder()
                .query("阿里的发票抬头是什么？")
                .category("FINANCE")
                .collectionName("group")
                .expectedKeywords(List.of("阿里", "发票抬头", "开票", "纳税人识别号"))
                .build());

        cases.add(QueryTestCase.builder()
                .query("腾讯的发票信息有哪些？")
                .category("FINANCE")
                .collectionName("group")
                .expectedKeywords(List.of("腾讯", "发票", "开票抬头", "开户银行"))
                .build());

        // ==================== 业务系统 - OA (BIZ_OA) ====================
        cases.add(QueryTestCase.builder()
                .query("OA系统数据安全有哪些规范要求？")
                .category("BIZ_OA")
                .collectionName("business")
                .expectedKeywords(List.of("OA", "数据安全", "权限", "审计", "加密"))
                .build());

        cases.add(QueryTestCase.builder()
                .query("OA系统如何进行访问控制？")
                .category("BIZ_OA")
                .collectionName("business")
                .expectedKeywords(List.of("OA", "访问控制", "权限", "角色", "认证"))
                .build());

        // ==================== 业务系统 - 保险 (BIZ_INS) ====================
        cases.add(QueryTestCase.builder()
                .query("互联网保险系统的数据安全规范是什么？")
                .category("BIZ_INS")
                .collectionName("business")
                .expectedKeywords(List.of("保险", "数据安全", "脱敏", "加密", "合规"))
                .build());

        cases.add(QueryTestCase.builder()
                .query("保险系统的敏感信息如何保护？")
                .category("BIZ_INS")
                .collectionName("business")
                .expectedKeywords(List.of("保险", "敏感信息", "脱敏", "保护", "加密"))
                .build());

        return cases;
    }
}
