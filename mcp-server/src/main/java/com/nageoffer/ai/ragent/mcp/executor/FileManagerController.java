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

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/files")
@RequiredArgsConstructor
public class FileManagerController {

    private final FileManagerExecutor fileManagerExecutor;

    @Value("${rag.file-manager.workspace:./workspace}")
    private String workspace;

    @PostMapping("/upload")
    public ResponseEntity<Map<String, Object>> upload(
            @RequestPart("file") MultipartFile file,
            @RequestParam(value = "subDir", required = false, defaultValue = "") String subDir
    ) throws IOException {
        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "文件为空"));
        }

        String originalFilename = file.getOriginalFilename();
        if (originalFilename == null || originalFilename.isBlank()) {
            originalFilename = "unnamed_file";
        }

        Path workspaceRoot = Paths.get(workspace).toAbsolutePath().normalize();
        Path targetDir = subDir != null && !subDir.isBlank() ? workspaceRoot.resolve(subDir) : workspaceRoot;
        Files.createDirectories(targetDir);

        String safeName = sanitizeFilename(originalFilename);
        Path targetFile = targetDir.resolve(safeName);

        int counter = 1;
        while (Files.exists(targetFile)) {
            int dotIdx = safeName.lastIndexOf('.');
            String baseName = dotIdx > 0 ? safeName.substring(0, dotIdx) : safeName;
            String ext = dotIdx > 0 ? safeName.substring(dotIdx) : "";
            targetFile = targetDir.resolve(baseName + "_" + counter + ext);
            counter++;
        }

        file.transferTo(targetFile.toFile());

        String relativePath = workspaceRoot.relativize(targetFile).toString().replace('\\', '/');

        Map<String, Object> result = new HashMap<>();
        result.put("name", originalFilename);
        result.put("path", relativePath);
        result.put("size", file.getSize());
        result.put("contentType", file.getContentType());
        return ResponseEntity.ok(result);
    }

    @PostMapping("/upload-batch")
    public ResponseEntity<List<Map<String, Object>>> uploadBatch(
            @RequestPart("files") List<MultipartFile> files,
            @RequestParam(value = "subDir", required = false, defaultValue = "") String subDir
    ) throws IOException {
        List<Map<String, Object>> results = new ArrayList<>();
        for (MultipartFile file : files) {
            if (file.isEmpty()) continue;

            String originalFilename = file.getOriginalFilename();
            if (originalFilename == null || originalFilename.isBlank()) {
                originalFilename = "unnamed_file";
            }

            Path workspaceRoot = Paths.get(workspace).toAbsolutePath().normalize();
            Path targetDir = subDir != null && !subDir.isBlank() ? workspaceRoot.resolve(subDir) : workspaceRoot;
            Files.createDirectories(targetDir);

            String safeName = sanitizeFilename(originalFilename);
            Path targetFile = targetDir.resolve(safeName);

            int counter = 1;
            while (Files.exists(targetFile)) {
                int dotIdx = safeName.lastIndexOf('.');
                String baseName = dotIdx > 0 ? safeName.substring(0, dotIdx) : safeName;
                String ext = dotIdx > 0 ? safeName.substring(dotIdx) : "";
                targetFile = targetDir.resolve(baseName + "_" + counter + ext);
                counter++;
            }

            file.transferTo(targetFile.toFile());

            String relativePath = workspaceRoot.relativize(targetFile).toString().replace('\\', '/');

            Map<String, Object> item = new HashMap<>();
            item.put("name", originalFilename);
            item.put("path", relativePath);
            item.put("size", file.getSize());
            item.put("contentType", file.getContentType());
            results.add(item);
        }
        return ResponseEntity.ok(results);
    }

    @GetMapping("/download/{*filePath}")
    public ResponseEntity<Resource> download(@PathVariable String filePath) throws IOException {
        Path workspaceRoot = Paths.get(workspace).toAbsolutePath().normalize();
        Path target = workspaceRoot.resolve(filePath.substring(1)).normalize();

        if (!target.startsWith(workspaceRoot) || !Files.exists(target) || Files.isDirectory(target)) {
            return ResponseEntity.notFound().build();
        }

        Resource resource = new FileSystemResource(target);
        String contentType = Files.probeContentType(target);
        if (contentType == null) {
            contentType = "application/octet-stream";
        }

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(contentType))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + target.getFileName().toString() + "\"")
                .body(resource);
    }

    private String sanitizeFilename(String filename) {
        return filename.replaceAll("[\\\\/:*?\"<>|]", "_");
    }
}
