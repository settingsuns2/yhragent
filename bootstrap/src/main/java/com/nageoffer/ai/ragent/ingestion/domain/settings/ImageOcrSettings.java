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

package com.nageoffer.ai.ragent.ingestion.domain.settings;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ImageOcrSettings {

    @Builder.Default
    private String modelId = null;

    @Builder.Default
    private String systemPrompt = "你是一个专业的文档图片识别助手。请仔细观察图片，将其中的所有文字、表格、图表内容完整地转换为纯文本格式。保留原始结构和层次，不要遗漏任何信息。如果图片中包含表格，请用文本格式还原表格内容。只输出识别结果，不要添加额外解释。";

    @Builder.Default
    private String userPromptTemplate = "请识别并提取这张图片中的所有文字和内容：";

    @Builder.Default
    private int concurrency = 3;

    @Builder.Default
    private boolean enabled = true;

    @Builder.Default
    private boolean failOnError = true;
}
