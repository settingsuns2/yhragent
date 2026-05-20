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

package com.nageoffer.ai.ragent.mcp.executor;

import com.nageoffer.ai.ragent.mcp.core.MCPToolDefinition;
import com.nageoffer.ai.ragent.mcp.core.MCPToolExecutor;
import com.nageoffer.ai.ragent.mcp.core.MCPToolRequest;
import com.nageoffer.ai.ragent.mcp.core.MCPToolResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

@Slf4j
@Component
public class FileManagerExecutor implements MCPToolExecutor {

    private static final String TOOL_ID = "file_manager";

    private final Path workspaceRoot;

    public FileManagerExecutor(@Value("${rag.file-manager.workspace:./workspace}") String workspace) {
        this.workspaceRoot = Paths.get(workspace).toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.workspaceRoot);
        } catch (IOException e) {
            log.warn("文件管理工作空间创建失败: {}", this.workspaceRoot, e);
        }
        log.info("文件管理工具初始化, workspace={}", this.workspaceRoot);
    }

    @Override
    public MCPToolDefinition getToolDefinition() {
        Map<String, MCPToolDefinition.ParameterDef> parameters = new LinkedHashMap<>();

        parameters.put("action", MCPToolDefinition.ParameterDef.builder()
                .description("操作类型: read(读文件), write(写文件), append(追加), edit(搜索替换), list(列目录), tree(目录树), mkdir(创建目录), delete(删除), search(搜索内容), exists(判断存在)")
                .type("string")
                .required(true)
                .enumValues(List.of("read", "write", "append", "edit", "list", "tree", "mkdir", "delete", "search", "exists"))
                .build());

        parameters.put("path", MCPToolDefinition.ParameterDef.builder()
                .description("文件或目录路径，相对于工作空间根目录")
                .type("string")
                .required(true)
                .build());

        parameters.put("content", MCPToolDefinition.ParameterDef.builder()
                .description("写入/追加的文件内容（write/append 操作时使用）")
                .type("string")
                .required(false)
                .build());

        parameters.put("search", MCPToolDefinition.ParameterDef.builder()
                .description("搜索的文本内容（edit 操作时为要替换的旧文本，search 操作时为搜索关键词）")
                .type("string")
                .required(false)
                .build());

        parameters.put("replace", MCPToolDefinition.ParameterDef.builder()
                .description("替换后的新文本（仅 edit 操作时使用）")
                .type("string")
                .required(false)
                .build());

        parameters.put("startLine", MCPToolDefinition.ParameterDef.builder()
                .description("读取起始行号，从1开始（仅 read 操作时可选）")
                .type("integer")
                .required(false)
                .build());

        parameters.put("endLine", MCPToolDefinition.ParameterDef.builder()
                .description("读取结束行号（仅 read 操作时可选）")
                .type("integer")
                .required(false)
                .build());

        parameters.put("recursive", MCPToolDefinition.ParameterDef.builder()
                .description("是否递归操作（list/delete 操作时可选，默认 false）")
                .type("boolean")
                .required(false)
                .defaultValue("false")
                .build());

        return MCPToolDefinition.builder()
                .toolId(TOOL_ID)
                .description("本地文件管理工具，支持读写文件、目录操作、内容搜索替换等，用于编程 Agent 操作宿主机文件系统")
                .parameters(parameters)
                .requireUserId(false)
                .build();
    }

    @Override
    public MCPToolResponse execute(MCPToolRequest request) {
        long start = System.currentTimeMillis();
        String action = request.getStringParameter("action");
        String relativePath = request.getStringParameter("path");

        if (action == null || action.isBlank()) {
            return error("action 参数不能为空");
        }
        if (relativePath == null || relativePath.isBlank()) {
            return error("path 参数不能为空");
        }

        Path target = resolveSafe(relativePath);

        try {
            String result = switch (action) {
                case "read" -> doRead(target, request);
                case "write" -> doWrite(target, request);
                case "append" -> doAppend(target, request);
                case "edit" -> doEdit(target, request);
                case "list" -> doList(target, request);
                case "tree" -> doTree(target);
                case "mkdir" -> doMkdir(target);
                case "delete" -> doDelete(target, request);
                case "search" -> doSearch(target, request);
                case "exists" -> doExists(target);
                default -> "不支持的操作: " + action;
            };
            long cost = System.currentTimeMillis() - start;
            return MCPToolResponse.builder()
                    .success(true)
                    .toolId(TOOL_ID)
                    .textResult(result)
                    .costMs(cost)
                    .build();
        } catch (Exception e) {
            long cost = System.currentTimeMillis() - start;
            log.error("文件管理操作失败: action={}, path={}", action, relativePath, e);
            return MCPToolResponse.builder()
                    .success(false)
                    .toolId(TOOL_ID)
                    .errorMessage("操作失败: " + e.getMessage())
                    .errorCode("FILE_ERROR")
                    .costMs(cost)
                    .build();
        }
    }

    private Path resolveSafe(String relativePath) {
        Path resolved = workspaceRoot.resolve(relativePath).normalize();
        if (!resolved.startsWith(workspaceRoot)) {
            throw new IllegalArgumentException("路径越界，不允许访问工作空间之外的文件: " + relativePath);
        }
        return resolved;
    }

    private String doRead(Path target, MCPToolRequest request) throws IOException {
        if (!Files.exists(target)) {
            return "文件不存在: " + workspaceRoot.relativize(target);
        }
        if (Files.isDirectory(target)) {
            return "目标是目录，不是文件: " + workspaceRoot.relativize(target);
        }

        List<String> allLines = Files.readAllLines(target, StandardCharsets.UTF_8);
        Integer startLine = request.getParameter("startLine");
        Integer endLine = request.getParameter("endLine");

        int from = (startLine != null && startLine > 0) ? startLine - 1 : 0;
        int to = (endLine != null && endLine > 0) ? Math.min(endLine, allLines.size()) : allLines.size();

        if (from >= allLines.size()) {
            return "(空，起始行超出文件范围)";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("文件: ").append(workspaceRoot.relativize(target))
                .append(" (共 ").append(allLines.size()).append(" 行, 显示 ").append(from + 1).append("-").append(to).append(" 行)\n");

        for (int i = from; i < to; i++) {
            sb.append(String.format("%6d | %s%n", i + 1, allLines.get(i)));
        }
        return sb.toString();
    }

    private String doWrite(Path target, MCPToolRequest request) throws IOException {
        String content = request.getStringParameter("content");
        if (content == null) {
            content = "";
        }
        Files.createDirectories(target.getParent());
        Files.writeString(target, content, StandardCharsets.UTF_8);
        return "文件写入成功: " + workspaceRoot.relativize(target) + " (" + content.length() + " 字符)";
    }

    private String doAppend(Path target, MCPToolRequest request) throws IOException {
        String content = request.getStringParameter("content");
        if (content == null) {
            content = "";
        }
        Files.createDirectories(target.getParent());
        Files.writeString(target, content, StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.APPEND);
        return "内容已追加: " + workspaceRoot.relativize(target);
    }

    private String doEdit(Path target, MCPToolRequest request) throws IOException {
        String search = request.getStringParameter("search");
        String replace = request.getStringParameter("replace");
        if (search == null || search.isEmpty()) {
            return "search 参数不能为空";
        }

        if (!Files.exists(target)) {
            return "文件不存在: " + workspaceRoot.relativize(target);
        }

        String content = Files.readString(target, StandardCharsets.UTF_8);
        int count = countOccurrences(content, search);
        if (count == 0) {
            return "未找到匹配内容: \"" + search + "\"";
        }

        String newContent = content.replace(search, replace != null ? replace : "");
        Files.writeString(target, newContent, StandardCharsets.UTF_8);
        return "替换完成: " + workspaceRoot.relativize(target) + " (共替换 " + count + " 处)";
    }

    private int countOccurrences(String text, String search) {
        int count = 0;
        int idx = 0;
        while ((idx = text.indexOf(search, idx)) != -1) {
            count++;
            idx += search.length();
        }
        return count;
    }

    private String doList(Path target, MCPToolRequest request) throws IOException {
        if (!Files.exists(target)) {
            return "目录不存在: " + workspaceRoot.relativize(target);
        }
        if (!Files.isDirectory(target)) {
            return "目标不是目录: " + workspaceRoot.relativize(target);
        }

        Boolean recursive = request.getParameter("recursive");
        List<String> entries = new ArrayList<>();

        try (Stream<Path> stream = Files.list(target)) {
            stream.sorted(Comparator.comparing((Path p) -> !Files.isDirectory(p))
                            .thenComparing(p -> p.getFileName().toString()))
                    .forEach(p -> {
                        String name = p.getFileName().toString();
                        String relPath = workspaceRoot.relativize(p).toString().replace('\\', '/');
                        try {
                            if (Files.isDirectory(p)) {
                                entries.add(String.format("  DIR  %s/", relPath));
                            } else {
                                long size = Files.size(p);
                                entries.add(String.format("  FILE %-8d %s", size, relPath));
                            }
                        } catch (IOException e) {
                            entries.add("  ?    " + relPath);
                        }
                    });
        }

        if (entries.isEmpty()) {
            return "(空目录) " + workspaceRoot.relativize(target);
        }
        return "目录: " + workspaceRoot.relativize(target) + " (" + entries.size() + " 项)\n" + String.join("\n", entries);
    }

    private String doTree(Path target) throws IOException {
        if (!Files.exists(target)) {
            return "目录不存在: " + workspaceRoot.relativize(target);
        }

        StringBuilder sb = new StringBuilder();
        sb.append(workspaceRoot.relativize(target)).append("/\n");
        buildTree(target, "", sb);
        return sb.toString();
    }

    private void buildTree(Path dir, String prefix, StringBuilder sb) throws IOException {
        try (Stream<Path> stream = Files.list(dir)) {
            List<Path> children = stream.sorted(Comparator.comparing((Path p) -> !Files.isDirectory(p))
                            .thenComparing(p -> p.getFileName().toString()))
                    .toList();

            for (int i = 0; i < children.size(); i++) {
                Path child = children.get(i);
                boolean isLast = (i == children.size() - 1);
                String connector = isLast ? "└── " : "├── ";
                String childPrefix = isLast ? "    " : "│   ";
                String name = child.getFileName().toString();
                if (Files.isDirectory(child)) {
                    sb.append(prefix).append(connector).append(name).append("/\n");
                    buildTree(child, prefix + childPrefix, sb);
                } else {
                    sb.append(prefix).append(connector).append(name).append("\n");
                }
            }
        }
    }

    private String doMkdir(Path target) throws IOException {
        Files.createDirectories(target);
        return "目录创建成功: " + workspaceRoot.relativize(target);
    }

    private String doDelete(Path target, MCPToolRequest request) throws IOException {
        if (!Files.exists(target)) {
            return "文件或目录不存在: " + workspaceRoot.relativize(target);
        }

        Boolean recursive = request.getParameter("recursive");
        if (Files.isDirectory(target) && !Boolean.TRUE.equals(recursive)) {
            try (Stream<Path> stream = Files.list(target)) {
                if (stream.findAny().isPresent()) {
                    return "目录非空，需要设置 recursive=true 才能删除非空目录";
                }
            }
        }

        if (Files.isDirectory(target)) {
            deleteRecursively(target);
        } else {
            Files.delete(target);
        }
        return "删除成功: " + workspaceRoot.relativize(target);
    }

    private void deleteRecursively(Path dir) throws IOException {
        Files.walkFileTree(dir, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path d, IOException exc) throws IOException {
                Files.delete(d);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private String doSearch(Path target, MCPToolRequest request) throws IOException {
        String query = request.getStringParameter("search");
        if (query == null || query.isEmpty()) {
            return "search 参数不能为空";
        }

        if (!Files.exists(target)) {
            return "文件或目录不存在: " + workspaceRoot.relativize(target);
        }

        List<String> matches = new ArrayList<>();

        if (Files.isRegularFile(target)) {
            searchInFile(target, query, matches);
        } else {
            Files.walkFileTree(target, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    if (isTextFile(file)) {
                        searchInFile(file, query, matches);
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        }

        if (matches.isEmpty()) {
            return "未找到匹配内容: \"" + query + "\"";
        }

        return "搜索结果 (共 " + matches.size() + " 处匹配):\n" + String.join("\n", matches);
    }

    private void searchInFile(Path file, String query, List<String> matches) throws IOException {
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        String relPath = workspaceRoot.relativize(file).toString().replace('\\', '/');
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).contains(query)) {
                matches.add(String.format("%s:%d: %s", relPath, i + 1, lines.get(i).trim()));
            }
        }
    }

    private boolean isTextFile(Path file) {
        String name = file.getFileName().toString().toLowerCase();
        return name.endsWith(".java") || name.endsWith(".py") || name.endsWith(".js")
                || name.endsWith(".ts") || name.endsWith(".tsx") || name.endsWith(".jsx")
                || name.endsWith(".xml") || name.endsWith(".yaml") || name.endsWith(".yml")
                || name.endsWith(".json") || name.endsWith(".properties") || name.endsWith(".md")
                || name.endsWith(".txt") || name.endsWith(".csv") || name.endsWith(".html")
                || name.endsWith(".css") || name.endsWith(".sql") || name.endsWith(".sh")
                || name.endsWith(".bat") || name.endsWith(".gradle") || name.endsWith(".toml")
                || name.endsWith(".cfg") || name.endsWith(".ini") || name.endsWith(".conf")
                || name.endsWith(".go") || name.endsWith(".rs") || name.endsWith(".c")
                || name.endsWith(".cpp") || name.endsWith(".h") || name.endsWith(".hpp");
    }

    private String doExists(Path target) {
        boolean exists = Files.exists(target);
        String type = "";
        if (exists) {
            type = Files.isDirectory(target) ? " (目录)" : " (文件, " + target.toFile().length() + " bytes)";
        }
        return (exists ? "存在" : "不存在") + ": " + workspaceRoot.relativize(target) + type;
    }

    private MCPToolResponse error(String message) {
        return MCPToolResponse.builder()
                .success(false)
                .toolId(TOOL_ID)
                .errorMessage(message)
                .errorCode("INVALID_PARAMS")
                .build();
    }
}
