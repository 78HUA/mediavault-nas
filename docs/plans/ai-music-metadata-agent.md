# 计划：MediaVault 媒体元信息补全 Agent

> **这份文档是一份可直接执行的交接计划。** 把整份文档（或本文件的路径）交给一个新的对话，
> 它应当能在**不依赖任何额外背景**的前提下按阶段实施。
>
> 建议的开场指令（可直接粘贴给执行者）：
>
> ```
> 读 E:\GitHub\yunshu-nas\docs\plans\ai-music-metadata-agent.md，
> 按里面的「分阶段计划」从第 0 步开始执行。
> 先读文档第 1、3、4、7 节把背景和纪律吃透，再动手。
> 每一步做完都要给我：改了什么、怎么验证的、实测数字。一小步一提交。
> ```

---

## 1. 背景

### 1.1 项目是什么

`E:\GitHub\yunshu-nas` 是 **MediaVault** —— 一个 NAS / 家庭媒体服务器，
基于开源项目 `itning/yunshu-nas` 二次开发（Apache-2.0，fork 关系保留）。

- 技术栈：**Java 21 / Spring Boot 4.1.1 / Maven**，模块 `nas-common` / `nas-video` / `nas-music` / `nas-deploy`
- 持久层：**HikariCP + JdbcTemplate + 手写 SQL**（没有 MyBatis / JPA）
- 前端：Angular 22（构建产物提交在 `nas-deploy/src/main/resources/static/`）
- 已有的可选中间件：Redis（配置广播 + 分布式锁）、RabbitMQ（转码队列）、Elasticsearch（歌词检索）——
  **全部做成"未配置即降级、不影响启动"**

### 1.2 这个项目已经完成了什么

前一轮改造（39 个提交）已经做完了一轮系统的性能优化与缺陷修复，路线图在
**`docs/optimization-plan.md`**（权威，含每条改动的实测数字、被取消项的原因、以及"已知取舍与未做的事"）。
**动手前先读它，尤其是"不可动摇的纪律"一节。**

已完成的主要成果（与本计划相关的）：

| 已有能力 | 对本计划的意义 |
|---|---|
| 有界队列 + `AbortPolicy` + HTTP 429 背压 | 批量任务可以直接复用这套背压 |
| RabbitMQ 转码队列（持久化 / 死信 / 幂等） | 长任务可以复用这套治理 |
| Redis 分布式锁（`SET NX PX` + Lua 释放） | 多实例不重复干活 |
| Elasticsearch 歌词检索（`SearchServiceImpl`） | 可作为 Agent 的一个检索工具 |
| `MusicMetaInfoServiceImpl`（jaudiotagger 读 ID3/FLAC 标签） | Agent 的"读标签"工具直接复用 |
| 可降级中间件的完整范式（`NasRedisProperties` / `NasRedisConfig`） | **AI 配置照抄这套模式** |

### 1.3 为什么加这个功能

项目里的 `Music` 实体**只有 `name` / `singer` / `type` 三个业务字段**，
而实际音乐库的元信息质量很差：文件名来自各种下载站，格式五花八门，
ID3 标签经常是空的或错的。

**这不是猜测，已有实测数据**：上一轮修 F8（无标签音频也能添加）时，
拿手上 **20 个真实音频文件**测了一遍 —— **其中 18 个标题/歌手为空，占 90%**。
（那次修复的经过见 `docs/optimization-plan.md` 的 F8 条目。）

**F8 和本计划是互补的两半，理解这个关系很重要**：

| | 做了什么 | 留下什么问题 |
|---|---|---|
| **F8（已完成）** | 去掉「歌名/歌手」输入框的 `readonly`；选文件时用**文件名（去扩展名）兜底填歌名** | 这批文件现在**能加进库了**，但加进去的是"**文件名当歌名**"的脏数据 |
| **本计划** | 让 Agent 读标签、查库、检索、比对，产出**规范化**的元信息建议 | 把 F8 放进来但没规范化的记录**修正过来** |

换句话说：**F8 解决了"加不进来"，本计划解决"加进来了但不对"。**
这是个很自然的接续，不是硬塞的功能。

**而且这不是"展示问题"，是"功能可用性问题"**：

