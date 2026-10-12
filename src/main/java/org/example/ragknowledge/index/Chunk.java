package org.example.ragknowledge.index;

/**
 * 知识块：段落原文 + 元数据 + 向量。
 *
 * <p>{@code ordinal} 由全局自增序列生成，在知识库生命周期内<b>唯一且稳定</b>，
 * 因此直接用它当作文档主键（BM25 倒排表、RRF 融合、重排结果都用它做标识），
 * 删除文档后不会与其它段落冲突。</p>
 *
 * @param text    段落原文（送入大模型的内容）
 * @param source  来源文档名
 * @param section 章节路径（Markdown 标题层级，如「员工手册 &gt; 请假制度」），无标题时为 null
 * @param ordinal 全局唯一序号，同时用作检索主键
 * @param vector  向量化结果
 */
public record Chunk(String text, String source, String section, int ordinal, double[] vector) {

    /** 章节路径的展示用文本，没有章节时返回空串（不返回 null，避免前端判空） */
    public String sectionOrEmpty() {
        return section == null ? "" : section;
    }

    /** 用于 BM25 建索引的文本：正文 + 章节路径，让「查询章节名」也能命中该章节下的段落 */
    public String indexableText() {
        return section == null || section.isBlank() ? text : section + "\n" + text;
    }
}
