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
清理残缺产物（使失败后可重转）→ 修正进程治理（超时 / 退出码 / 强制回收）。

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
> 例如 Redis 未配置或不可达时，相关能力各自降级而不会拖垮应用（实测四种情形下 `/health` 均正常）。

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
| 安全加固 | 已修任意文件删除（`/del` 无校验）、路径穿越类问题；其余（WebDAV XXE、上传大小上限、密码进日志、CORS）**未做** |
| 正式压测 | 只有单请求延迟基线，**未做多线程 QPS 曲线** |

## 界面

界面来自原项目，本次改造未改动前端交互（仅调整了显示名称）。

![](https://raw.githubusercontent.com/itning/yunshu-nas/master/pic/a.png)

![](https://raw.githubusercontent.com/itning/yunshu-nas/master/pic/b.png)

![](https://raw.githubusercontent.com/itning/yunshu-nas/master/pic/c.png)

## 许可与致谢

- 本项目基于 [itning/yunshu-nas](https://github.com/itning/yunshu-nas)，遵循 **Apache-2.0**。
  原项目的包名（`top.itning.yunshunas.*`）与 `@author` 署名**全部保留**，这是许可证的要求，也是应有的尊重。
- 感谢原作者 [@itning](https://github.com/itning) 提供了这样一个结构清晰、便于二次开发的母体。
- 感谢 [JetBrains](https://www.jetbrains.com/) 提供的开源开发工具支持。

![JetBrains Logo (Main) logo](https://resources.jetbrains.com/storage/products/company/brand/logos/jb_beam.svg)
