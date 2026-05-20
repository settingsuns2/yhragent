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

package com.nageoffer.ai.ragent.framework.convention;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ToolDefinition {

    private String type;

    private FunctionDef function;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FunctionDef {

        private String name;

        private String description;

        private ParametersDef parameters;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ParametersDef {

        private String type;

        private Map<String, PropertyDef> properties;

        private List<String> required;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PropertyDef {

        private String type;

        private String description;

        private List<String> enumValues;
    }

    public static ToolDefinition function(String name, String description,
                                          Map<String, PropertyDef> properties,
                                          List<String> required) {
        return ToolDefinition.builder()
                .type("function")
                .function(FunctionDef.builder()
                        .name(name)
                        .description(description)
                        .parameters(ParametersDef.builder()
                                .type("object")
                                .properties(properties)
                                .required(required)
                                .build())
                        .build())
                .build();
    }
}