```java
// WebDavFilter.java:163 —— (name, singer, type) 这个三元组是 WebDAV 访问路径的寻址键
musicRepository.findByNameAndSingerAndType(name, single, musicTypeOptional.get().getType())
```

也就是说，元信息一乱（"周杰伦" / "Jay Chou" / "周杰倫" 混着、"晴天" 与 "晴 天" 并存），
**WebDAV 就取不到文件**。痛点是真的。

---

## 2. 目标与非目标

### 2.1 目标

做一个 **Agent**（不是 pipeline，区别见第 4.1 节）：
给定一个音乐文件，它能**自主决定**去读标签、查库、检索、比对，
最终产出一份规范化的元信息建议 `{name, singer, type, confidence, reason}`。

### 2.2 非目标（明确划出来，避免范围蔓延）

- ❌ **不做**"用 LLM 决定转码参数"这类场景 —— 转码参数有成熟经验公式，用 LLM 是杀鸡用牛刀且不可复现
- ❌ **不做**自动落库（第一版）。Agent 只**产出建议**，人工确认后才写库，理由见 4.4 节
- ❌ **不碰前端**（第一版）。所有接口用 curl / 测试类验证即可；前端的"一键补全"按钮留到后续
- ❌ **不改**包名、不改原项目文件的 `@author`（见第 7 节红线）

---

## 3. 已核实的落点（这些是代码事实，不用再查一遍）

| 落点 | 位置 | 说明 |
|---|---|---|
| 读音频标签 | `nas-music/.../service/impl/MusicMetaInfoServiceImpl.java` | `metaInfo(File, MusicType)` 用 jaudiotagger 读，**可能返回 `null`**（无标签时），要处理 |
| 标签模型 | `nas-music/.../dto/MusicMetaInfo.java` | 有 `title` / `artists`(List) / `album` / `coverPictures` —— **比 `Music` 实体丰富**，`album` 目前无处可存 |
| 写标签 | `MusicMetaInfoServiceImpl.editMetaInfo(...)` | **能回写文件标签**。所以 Agent 的产出有两个落点：数据库 + 文件本身 |
| 实体 | `nas-music/.../entity/Music.java` | 只有 `id / musicId / name / singer / lyricId / type / gmtCreate / gmtModified` |
| 类型枚举 | `nas-music/.../constant/MusicType.java` | **`FLAC=1, MP3=2, WAV=3, AAC=4`（只有 4 个）**，校验 `type` 合法性就用它 |
| 查重 | `MusicRepository.findByNameAndSingerAndType(name, singer, type)` | 已存在，返回 `Optional<Music>` |
| 落库 | `MusicManageServiceImpl.editMusic(MusicChangeDTO)` / `addMusic(...)` | 现有写入口 |
| 检索 | `SearchService` / `SearchServiceImpl` | ES 歌词检索（`searchLyric`），可选工具 |
| 配置类模板 | `nas-common/.../config/NasRedisProperties.java` | **照抄它的写法**：`@Data` + `@JsonIgnoreProperties` + `@JsonInclude(NON_NULL)` + `enabled` 开关 |
| 配置装配模板 | `nas-common/.../config/NasRedisConfig.java` | 可降级装配的完整范式 |
| 配置读写接口 | `nas-deploy/.../controller/SettingController.java` | `GET/POST /api/setting/{type}`，`switch (type)` 里逐类型分发 —— **新增 AI 配置要在这里加 `case "ai"`（GET 和 POST 两处）** |
| 可复用 HTTP 客户端 | `nas-deploy/.../config/BeanConfig.java:29` | 已有一个 `RestTemplate` bean，`SearchServiceImpl` / `UploadServiceImpl` 在用 |
| 配置存储 | 内嵌 SQLite（`setting` 表），文件是**工作目录**下的 `yunshu-nas.db` | 所以设置存在"从哪个目录启动"里 |

---

## 4. 设计决策（已经定了，不要重新讨论）

### 4.1 ⚠️ 必须先分清：什么才算 Agent

**判定标准只有一条：谁掌握控制流。**

| | 谁决定"下一步做什么" | 能不能循环 | 名字 |
|---|---|---|---|
| ① | 程序员写死顺序 | 不能 | **Pipeline** |
| ② | 程序员写死顺序，但每步参数由 LLM 填 | 不能 | Pipeline + Function Calling |
| ③ | **LLM 自己**根据上一步结果决定下一步 | **能** | **Agent** |

