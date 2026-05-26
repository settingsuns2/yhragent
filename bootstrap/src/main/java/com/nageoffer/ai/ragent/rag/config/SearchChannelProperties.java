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

package com.nageoffer.ai.ragent.rag.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import lombok.Data;

/**
 * RAG 检索配置
 */
@Data
@Component
@ConfigurationProperties(prefix = "rag.search")
public class SearchChannelProperties {

    private Channels channels = new Channels();

    private ScoreFusion scoreFusion = new ScoreFusion();

    private RegexRouting regexRouting = new RegexRouting();

    @Data
    public static class Channels {

        /**
         * 向量全局检索配置
         */
        private VectorGlobal vectorGlobal = new VectorGlobal();

        /**
         * 意图定向检索配置
         */
        private IntentDirected intentDirected = new IntentDirected();

        /**
         * PG 关键词检索配置（基于 FTS + pg_jieba）
         */
        private KeywordPg keywordPg = new KeywordPg();
    }

    @Data
    public static class ScoreFusion {

        /**
         * 是否启用分数融合（dense + sparse 加权融合）
         */
        private boolean enabled = true;

        /**
         * 密集检索（dense）权重，默认 0.7
         */
        private double denseWeight = 0.7;

        /**
         * 稀疏检索（sparse/FTS）权重，默认 0.3
         */
        private double sparseWeight = 0.3;
    }

    @Data
    public static class VectorGlobal {

        /**
         * 是否启用
         */
        private boolean enabled = true;

        /**
         * 意图置信度阈值
         * 当意图识别的最高分数低于此阈值时，启用全局检索
         */
        private double confidenceThreshold = 0.6;

        /**
         * 单意图补充检索阈值
         * 当仅识别出一个意图且分数低于此阈值时，启用全局检索作为安全网
         */
        private double singleIntentSupplementThreshold = 0.8;

        /**
         * TopK 倍数
         * 全局检索时召回更多候选，后续通过 Rerank 筛选
         */
        private int topKMultiplier = 3;
    }

    @Data
    public static class IntentDirected {

        /**
         * 是否启用
         */
        private boolean enabled = true;

        /**
         * 最低意图分数
         * 低于此分数的意图节点会被过滤
         */
        private double minIntentScore = 0.4;

        /**
         * TopK 倍数
         */
        private int topKMultiplier = 2;
    }

    @Data
    public static class KeywordPg {

        /**
         * 是否启用 PG 全文检索通道
         */
        private boolean enabled = true;

        /**
         * PostgreSQL 文本搜索配置名称
         * 安装了 pg_jieba 时使用 jiebacfg，否则使用 simple
         */
        private String tsConfigName = "zh";

        /**
         * TopK 倍数
         */
        private int topKMultiplier = 3;
    }

    @Data
    public static class RegexRouting {

        private boolean enabled = false;
    }
}
