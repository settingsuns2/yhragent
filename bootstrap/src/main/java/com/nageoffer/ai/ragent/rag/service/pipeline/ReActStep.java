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

package com.nageoffer.ai.ragent.rag.service.pipeline;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReActStep {

    private String toolId;
    private String action;
    private boolean success;
    private String result;
    private String error;

    public static ReActStep success(String toolId, String action, String result) {
        return ReActStep.builder()
                .toolId(toolId)
                .action(action)
                .success(true)
                .result(result)
                .build();
    }

    public static ReActStep failure(String toolId, String action, String error) {
        return ReActStep.builder()
                .toolId(toolId)
                .action(action)
                .success(false)
                .error(error)
                .build();
    }

    public String toDescription() {
        if (success) {
            return "工具 " + toolId + "(" + action + ") 执行成功，结果:\n" + result;
        }
        return "工具 " + toolId + "(" + action + ") 执行失败: " + error;
    }
}