一句话测试：**把 LLM 拿掉，流程还能跑就是 pipeline；散架了才是 Agent。**

**本计划的第 2 步是 pipeline，第 3 步才变成 Agent。** 这是刻意的分阶段 ——
先在无工具的单次调用上把链路和校验跑通，再加自主决策，否则出问题无法定位。

**纪律：不要把 pipeline 包装成 Agent。** 面试官一问"模型怎么决定调用哪个工具"就会露馅；
反过来，能主动说清"这一步为什么不需要 Agent"是加分项。

### 4.2 工具集（Agent 的"手"）

| 工具 | 实现依据 | 什么时候用得上 |
|---|---|---|
| `readAudioTags(musicId)` | `MusicMetaInfoService.metaInfo` | 文件名不可信时 |
| `listExistingSingers()` | 查库去重 + 计数 | **约束输出、防幻觉**（见 4.3） |
| `findExistingMusic(name, singer)` | `findByNameAndSingerAndType` | 判断是否已存在 |
| `searchSimilarMusic(keyword)` | `SearchService`（ES） | 找候选做比对 |
| `probeFile(musicId)` | `MusicType` + 文件属性 | 判断格式、大小（比版本优劣用） |
| `getFilePath(musicId)` | 现有数据源配置 | 看路径（路径里常含专辑信息） |

### 4.3 防幻觉的两条硬约束（**这是本计划最重要的设计**）

LLM 在这个任务上最典型的失败是**编造一个库里根本不存在的歌手名**，
以及**把同一个歌手分裂成多个写法**（"周杰伦" / "Jay Chou" / "周杰倫"）。

**约束一：把 `listExistingSingers()` 的结果作为候选集喂给模型，要求它从候选里选。**
只有确实不在候选里时，才允许输出新歌手 —— 且必须打上低置信度标记。

**约束二：结构化输出 + 强校验。** 不要让模型输出自由文本，要求它输出 JSON：

```json
{
  "name": "晴天",
  "singer": "周杰伦",
  "type": 2,
  "confidence": 0.92,
  "reason": "文件名 `[Jay Chou] 晴天 (2003).flac` 与 ID3 标签一致",
  "singerFromCandidates": true
}
```

落到代码里必须校验：
- JSON schema 完整（字段齐全、类型对）
- `type` ∈ `MusicType` 的 4 个值之一
- `singerFromCandidates == true` 时，`singer` 必须真的在候选集里（**不信模型的自述，自己再查一遍**）
- 校验不过 → 重试一次 → 仍不过则标为"放弃"，不写库

### 4.4 人在环（HITL）

Agent **只产出建议，不直接写库**。低置信度（比如 `< 0.8`）的建议进"待确认"列表。

理由有两个，都很实际：
1. **安全**：元信息是 WebDAV 的寻址键，写错了会让文件访问不了
2. **面试**：这是"知道 LLM 不可靠、用工程手段兜住"的实证，比"我调了个 API"有说服力得多

### 4.5 可降级（**不可违反**）

这个项目有一条从第一天守到现在的特性：**零配置可启动**。
Redis / RabbitMQ / ES 全都是"未配置就不启用、绝不拖垮启动"。

**AI 功能必须照此办理**：没配 API Key 时，AI 相关接口给友好提示（或直接隐藏），
**绝不能启动失败**。照 `NasRedisConfig` 的写法做。

---

## 5. 分阶段计划（每阶段 = 至少 1 个提交）

> 提交纪律（沿用本项目既有约定）：**一小步一提交**，
> commit message 写清「**问题 → 改动 → 实测**」三段。
> 不留"突然成品"的提交 —— 改造过程本身就是可被检视的证据。

### 第 0 步：选型验证（**先做，且限时**）

**这一步是硬性前置，不许跳过。** 本项目有一条血的教训：上一轮想换 Undertow，
代码看起来完全正确，实际根本不兼容 —— 如果先写完业务代码再发现，全部白做。

要验证的三件事：

1. **有可用的模型**：云 API（需要一个 Key）或本地 Ollama（需要装）。**先确认这个再动手**
2. **AI 框架与 Spring Boot 4.1.1 / JDK 21 兼容**。候选：
   - `Spring AI`（官方，`ChatClient` + tool calling + 结构化输出）
   - `LangChain4j`（Java 生态更久，`AiServices` 注解式）
   - **手写**：项目已有 `RestTemplate` + Jackson，tool-calling 循环本身只有百来行
