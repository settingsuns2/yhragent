-- ragent v1.2 -> v1.3 升级脚本
-- t_knowledge_vector 表：新增全文检索列 + GIN 索引（配合 pg_jieba 实现中文稀疏检索）
-- 前置条件：需先安装 pg_jieba 扩展并配置 jiebacfg 文本搜索配置

-- 1. 添加 tsvector 列（从 content 自动生成）
ALTER TABLE t_knowledge_vector ADD COLUMN tsv tsvector;

-- 2. 创建 GIN 索引加速全文检索
CREATE INDEX idx_kv_tsv ON t_knowledge_vector USING gin(tsv);

-- 3. 为已有数据回填 tsvector（使用 jiebacfg 分词配置，如果已安装 pg_jieba）
-- 如果未安装 pg_jieba，使用 'simple' 配置作为降级方案
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_ts_config WHERE cfgname = 'jiebacfg') THEN
        UPDATE t_knowledge_vector SET tsv = to_tsvector('jiebacfg', coalesce(content, ''));
        RAISE NOTICE '使用 jiebacfg 回填 tsvector 完成';
    ELSE
        UPDATE t_knowledge_vector SET tsv = to_tsvector('simple', coalesce(content, ''));
        RAISE NOTICE 'pg_jieba 未安装，使用 simple 配置回填 tsvector';
    END IF;
END $$;

-- 4. （可选）如果希望后续 INSERT/UPDATE 自动维护 tsvector，可使用触发器
-- 此处采用在应用层维护的方式，不创建触发器
