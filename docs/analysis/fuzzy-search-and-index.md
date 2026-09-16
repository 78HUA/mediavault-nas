# 前导通配符 LIKE 与索引：为什么模糊搜索要交给 ES

> 本文是一次**实测**记录，回答一个具体问题：
> 给 `name` / `singer` 加索引，能不能让项目的模糊搜索快起来？
> 结论：**不能**，而且原因与"索引建得对不对"无关。

## 一、项目里的模糊搜索是怎么写的

`MusicServiceImpl.fuzzySearch` / `fuzzySearchName` / `fuzzySearchSinger`
（以及 `MusicManageServiceImpl.fuzzySearch`）都会把关键字拼成 `%关键字%` 再查询
（`MusicServiceImpl.java:49/62/75`）：

| 方法 | SQL |
|---|---|
| `fuzzySearch` | `SELECT * FROM music WHERE name LIKE ? OR singer LIKE ? ORDER BY gmt_create DESC` |
| `fuzzySearchName` | `SELECT * FROM music WHERE name LIKE ? ORDER BY gmt_create DESC` |
| `fuzzySearchSinger` | `SELECT * FROM music WHERE singer LIKE ? ORDER BY gmt_create DESC` |

传进去的参数形如 `"%月亮%"`。

## 二、实验：只改通配符位置，看索引能否被用上

给 `singer` 建一个普通索引，然后用**同一列、同一个索引**跑两条只差 `%` 位置的查询
（测试表 5 万行，`ANALYZE TABLE` 已刷新统计）：

| 查询 | 加索引前 | 加索引后 |
|---|---|---|
| `WHERE singer LIKE '%Chou%'` | `type=ALL`、`key=NULL`、rows 49840 | `type=ALL`、`key=NULL`、rows 49914 —— **毫无变化** |
| `WHERE singer LIKE 'Chou%'` | `type=ALL`、`key=NULL` | `type=range`、`key=idx_singer`、rows 49840 → **1**、`Using index condition` |

实验完成后该索引已删除 —— 它对项目真实使用的 `%kw%` 查询毫无帮助，留着只会增加写入维护成本。

## 三、结论：索引不是"加了就有用"

**同一个索引，只因为 `%` 在前面，就完全用不上。**

原因：B+ 树索引是按列值的**前缀顺序**组织的。
- `LIKE 'Chou%'` 要找的是"以 Chou 开头"的一段连续值，在索引里是一段连续区间，
  顺着索引走一小段即可 → `range`；
- `LIKE '%Chou%'` 要找的是"任意位置包含 Chou"，这些行在索引里**分散在整棵树的各处**，
  只能把叶子节点逐个扫一遍 —— 那还不如直接扫全表。

打个比方：索引像书的目录。查"第 3 章"能靠目录直接翻到；
查"书里所有提到『月亮』的地方"，目录一点忙都帮不上，只能一页页翻。

## 四、那模糊搜索该怎么办

项目里**本来就有 Elasticsearch**（`SearchService` 与其 `yunshu_music_lyric` 索引），
但 `/music/search_v2` 这个入口前端从未调用过。

ES 用的是**倒排索引**：把文本切成词，为每个词记录"哪些文档包含它"。
查"包含 Chou 的文档"正是它的本职工作，前导通配符在它这里不是问题。

要补的是（见实施大纲后续阶段）：

- 中文分词与 mapping 显式化 —— 当前 `content` 走默认分词器，中文会被切成单字；
- 批量写入与分页 —— 当前 `reInit` 是「逐条 HTTP 拉歌词 + 逐条写 ES」的双重 N+1；
- 把搜索接到前端，并在 ES 未启用时给出明确状态，而不是静默返回空数组。

## 五、面试要点

| 会被问 | 怎么答 |
|---|---|
| 加了索引为什么还是慢？ | 要看查询形态。前导通配符 LIKE 用不上 B+ 树索引 —— 我实测过：同一列同一个索引，`LIKE 'Chou%'` 走 `range`、`LIKE '%Chou%'` 仍是全表扫描。 |
| 那模糊搜索怎么解决？ | 交给 ES 的倒排索引。项目里本来就有 ES，只是前端没接上；我把它接通并补了中文分词与批量写入。 |
| 全表扫描一定不可接受吗？ | 看数据量与 QPS。5 万行、低频搜索时全表扫描可以接受；数据量到百万级或高频搜索才必须上搜索引擎。不该"见慢就上中间件"。 |
| 那 `ORDER BY gmt_create DESC` 的 filesort 呢？ | 那是另一类问题（排序而非匹配），能靠索引解决，但只在带 LIMIT 的分页查询下才生效 —— 见 `docs/optimization-plan.md` 的 B3。 |