3. 用最简方式跑通一次调用（能编过、能拿到回复）

**选型建议**：
- 如果目标是**能力本身** → 优先考虑手写。项目已有 `RestTemplate` 和 Jackson，
  零版本兼容风险，而且**每一行你都能解释**（面试时这点很值钱）
- 如果目标是**简历上的框架关键词** → 先验证 Spring AI 能否在这套依赖下跑起来，
  **限时 1–2 小时**；不通过就记录证据然后降级到手写

**产出**：一段结论（选了哪个、为什么、验证命令与输出），并提交为 `docs:` 记录。
**如果三种方案都跑不通**，停下来汇报，不要硬写。

### 第 1 步：配置与降级骨架

- 新增 `NasAiProperties`（**照抄 `NasRedisProperties` 的写法**）：
  `enabled` / `baseUrl` / `apiKey` / `model` / `maxIterations` / `timeoutSeconds`
- `SettingController` 的 GET 与 POST **两处** `switch` 加 `case "ai"`
- 未配置时：接口给友好提示、启动完全不受影响
- **实测**：三种情形各跑一次（从未配置 / 配置了不可达 / 配置正确），
  确认启动都成功、且只有最后一种能真正调通

### 第 2 步：单文件链路（**这一步还是 pipeline**）

- 新增 service：输入 `musicId`，输出一份建议（见 4.3 的 JSON）
- 只做**一次** LLM 调用 + 结构化解析 + 校验，**不做工具调用**
- 接口先挂一个 `GET /api/ai/music/suggest?musicId=`
- **明确在代码注释里写清这是 pipeline 不是 Agent**（第 3 步才升级）
- **实测**：拿真实文件跑，贴出返回的 JSON 与校验结果

### 第 3 步：引入工具调用（**这一步才变成 Agent**）

- 定义工具集（4.2 节），实现 tool-calling 循环
- **必须有 `maxIterations` 兜底**（否则模型可能不停止调用）
- **每轮记录调用了哪些工具、参数是什么、返回什么** —— 这是后面算"平均工具调用轮数"和排查问题的基础
- **实测**：同一个文件，观察模型在不同输入下是否真的走了不同的工具路径
  （文件名清晰的可能一个工具都不调；标签为空的需要先读标签再查库）
  —— **这正是"它是 Agent 而不是 pipeline"的证据**

### 第 4 步：防幻觉约束 + HITL

- 把 `listExistingSingers()` 的输出作为候选集喂进去（4.3 约束一）
- 置信度分级：高 → 可直接采纳；低 → 进"待确认"
- 计数器：`singerNotInCandidates`（模型编造歌手的次数）
- **实测**：加候选集约束**前后**，编造歌手的次数对比 —— **这个对比就是你最好的面试素材**

### 第 5 步：批量 + 重复识别（让 Agent 变厚）

如果只做"单文件改名"，工具调用就那两下，**薄到面试官追问两轮就见底**。
这一步让任务有真正的判断力成分：

> **"这两条记录是不是同一首歌？"** —— 规则做不了，需要理解。

`Jay Chou - 晴天` / `周杰伦 - 晴天 (Live)` / `晴天.flac` / `周杰伦 - 晴 天 (Remastered)`
—— Live 算不算重复？Remastered 呢？翻唱呢？

- 批量入口（先做小批量同步，别一上来就接 MQ；**如果要做异步，复用已有的有界队列 + 429 背压**）
- 重复识别：判断两条是否同一首 + 决定保留哪个（可比对格式与文件大小）
- **实测**：造一组明确的重复/非重复样本，贴出判断结果

### 第 6 步：评估集与指标（**让"Agent"立得住的一步**）

**没有评估的 Agent 是站不住的。** 面试官问"你怎么知道它变好了"，
答不上来，这一整块就从加分项变成减分项。

