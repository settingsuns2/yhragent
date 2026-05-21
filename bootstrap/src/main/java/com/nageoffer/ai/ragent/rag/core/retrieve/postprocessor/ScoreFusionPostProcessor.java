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

package com.nageoffer.ai.ragent.rag.core.retrieve.postprocessor;

import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;
import com.nageoffer.ai.ragent.rag.config.SearchChannelProperties;
import com.nageoffer.ai.ragent.rag.core.retrieve.channel.SearchChannelResult;
import com.nageoffer.ai.ragent.rag.core.retrieve.channel.SearchChannelType;
import com.nageoffer.ai.ragent.rag.core.retrieve.channel.SearchContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
@Component
@RequiredArgsConstructor
public class ScoreFusionPostProcessor implements SearchResultPostProcessor {

    private static final Set<SearchChannelType> DENSE_TYPES = Set.of(
            SearchChannelType.VECTOR_GLOBAL,
            SearchChannelType.INTENT_DIRECTED
    );

    private static final Set<SearchChannelType> SPARSE_TYPES = Set.of(
            SearchChannelType.KEYWORD_PG,
            SearchChannelType.KEYWORD_ES
    );

    private final SearchChannelProperties properties;

    @Override
    public String getName() {
        return "ScoreFusion";
    }

    @Override
    public int getOrder() {
        return 2;
    }

    @Override
    public boolean isEnabled(SearchContext context) {
        return properties.getScoreFusion().isEnabled();
    }

    @Override
    public List<RetrievedChunk> process(List<RetrievedChunk> chunks,
                                        List<SearchChannelResult> results,
                                        SearchContext context) {
        SearchChannelProperties.ScoreFusion fusionConfig = properties.getScoreFusion();
        double denseWeight = fusionConfig.getDenseWeight();
        double sparseWeight = fusionConfig.getSparseWeight();

        boolean hasDense = results.stream()
                .anyMatch(r -> DENSE_TYPES.contains(r.getChannelType())
                        && !r.getChunks().isEmpty());
        boolean hasSparse = results.stream()
                .anyMatch(r -> SPARSE_TYPES.contains(r.getChannelType())
                        && !r.getChunks().isEmpty());

        if (!hasDense || !hasSparse) {
            log.info("仅存在单路检索结果（dense={}, sparse={}），跳过分数融合", hasDense, hasSparse);
            return chunks;
        }

        Map<String, DenseSparseScore> scoreMap = buildScoreMap(results);

        List<RetrievedChunk> fused = new ArrayList<>();
        for (Map.Entry<String, DenseSparseScore> entry : scoreMap.entrySet()) {
            DenseSparseScore ds = entry.getValue();
            float normDense = ds.denseScore;
            float normSparse = ds.sparseScore;

            float fusedScore = (float) (denseWeight * normDense + sparseWeight * normSparse);

            RetrievedChunk chunk = RetrievedChunk.builder()
                    .id(ds.chunkId)
                    .text(ds.chunkText)
                    .score(fusedScore)
                    .build();
            fused.add(chunk);
        }

        fused.sort((a, b) -> Float.compare(b.getScore(), a.getScore()));

        log.info("分数融合完成 - dense权重: {}, sparse权重: {}, 融合后 Chunk 数: {}",
                denseWeight, sparseWeight, fused.size());

        return fused;
    }

    private Map<String, DenseSparseScore> buildScoreMap(List<SearchChannelResult> results) {
        Map<String, DenseSparseScore> scoreMap = new LinkedHashMap<>();

        float denseMin = Float.MAX_VALUE;
        float denseMax = Float.MIN_VALUE;
        float sparseMin = Float.MAX_VALUE;
        float sparseMax = Float.MIN_VALUE;

        for (SearchChannelResult result : results) {
            for (RetrievedChunk chunk : result.getChunks()) {
                String key = chunk.getId() != null ? chunk.getId() : String.valueOf(chunk.getText().hashCode());
                boolean isDense = DENSE_TYPES.contains(result.getChannelType());

                DenseSparseScore ds = scoreMap.computeIfAbsent(key, k ->
                        new DenseSparseScore(key, chunk.getText()));

                if (isDense) {
                    ds.denseScore = Math.max(ds.denseScore, chunk.getScore());
                    denseMin = Math.min(denseMin, chunk.getScore());
                    denseMax = Math.max(denseMax, chunk.getScore());
                } else {
                    ds.sparseScore = Math.max(ds.sparseScore, chunk.getScore());
                    sparseMin = Math.min(sparseMin, chunk.getScore());
                    sparseMax = Math.max(sparseMax, chunk.getScore());
                }
            }
        }

        float denseRange = denseMax - denseMin;
        float sparseRange = sparseMax - sparseMin;

        for (DenseSparseScore ds : scoreMap.values()) {
            if (denseRange > 0 && ds.denseScore > 0) {
                ds.denseScore = (ds.denseScore - denseMin) / denseRange;
            }
            if (sparseRange > 0 && ds.sparseScore > 0) {
                ds.sparseScore = (ds.sparseScore - sparseMin) / sparseRange;
            }
        }

        return scoreMap;
    }

    private static class DenseSparseScore {
        final String chunkId;
        final String chunkText;
        float denseScore = 0f;
        float sparseScore = 0f;

        DenseSparseScore(String chunkId, String chunkText) {
            this.chunkId = chunkId;
            this.chunkText = chunkText;
        }
    }
}
