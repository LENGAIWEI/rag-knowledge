package org.example.ragknowledge.index;

import jakarta.annotation.PostConstruct;
import org.example.ragknowledge.config.KnowledgeProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 知识库存储：段落内存列表 + JSON 落盘 + BM25 索引缓存。
 *
 * <p>并发约定（上传/删除与检索会并发发生）：</p>
 * <ul>
 *   <li>段落列表用 {@link CopyOnWriteArrayList}，读多写少场景下检索不加锁也能拿到一致快照；</li>
 *   <li>每次写入自增 {@code version}，BM25 索引按 version <b>惰性重建</b>：
 *       检索时才构建，连续多次上传不会重复建索引；不引入定时任务，也不阻塞写入方。</li>
 * </ul>
 */
@Component
public class KnowledgeStore {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeStore.class);

    /** 文档统计（用于前端展示文档清单） */
    public record DocStat(String source, int count) {
    }

    /** 持久化文件结构；version 用于后续格式升级时做兼容判断 */
    record StoreFile(int version, List<Chunk> chunks) {
    }

    /** 当前落盘格式版本 */
    private static final int STORE_VERSION = 2;

    private final List<Chunk> chunks = new CopyOnWriteArrayList<>();
    private final AtomicInteger sequence = new AtomicInteger();
    private final AtomicLong version = new AtomicLong();

    private final Object bm25Lock = new Object();
    private volatile Bm25Index bm25Index;
    private volatile long bm25Version = -1;

    private final KnowledgeProperties properties;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public KnowledgeStore(KnowledgeProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    void init() {
        load();
    }

    // ==================== 读取 ====================

    /** 段落快照，调用方可安全遍历 */
    public List<Chunk> all() {
        return List.copyOf(chunks);
    }

    public int size() {
        return chunks.size();
    }

    /** 分配一个全局唯一的块序号（同时作为检索主键） */
    public int nextOrdinal() {
        return sequence.incrementAndGet();
    }

    /** 按 ordinal 建索引，供检索结果回查原文 */
    public Map<Integer, Chunk> byOrdinal() {
        Map<Integer, Chunk> map = new HashMap<>(chunks.size() * 2);
        for (Chunk chunk : chunks) {
            map.put(chunk.ordinal(), chunk);
        }
        return map;
    }

    /** 文档清单（按入库顺序） */
    public List<DocStat> documents() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Chunk chunk : chunks) {
            counts.merge(chunk.source(), 1, Integer::sum);
        }
        List<DocStat> stats = new ArrayList<>(counts.size());
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            stats.add(new DocStat(entry.getKey(), entry.getValue()));
        }
        return stats;
    }

    public Path storeFile() {
        String raw = properties.getStorePath();
        if (raw == null || raw.isBlank()) {
            raw = "data/knowledge-base.json";
        }
        return Path.of(raw).toAbsolutePath().normalize();
    }

    /**
     * BM25 索引：按需构建并缓存，语料变化后自动重建。
     * 检索路径上只读，写入路径上不阻塞。
     */
    public Bm25Index bm25() {
        long current = version.get();
        Bm25Index cached = bm25Index;
        if (cached != null && bm25Version == current) {
            return cached;
        }
        synchronized (bm25Lock) {
            if (bm25Index == null || bm25Version != version.get()) {
                bm25Index = Bm25Index.build(List.copyOf(chunks),
                        properties.getHybrid().getBm25K1(),
                        properties.getHybrid().getBm25B());
                bm25Version = version.get();
            }
            return bm25Index;
        }
    }

    // ==================== 写入 ====================

    /** 批量入库并落盘 */
    public void addAll(List<Chunk> newChunks) {
        if (newChunks == null || newChunks.isEmpty()) {
            return;
        }
        chunks.addAll(newChunks);
        version.incrementAndGet();
        save();
    }

    /** 按来源删除某个文档的全部段落，返回删除条数 */
    public int removeBySource(String source) {
        if (source == null || source.isBlank()) {
            return 0;
        }
        int before = chunks.size();
        chunks.removeIf(chunk -> source.equals(chunk.source()));
        int removed = before - chunks.size();
        if (removed > 0) {
            version.incrementAndGet();
            save();
        }
        return removed;
    }

    public void clear() {
        chunks.clear();
        sequence.set(0);
        version.incrementAndGet();
        deleteStoreFile();
    }

    // ==================== 持久化 ====================

    private void load() {
        Path file = storeFile();
        if (!Files.isRegularFile(file)) {
            log.info("知识库文件不存在，以空知识库启动：{}", file);
            return;
        }
        try {
            String json = Files.readString(file, StandardCharsets.UTF_8);
            StoreFile store = objectMapper.readValue(json, StoreFile.class);
            if (store == null || store.chunks() == null) {
                return;
            }
            int maxOrdinal = 0;
            int skipped = 0;
            for (Chunk chunk : store.chunks()) {
                if (chunk == null || chunk.text() == null || chunk.vector() == null || chunk.vector().length == 0) {
                    skipped++;
                    continue;
                }
                String source = (chunk.source() == null || chunk.source().isBlank()) ? "未知文档" : chunk.source();
                // v1 格式没有 section 字段，反序列化后为 null，这里统一按空章节处理（兼容旧数据）
                chunks.add(new Chunk(chunk.text(), source, chunk.section(), chunk.ordinal(), chunk.vector()));
                maxOrdinal = Math.max(maxOrdinal, chunk.ordinal());
            }
            sequence.set(maxOrdinal);
            version.incrementAndGet();
            log.info("知识库已从 {} 载入 {} 个段落（跳过 {} 条损坏记录）", file, chunks.size(), skipped);
        } catch (Exception e) {
            log.warn("知识库文件解析失败，已忽略该文件：{}（原因：{}）", file, e.getMessage());
        }
    }

    private synchronized void save() {
        Path file = storeFile();
        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            StoreFile store = new StoreFile(STORE_VERSION, List.copyOf(chunks));
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, objectMapper.writeValueAsString(store), StandardCharsets.UTF_8);
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (Exception atomicMoveFailed) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            log.error("知识库落盘失败：{}", e.getMessage());
        }
    }

    private void deleteStoreFile() {
        Path file = storeFile();
        try {
            Files.deleteIfExists(file);
            Files.deleteIfExists(file.resolveSibling(file.getFileName() + ".tmp"));
        } catch (Exception e) {
            log.warn("删除知识库文件失败：{}", e.getMessage());
        }
    }
}