做法：
1. **构造测试集**：手工整理 **50–100 条**已知正确答案的样本
   - 文件名要刻意做乱（换序、加噪声、混中英文），因为正常文件名不需要 Agent
   - 正确答案从**现有数据**推出来（库里已有 `name`/`singer`/`type` 就是标准答案）
   - 可选捷径：如果 `.baseline/02_seed_music.sql` 还在，那份灌库脚本用**确定性公式**生成 5 万行，
     所以"正确答案"是可计算的 —— 把 `name`/`singer`/`type` 打乱成假文件名就成了天然的测试集。
     ⚠️ 两个注意：①`.baseline/` 是 **gitignored** 的本地目录，新克隆的仓库里没有；
     ②**库里的 5 万条假数据已经被删掉了**，要用这条捷径得先重新灌一遍。
     文件不在或不想重灌，就用上面的手工方式构造 50–100 条，完全够用
2. **统计四个指标**，写进文档：

| 指标 | 怎么算 | 说明 |
|---|---|---|
| 准确率 | `name` 与 `singer` 都正确的比例 | 核心指标 |
| **幻觉率** | 编造了候选集中不存在歌手的比例 | **最能体现工程意识** |
| 平均工具调用轮数 | 总调用次数 / 样本数 | 直接反映成本 |
| 放弃率 | 低置信度转人工的比例 | 反映"知道自己不知道"的能力 |

3. **改前改后对比**：比如"加候选集约束前 vs 后"的幻觉率变化

### 第 7 步：文档与话术

- 更新 `README.md`（新功能小节 + 实测数字）
- 更新 `docs/optimization-plan.md`（新增阶段与提交条目）
- 更新 `INTERVIEW-NOTES.md`（本地草稿，gitignore）补问答话术
  —— 注意该文件里有"**别说错**"的纪律表，新内容要与之一致

---

## 6. 验收标准（整个计划的"做完"定义）

- [ ] 未配置 AI 时，应用**照常零配置启动**，`/health` 正常
- [ ] Agent 能自主选择工具，**不同输入走不同工具路径**（有日志/记录为证）
- [ ] 结构化输出**强校验**通过，`type` 与候选歌手都经过代码复核（不信模型自述）
- [ ] 有 `maxIterations` 兜底，模型不停止时能被切断
- [ ] 低置信度不写库，进待确认列表
- [ ] **有评估集与四个指标的数字**，且有至少一组"改前 vs 改后"对比
- [ ] 每个阶段一个提交，message 都是「问题 → 改动 → 实测」三段
- [ ] 全部推送到 `origin`（仓库 `78HUA/mediavault-nas`）

---

## 7. 纪律与红线（**违反会破坏已有成果**）

| 红线 | 原因 |
|---|---|
| **包名 `top.itning.yunshunas.*` 一个字都不能改** | Apache-2.0 义务 + 一改就会把 diff 淹没 |
| **原项目文件的 `@author itning` 一个字都不能改** | 署名义务 |
| **新增的类，`@author` 写 `78HUA`** | 本项目已定稿：新增类署本人名（已有 9 个类这样） |
| **不许破坏"零配置可启动"** | 这是本项目从第一天守到现在的特性，Redis/MQ/ES 都为此做了降级 |
| **不要把 pipeline 说成 Agent** | 见 4.1，会被当场问穿 |
| **不许声称优化了不存在的指标** | 每条结论都要有实测数字支撑 |
| 前端构建产物是提交进仓库的 | 若改了前端源码，必须重跑构建（`angular.json` 的 `outputPath` 已指向 `nas-deploy/.../static`，构建即写入） |

---

## 8. 环境前置（本机实测过的，直接用）

```bash
# Git Bash 里 JAVA_HOME 必须写正斜杠 —— 系统值是反斜杠，会让 mvn 报"JAVA_HOME 未正确定义"
export JAVA_HOME=/e/Java/JDK21

cd /e/GitHub/yunshu-nas
mvn -q package -DskipTests        # 改代码前先确认在跑的程序已停止，否则 jar 被锁、报 rename 失败
java -jar nas-deploy/target/yunshu-nas-2.2.5.RELEASE.jar
```

