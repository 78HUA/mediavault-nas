# MediaVault

> 一个私人云盘 / 家庭流媒体服务器：把本机的视频与音乐变成可点播的流媒体服务，并额外提供 FTP 与 WebDAV 访问。
>
> **本仓库是基于开源项目 [itning/yunshu-nas](https://github.com/itning/yunshu-nas) 的深度优化改造**，
> 遵循 Apache-2.0，原项目版权归原作者所有（见 [LICENSE](LICENSE)）。
> 改造只动"外衣"与优化点，**包名与原作者署名一律保留**。

---

## 这篇 README 为什么这么写

这不是换皮仓库。下面每一条优化，都对应一次提交（`git log` 可逐条回溯），
并且**都有改前 / 改后的实测数字** —— 没有实测的改动我不会写进来。

改造的出发点是：上游有**真实存在、可复现、但作者没做完**的空间。所以本文的重点不是"我加了什么技术"，
而是"**我先量出了什么问题，再动手，最后又量了一遍**"。

### 优化成效

| 优化点 | 改前 | 改后 | 幅度 |
|---|---|---|---|
| `findByNameAndSingerAndType`（三条件等值） | `type=ALL`、扫 49928 行、20.1 ms | `type=ref`、rows **2**、0.064 ms | **约 300×** |
| 音乐列表接口响应 | **17.93 MB** / 0.45 s | **36 KB** / 0.014 s | **约 500×** |
| 转码进度 WebSocket 消息量 | 200 条 / 30 s | 54 条 / 30 s | −73%（字节 −93%） |
| 应用 jar 体积 | 91,963,604 B | 87,193,783 B | −4.55 MB |
| 应用启动耗时 | 5.349 s | 3.187 s | −40% |

数据基于 5 万行测试数据（5000 个歌名 / 200 个歌手 / 4 种音频类型），统计经 `ANALYZE TABLE` 刷新。
完整路线图与逐条实测见 [`docs/optimization-plan.md`](docs/optimization-plan.md)。

---

## 三个最有价值的发现

### 1. 视频转码链是**死代码**，而且从未成功产出过一个文件

`VideoTransformHandler` 被注入到 Controller 后**从未被调用**，`put()` / `status()` 全仓零调用者，
前端也从不请求 m3u8（播放走的是原始文件直链）—— 整套 HLS 转码能力等于白写。

更关键的是：切片命令用的 `-vbsf` 选项**已被新版 ffmpeg 移除**（代码注释里写着"ffmpeg 4.1.3 版本测试通过"），
执行时退出码 8、零产物；而 `CommandUtils` **不检查退出码**，于是**失败被当成成功上报**
（任务计数照常 +1）。再叠加"m3u8 文件存在即已完成"的判据 —— 产物永远不存在，
该文件可以无限重复提交、每次失败、每次报成功。

**做了什么**：接通触发入口 → 修复切片命令 → 让失败可见（`failedCount` + ERROR 堆栈）→
清理残缺产物（使失败后可重转）→ 修正进程治理（超时 / 退出码 / 强制回收）→ **最后把前端也接上了**（播放页可在原文件 / 转码版本之间切换，一键转码后自动切换）。

后端修好并不等于功能可用：只要产物没人消费，用户视角下 mkv 依然打不开。

### 2. Undertow 替换在 Spring Boot 4.1.1 上**根本做不到**

上游 `pom.xml` 排除了 Tomcat 并引入 Undertow，意图是换容器。实测后发现这是三层问题叠加：

1. exclusion **打在错误的 artifact 上** —— Tomcat 是从 `spring-boot-starter-websocket`
   → `spring-boot-starter-webmvc` → `spring-boot-starter-tomcat` 绕进来的（Boot 4.x 新增了 webmvc 中间层）；
2. 就算补上 exclusion 让 Undertow 生效，应用会**直接启动失败** —— 作者钉的 `4.0.0-M1`
   是 Boot 4.0 线的里程碑版本，与 4.1.1 的 `PropertyMapper` API 不兼容；
3. 根因是 **Boot 4.x 已不再支持 Undertow**：4.1.1 的 BOM 只管理 tomcat / jetty / netty，
   而该 starter 在 Maven Central 的版本止于 `4.0.0-M1`。

**做了什么**：不做"硬把 Undertow 塞回来"这种表面功夫，而是清理掉这个永远不会生效的依赖（省 4.55 MB），
并把原本只对 Undertow 生效（因此从未运行过）的 WebSocket 定制器改成对当前容器真正生效的写法。

### 3. 配置广播：**做完作者没做完的那一半**

项目里**本来就有** `ConfigChangeEvent` + `ApplicationListener` 的机制 —— `FtpConfig`、`ElasticsearchConfig`、
`DataSourceConfig` 都监听它来重建自身。**但这个机制只在本机生效**：在实例 A 改配置，实例 B 毫不知情。

**做了什么**：用 Redis 发布/订阅把它广播到所有实例。收到远端变更的实例会在本机落库，
并触发同样的组件重建流程。

**实测（两个实例，各带独立配置库，只改 A 的配置）**：

```
A 日志：已广播配置变更：NasMusicProperties
A 日志：add music data source name:local          ← A 本机重建
B 日志：add music data source name:local          ← B 的组件也重建了
B 日志：已应用来自实例 232150d8-… 的配置变更        ← 在 Redis 监听线程上执行
B 的 GET /api/setting/datasource → 返回完整新配置  ← 未重启、未手工同步
```

回环防护：用实例 ID 忽略自己发出的消息，并用标记位避免"落库时再次广播"在实例间来回弹。

---

## 技术栈

| | |
|---|---|
| 语言 / 运行时 | Java 21 |
| 框架 | Spring Boot 4.1.1（内嵌 Tomcat）、Spring JDBC（JdbcTemplate） |
| 数据库 | MySQL 8 / SQLite（可选）、Elasticsearch 9（可选） |
| 缓存 / 协调 | Redis（可选，用于分布式锁与配置广播） |
| 消息队列 | RabbitMQ（可选，用于转码任务队列） |
| 其他 | HikariCP、Caffeine、ffmpeg、Angular 22 + ng-zorro（前端） |

> **"可选"是真的可选**：MySQL / ES / Redis / RabbitMQ 全都可以不配。
> 应用自身的设置存在内嵌 SQLite 里，**零配置即可启动** —— 这是本项目的特性，改造过程中一直刻意保持。
>
> 但"降级"比看上去难，这里踩过一个坑，如实记下来：Redis 的降级分支一开始是**失效**的。
> 连接自检包了 `try/catch`，紧随其后的订阅启动却没有；而 `RedisMessageListenerContainer.start()`
> 会**同步**取一次连接，不可达时直接抛异常。异常穿出 `@PostConstruct` 后 Spring 会取消整个刷新，
> 结果是**应用连首页都打不开** —— 不是"Redis 功能不可用"。这个分支读代码看不出来，
> 是实测"先配好 Redis、再把 Redis 关掉"才暴露的。
> 修复后三种场景实测（均 `/health` 200）：
>
> | 场景 | 结果 |
> |---|---|
> | Redis 已配置、**不可达** | 启动成功（4.834 s）+ `WARN`，本实例不启用配置广播 |
> | Redis 已配置、**可达** | 启动成功（5.15 s），自检 `PONG`，已订阅频道 |
> | **从未配置**（全新目录） | 启动成功（4.222 s），自动创建 `yunshu-nas.db` |
>
> **这不是孤例，而是同一类缺陷的第一次现身。** 后来排查 Elasticsearch 时又撞到一次：
> `ElasticsearchConfig.init()` 也是在做完配置后**立刻发网络请求建索引**，同样没有失败边界。
> 后果更难受 —— 在设置页勾一下「开启 Elasticsearch」而 ES 没起，保存当场报 500（**配置却已落库**），
> 之后每次启动都 `Application run failed`；而**设置界面本身由这个应用提供**，等于死锁，
> 只能删掉 `yunshu-nas.db`（连同 MySQL、ffmpeg 的设置一起丢）。
>
> 归纳出来的模式是：**初始化阶段做网络 IO，却没考虑失败**。
> 两处都已按"失败只 WARN + 彻底降级（`enabled()==false`，调用点全走 no-op）"修掉 ——
> 关键是要降得**彻底**：只记日志不清模板的话，后续每次调用都抛异常，
> 会把"可选中间件的问题"变成"核心业务不能用"。

## 功能

- [x] 点播视频文件（文件浏览 / 转码为 HLS / 播放）
- [x] 点播音频文件（增删改查 / 歌词 / 封面 / 搜索），支持 WebDAV（`path:/webdav`）
- [x] 远程下载（aria2c，可不用）
- [x] 提供 FTP 服务
- [ ] 图片在线查看
- [ ] 资料加密
- [ ] 文件分布式存储
- [ ] axel 下载支持

## 快速开始

```bash
export JAVA_HOME=/path/to/jdk-21
mvn -DskipTests package
java -jar nas-deploy/target/yunshu-nas-2.2.5.RELEASE.jar
```

启动后访问 `http://127.0.0.1:8888`，在设置页配置业务数据库与数据源（不配也能启动）。

Docker：

```bash
docker run --name mediavault -p 8888:8888 -e SERVER_URL=http://localhost:8888 mediavault-nas:latest
```

---

## 优化清单

按主题分组。每条的**问题 → 改动 → 实测**都写在对应提交的 message 里。

### 并发与稳定性

| 改动 | 说明 |
|---|---|
| 转码线程池重构 | 无界队列 + `core==max` + 未设拒绝策略 → **有界队列 + AbortPolicy + 队列满返回 429 形成背压** |
| 去重改为原子操作 | `containsKey → queue.contains(O(n)) → put` 三步非原子 → `ConcurrentHashMap.newKeySet().add()` 单次原子 |
| 分布式锁 | 转码去重跨实例：`SET NX PX` 加锁 + **Lua 脚本校验持有者后释放**；Redis 不可用时放行、退化为单机去重 |
| 优雅停机 | 新增 `@PreDestroy`：先停止接受新任务、等待在途任务、超时才强制中断 |
| 子进程治理 | 新增 `ProcessRunner`：**超时 / 退出码 / 关闭 stdin / 中断传播 / 强制回收**；实测卡死命令 2 秒被回收、`cmd /c pause` 23 ms 返回 |
| 进度推送 | 会话表普通 `HashMap` → 并发容器、补 `onClose` 清理、阻塞 `sendText` → 异步发送、进度按 500 ms 节流 |
| WebSocket 缓冲 | 原本的定制器只对 Undertow 生效（从未运行），改为对当前容器生效并放宽文本缓冲上限 |

### SQL

| 改动 | 说明 |
|---|---|
| 删除冗余索引 | `music_id` 上同时有唯一索引与普通索引；删除前后执行计划**逐列完全一致**，证明优化器从未用过它 |
| 新增联合索引 | `(name, singer, type)`，对应三条件等值查询：`ALL` → `ref`，rows 49928 → 2 |
| 新增 `gmt_create` 索引 | 消除 `ORDER BY` 的 filesort —— **实测无 LIMIT 时计划不变**，加 `LIMIT` 后才转为倒序索引扫描 |
| 新增分页接口 | 列表接口原本一次返回全表；**新增** `/api/music/page`（不改老接口契约，避免打坏已发布前端），每页条数设上限 |

### 架构与依赖

| 改动 | 说明 |
|---|---|
| Undertow 依赖清理 | 见上文"发现 2" |
| Redis 可降级引入 | 与项目既有配置模式一致（存内嵌 SQLite、设置接口读写、支持热重建） |
| 配置广播 | 见上文"发现 3" |
| 转码队列切换 MQ | 内存队列 → RabbitMQ（可选，默认关闭）：任务持久化、死信队列、幂等 |

MQ 的实测结论：正常任务 ack 出产物；失败任务进死信队列；
**应用停机期间投递的任务，重启后被自动消费**（日志显示跑在 MQ 消费线程上）；
同一任务重复投递 2 次被幂等跳过，实际转码次数仍为 1。

### 转码产物的最后一环（前端接入）

修好后端之后还剩一个问题：**产物没人消费**。前端播放器一直播的是原始文件直链（`/video/{path}`），
从不请求 m3u8 —— 所以即使转码成功，mkv / avi 这些浏览器放不了的格式，用户依然打不开。

补上的东西：

| 层 | 改动 |
|---|---|
| 后端 | 新增 `GET /transcode/info?location=`：只读地返回产物是否就绪、播放地址、是否正在转码。**刻意不调用 `getWriteDir()`** —— 那个方法会顺手 `mkdirs`，一个查询接口不该有副作用 |
| 前端 | 播放页加"原文件 / 转码版本 / 转码后播放"三个控件；原文件播放失败时给出提示；提交转码后轮询，就绪即自动切换 |
| 体积 | `hls.js` 用**动态 import**：它压缩后约 586 KB，而列表页、音乐页根本用不到，打进主包等于让所有人先下载再说话。实测主包只 +4.5 KB，hls 独立成 chunk |
| 离线可用 | 不用 dplayer 内置的 `type:'hls'` —— 那个会在运行时从 **CDN** 拉 hls.js，而 NAS 常常是内网离线环境，拉不到就是一片黑 |

**实测结论（在浏览器里逐项验的）**：

| 场景 | 结果 |
|---|---|
| 打开 `.avi`（浏览器确实放不了） | `video.error.code = 4`（`SRC_NOT_SUPPORTED`），页面出现失败提示，`转码版本` 按钮正确置灰 |
| 点"转码后播放" | 状态变为"正在转码，完成后会自动切换"，按钮进入 loading 并禁用 |
| 约 5 秒后 | 自动切到转码版本，`video.src` 变成 `blob:` 地址 —— 这是 hls.js 走 MSE 播放的确凿标志（播原文件会是 `http://…/video/…`） |
| 起播验证 | `currentTime` 由 0 推进到 2.96、`paused=false`、分辨率 `1920×1080`、时长 60.2 s、`error=null` |

**一个必须说清的边界**：`canPlay` 按**后缀名**判断，而浏览器能不能播取决于**容器 + 编码**，两者不等价。
实测里正好撞到反例 —— 同一个 H.264/AAC 的 `.mkv`，Chrome 其实能直接播（列表页标 `canPlay=true` 是对的）；
而 `.avi` 同样标了 `canPlay=true` 却放不了（`error.code=4`）。
这正是失败提示存在的理由：**后缀判断不准，所以用播放器的 `error` 事件兜底，而不是靠前端再维护一份白名单。**

---

## 已知取舍与未做的事

写在这里，是因为"没做什么、为什么不做"同样是工程判断的一部分。

| 项 | 结论 |
|---|---|
| 前导通配符 `LIKE '%kw%'` | **加索引也救不了**。实测同一列同一个索引：`LIKE 'Chou%'` 走 `range`、`LIKE '%Chou%'` 仍是全表扫描。这类查询应交给 ES 倒排索引（项目已有 ES）。详见 [`docs/analysis/fuzzy-search-and-index.md`](docs/analysis/fuzzy-search-and-index.md) |
| 深分页 | 当前是 `OFFSET` 分页，页号越大越慢（实测第 100 页是首页的约 3.5 倍）。彻底解决需改游标（keyset）分页 |
| 列表逐行 URI 调用 | **经实测证否后放弃**：分页后每页仅 100–200 行，整页 14 ms，没有可优化的瓶颈 |
| 删歌流程加事务 | **经读码证否后放弃**：该流程 5 步里只有 1 条 DB 语句，单条语句本身即原子，`@Transactional` 提供不了任何原子性；文件与 ES 也参与不了 JDBC 事务 |
| MQ 失败重试 | 有意不做：重试只对**瞬时**故障有意义，而转码失败多为**确定性**失败；真要做的正确姿势是延迟队列（TTL + 死信路由回主队列），留给后续 |
| Redis 恢复后补订阅 | **未做**：订阅启动失败的实例不再接收配置广播，Redis 恢复也需重启才补上。分布式锁不受影响 —— 它每次调用即时判断、失败即放行 |
| Elasticsearch 的两个功能 | **界面上不可达**：前端从不调用 `/music/search_v2`（歌词全文搜索）与 `/api/music/reInitLyric`（重建索引）。用户能触达 ES 的只有"删歌"与"上传歌词"两条被动写入；列表页的搜索框走的是数据库 `LIKE`，与 ES 无关。也就是说 **用户享受不到 ES 的好处，却要承担它出错的风险** —— 修完启动问题后这个不对称仍在 |
| ES 恢复后自动启用 | **未做**：启动时连不上就一直降级（`enabled()==false`，相关能力静默 no-op）。ES 恢复后需**重启应用**或**重新保存一次配置**才启用 —— 与 Redis 订阅同款取舍 |
| 安全加固 | 只修了 `/del` 的任意文件删除（原实现按原样绝对路径删除、且删除前还会创建目录）；**其余未做**：`/file?id` 的路径穿越、WebDAV XXE、上传大小上限、密码进日志、CORS 收紧 |
| 转码链的前端接入 | **已做**：播放页可切换原文件 / 转码版本，一键转码后自动切换（详见下方"转码产物的最后一环"）。仍未做的是"完全自动"—— 目前由用户点一次触发转码，没有做成"发现放不了就静默转码" |
| 正式压测 | 只有单请求延迟基线，**未做多线程 QPS 曲线** |

## 界面

界面来自原项目（Angular 22 + ng-zorro），本次改造只动了**播放页**：加了"原文件 / 转码版本 / 转码后播放"
三个控件与一段失败提示，用来接通转码产物。其余页面的交互与样式未改动。

![](https://raw.githubusercontent.com/itning/yunshu-nas/master/pic/a.png)

![](https://raw.githubusercontent.com/itning/yunshu-nas/master/pic/b.png)

![](https://raw.githubusercontent.com/itning/yunshu-nas/master/pic/c.png)

## 怎么复现这些数字

下面是最关键几项的复现方法 —— 数字能被人自己跑出来才算数。

**SQL 层**（MySQL 8，音乐表 5 万行）：

```sql
-- 1) 三条件等值查询：加联合索引前后对比
EXPLAIN SELECT * FROM music WHERE name = 'Summer Piano 00' AND singer = 'Jay Chou' AND type = 1;
--   加索引前：type=ALL、key=NULL、rows≈49928、filtered=0.10%、Extra=Using where
--   加索引后：type=ref、key=idx_name_singer_type、rows=2、filtered=100%
ALTER TABLE music ADD INDEX idx_name_singer_type (name, singer, type);

-- 2) 前导通配符 LIKE：加索引也没用（这是"为什么要用 ES"的证据）
ALTER TABLE music ADD INDEX idx_singer (singer);
EXPLAIN SELECT * FROM music WHERE singer LIKE 'Chou%';    -- type=range、rows 大降到 1
EXPLAIN SELECT * FROM music WHERE singer LIKE '%Chou%';   -- 仍 type=ALL、key=NULL
ALTER TABLE music DROP INDEX idx_singer;   -- 对 %kw% 无用，删掉以免白占写入成本

-- 3) ORDER BY 的 filesort：只在带 LIMIT 时才能靠索引消除
EXPLAIN SELECT * FROM music ORDER BY gmt_create DESC;            -- 加索引后计划不变
EXPLAIN SELECT * FROM music ORDER BY gmt_create DESC LIMIT 100;  -- 转为 index + Backward index scan
```

**接口层**（启动应用并配置好业务库与音乐数据源后）：

```bash
curl -s -o /dev/null -w 'size=%{size_download}B  time=%{time_total}s\n' \
  'http://127.0.0.1:8888/api/music/list'                  # 旧接口：约 17.9 MB
curl -s -o /dev/null -w 'size=%{size_download}B  time=%{time_total}s\n' \
  'http://127.0.0.1:8888/api/music/page?page=1&size=100'   # 新分页：约 36 KB
```

**MQ**（需本机 RabbitMQ；以 `--nas.mq.enabled=true` 开启）：

```bash
# 失败任务应进死信队列（主队列归零）
curl -s -u guest:guest 'http://127.0.0.1:15672/api/queues/%2F/yunshu.transcode.dlq'
# 幂等：把同一任务重复投两次，消费端会跳过（产物已存在），实际转码次数仍为 1
```

### 测量方法上的三点注意（我自己踩过）

1. **单次 `EXPLAIN ANALYZE` 受缓冲池冷热影响很大** —— 同一个查询我先后见过 58 ms 与 20 ms。
   所以结论要建立在"**同一条件下各跑多次取中位数**"上。我在验证联合索引时特意把索引先摘掉跑 5 次、
   再加回来跑 5 次，就是为了避免拿不同时刻的单次值互相比较。
2. **接口首次请求包含 JIT 与连接池预热** —— 分页接口冷启动 196 ms、预热后 14 ms。
   拿冷启动去和已预热的作对比会得出完全错误的结论（我一开始就差点这么写）。
3. **RabbitMQ 管理接口的 `messages` 统计有延迟**（默认约 5 秒），刚投递完立刻查询可能仍显示 0。

## 许可与致谢

- 本项目基于 [itning/yunshu-nas](https://github.com/itning/yunshu-nas)，遵循 **Apache-2.0**。
  原项目的包名（`top.itning.yunshunas.*`）与 `@author` 署名**全部保留**，这是许可证的要求，也是应有的尊重。
- 感谢原作者 [@itning](https://github.com/itning) 提供了这样一个结构清晰、便于二次开发的母体。
- 感谢 [JetBrains](https://www.jetbrains.com/) 提供的开源开发工具支持。

![JetBrains Logo (Main) logo](https://resources.jetbrains.com/storage/products/company/brand/logos/jb_beam.svg)
