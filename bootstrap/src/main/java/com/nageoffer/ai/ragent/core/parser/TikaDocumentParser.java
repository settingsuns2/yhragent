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

package com.nageoffer.ai.ragent.core.parser;

import com.nageoffer.ai.ragent.framework.exception.ServiceException;
import com.nageoffer.ai.ragent.ingestion.domain.context.ImageResource;
import lombok.extern.slf4j.Slf4j;
import org.apache.tika.config.TikaConfig;
import org.apache.tika.extractor.EmbeddedDocumentExtractor;
import org.apache.tika.extractor.ParsingEmbeddedDocumentExtractor;
import org.apache.tika.io.TikaInputStream;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.Parser;
import org.apache.tika.parser.pdf.PDFParserConfig;
import org.apache.tika.sax.BodyContentHandler;
import org.springframework.stereotype.Component;
import org.xml.sax.Attributes;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.AttributesImpl;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Component
public class TikaDocumentParser implements DocumentParser {

    private static final TikaConfig TIKA_CONFIG = TikaConfig.getDefaultConfig();

    @Override
    public String getParserType() {
        return ParserType.TIKA.getType();
    }

    @Override
    public ParseResult parse(byte[] content, String mimeType, Map<String, Object> options) {
        if (content == null || content.length == 0) {
            return ParseResult.ofText("");
        }

        try (TikaInputStream tis = TikaInputStream.get(content)) {
            Metadata metadata = new Metadata();
            if (mimeType != null) {
                metadata.set(Metadata.CONTENT_TYPE, mimeType);
            }

            PDFParserConfig pdfConfig = new PDFParserConfig();
            pdfConfig.setExtractInlineImages(true);
            pdfConfig.setExtractUniqueInlineImagesOnly(false);

            ParseContext parseContext = new ParseContext();
            parseContext.set(Parser.class, new AutoDetectParser(TIKA_CONFIG));
            parseContext.set(PDFParserConfig.class, pdfConfig);

            List<ImageResource> imageResources = new ArrayList<>();
            AtomicInteger imageCounter = new AtomicInteger(0);
            PlaceholderInsertingHandler handler = new PlaceholderInsertingHandler(-1);

            EmbeddedDocumentExtractor extractor = new ParsingEmbeddedDocumentExtractor(parseContext) {
                @Override
                public boolean shouldParseEmbedded(Metadata m) {
                    String ct = m.get(Metadata.CONTENT_TYPE);
                    return ct != null && ct.startsWith("image/");
                }

                @Override
                public void parseEmbedded(InputStream stream, org.xml.sax.ContentHandler handler, Metadata m, boolean outputHtml) throws SAXException {
                    try {
                        ByteArrayOutputStream baos = new ByteArrayOutputStream();
                        stream.transferTo(baos);
                        byte[] imageData = baos.toByteArray();
                        if (imageData.length == 0) {
                            return;
                        }

                        String ct = m.get(Metadata.CONTENT_TYPE);
                        if (ct == null) {
                            ct = "image/png";
                        }

                        int idx = imageCounter.incrementAndGet();
                        String imageId = "IMG_" + String.format("%03d", idx);
                        String placeholder = "[" + imageId + "]";

                        imageResources.add(ImageResource.builder()
                                .imageId(imageId)
                                .imageData(imageData)
                                .mimeType(ct)
                                .placeholder(placeholder)
                                .build());

                        handler.characters(placeholder.toCharArray(), 0, placeholder.length());
                        handler.characters("\n".toCharArray(), 0, 1);
                    } catch (Exception e) {
                        log.warn("提取嵌入图片失败: {}", e.getMessage());
                    }
                }
            };
            parseContext.set(EmbeddedDocumentExtractor.class, extractor);

            AutoDetectParser parser = new AutoDetectParser(TIKA_CONFIG);
            parser.parse(tis, handler, metadata, parseContext);

            String text = handler.toString();
            String cleaned = TextCleanupUtil.cleanup(text);

            if (!imageResources.isEmpty()) {
                log.info("从文档中提取到 {} 张嵌入图片", imageResources.size());
            }

            return ParseResult.of(cleaned, Map.of(), imageResources);
        } catch (Exception e) {
            log.error("Tika 解析失败，MIME 类型: {}", mimeType, e);
            throw new ServiceException("文档解析失败: " + e.getMessage());
        }
    }

    @Override
    public String extractText(InputStream stream, String fileName) {
        try {
            AutoDetectParser parser = new AutoDetectParser(TIKA_CONFIG);
            BodyContentHandler handler = new BodyContentHandler(-1);
            Metadata metadata = new Metadata();
            if (fileName != null) {
                metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, fileName);
            }
            ParseContext parseContext = new ParseContext();
            parseContext.set(Parser.class, new AutoDetectParser(TIKA_CONFIG));
            parser.parse(stream, handler, metadata, parseContext);
            return TextCleanupUtil.cleanup(handler.toString());
        } catch (Exception e) {
            log.error("从文件中提取文本内容失败: {}", fileName, e);
            throw new ServiceException("解析文件失败: " + fileName);
        }
    }

    @Override
    public boolean supports(String mimeType) {
        return mimeType != null && !mimeType.startsWith("text/markdown");
    }

    private static class PlaceholderInsertingHandler extends BodyContentHandler {
        public PlaceholderInsertingHandler(int writeLimit) {
            super(writeLimit);
        }
    }
}