| 项 | 值 |
|---|---|
| 端口 | 8888；`GET /health` 返回 `UP` |
| 配置存储 | **工作目录**下的 `yunshu-nas.db`（内嵌 SQLite，`*.db` 已在 `.gitignore`） |
| 配置接口 | `GET/POST /api/setting/{type}`，`type` ∈ `nas` / `datasource` / `ftp` / `es` / `redis` |
| 业务数据库 | 可在网页设置页配 MySQL（本机 `root`/`123456`，服务 MySQL80 在跑），或直接用 SQLite |
| 前端 npm | 全局缓存目录不可写 → **必须** `npm install --cache <项目内可写目录>`（已有约定目录 `nas-front-end/.npm-cache`，已 gitignore） |
| push | `github.com:443` 在本机**间歇性不可达** —— 固定写成「先直连重试 3 次 → 再挂代理重试」的循环，**别怀疑令牌** |

---

## 9. 已知的坑

1. **`metaInfo()` 返回 `null` 是常态，不是异常分支**。F8 已实测：20 个真实文件里 18 个标签为空。
   Agent 的"读标签"工具必须能表达"读到了但没有"这个第三种状态，而不是抛异常 ——
   这个区分对模型的决策**非常关键**（正是"读到空标签"才需要去查库/检索）
2. **库里会有一批"歌名 = 文件名"的脏数据**。这是 F8 的兜底逻辑带来的（选文件时用文件名去扩展名填歌名），
   属于**刻意为之的取舍**（总比加不进来强）。做重复识别时要预期到：
   同一首歌可能存在"文件名版"和"标签版"两条记录 —— 这正是第 5 步要处理的核心场景
2. **`Music` 实体存不下 `album`**。`MusicMetaInfo` 有 `album`，实体没有。
   第一版可以让 Agent 只补 `name`/`singer`/`type`，
   把 `album` 作为"只回写文件标签、不入库"的字段处理 —— 但要在文档里写清这个取舍
3. **别一上来就接 MQ / 队列**。先把同步小批量跑通，再考虑异步。
   过早引入异步会让"Agent 本身对不对"这个问题被任务治理的复杂度淹没
4. **`type` 只有 4 个值**（FLAC/MP3/WAV/AAC），别让模型自由发挥
5. **LLM 调用有超时和成本**：批量跑评估集时要做限流/重试，否则一次跑 100 条可能中途挂掉一半
6. **Windows 上路径是反斜杠**，注意 JSON 里的转义（别在字符串里手拼路径）

---

## 10. 做完之后的面试话术要点

按本项目 `INTERVIEW-NOTES.md` 的规矩：**写下的每个技术名词都要能答到第 3 层**
（①是什么 ②为什么用它 ③项目里具体怎么用 + 踩过什么坑）。

需要能答上来的追问：

| 面试官会问 | 你要能说清 |
|---|---|
| **这个痛点是真的吗？** | **有数据**：20 个真实音频文件里 **18 个标签为空（90%）**。而且元信息是 **WebDAV 寻址的键**（`WebDavFilter` 用 `findByNameAndSingerAndType` 查文件），错了会导致文件取不到 —— 不是"展示不好看"，是**功能不可用** |
| 为什么用 LLM 而不是正则/规则？ | 文件名格式是**无穷的**（`周杰伦 - 晴天.mp3` / `[Jay Chou] 晴天 (2003).flac` / `晴天-周杰伦-叶惠美-2003`），规则永远覆盖不全 —— 这是"格式不确定、需要理解而非匹配"的任务 |
| 模型编造歌手怎么办？ | 双层：**候选集约束**（从库里已有歌手里选，不许自由生成）+ **结构化输出强校验**（代码再查一遍，不信模型自述）→ 实测幻觉率从 X% 降到 Y% |
| Agent 和 pipeline 有什么区别？ | 看**谁掌握控制流**；我的实现里工具选择由模型决定，且**不同输入走不同路径**（有记录为证） |
| 怎么评估效果？ | 50–100 条已知答案的测试集，统计**准确率 / 幻觉率 / 平均工具调用轮数 / 放弃率**，并做改前改后对比 |
| 为什么不直接写库？ | 元信息是 **WebDAV 的寻址键**，写错会让文件访问不了；低置信度进人工确认队列 |
| 成本和延迟怎么办？ | `maxIterations` 兜底防死循环；批量做限流；可以说明哪些情况**故意不用 LLM** |
| 为什么没用 XX 框架？ | 说明当时的选型权衡（第 0 步的记录就是答案），**并且说清手写版本每一部分怎么工作** |

**别说的**：不要把第 2 步（单次调用）说成 Agent；不要声称"提升了准确率"却没数字；
不要把"我想做的"说成"我做了的"。
