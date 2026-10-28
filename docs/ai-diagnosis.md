# AI 运维

一级菜单「AI 运维」是一个目录，按任务分成五个页面，每个页面单独授权（权限标识即路由名）：

| 页面 | 路由名 | 用途 |
| --- | --- | --- |
| 智能问答 | `AiChat` | 用自然语言问运维问题，AI 检索资料后回答并标注出处（RAG） |
| AI 诊断 | `AiDiagnosis` | 错误日志结合代码的分析结果：原因、修改建议和 diff；日志查询里的「AI 分析」也需要它 |
| 错误归类 | `AiErrorGroup` | 按指纹归类的错误及次数，可对某类错误发起分析 |
| 知识库 | `AiKnowledge` | 运维手册、故障复盘、常见问题，供智能问答检索 |
| AI 设置 | `AiSettings` | 模型、每日上限、分析完成后的通知，以及各日志库的代码仓库和自动分析 |

从智能问答的引用资料可以直接打开诊断详情和知识库文档。早期版本只有一个「AI 诊断」页面，升级后它会成为这个目录里的「AI 诊断」页面（菜单 id 不变），原来拥有它的角色自动获得整个目录的五个页面。

## 智能问答

每次提问时按所选日志库（默认全部有权限的日志库）和时间范围（默认最近 24 小时）收集资料：

| 资料 | 说明 |
| --- | --- |
| 知识库 | 「知识库」页签维护的运维手册、故障复盘、常见问题（Markdown 或纯文本，可导入 .md / .txt），按段落切成约 900 字的片段 |
| AI 诊断 | 近 180 天已完成的诊断：结论、原因、建议和处理状态 |
| 错误归类 | 各类错误的类型、位置、次数和示例 |
| 告警 | 时间范围内的监控告警和日志告警 |
| 错误数量 | 时间范围内各日志库的 ERROR / FATAL 数量 |
| 错误日志 | 时间范围内包含问题中关键词（英文单词、类名、错误码）的 ERROR / FATAL / WARN 日志；没有关键词时取最新的错误日志 |
| 代码 | 问题里出现类名（如 `OrderService`）或文件名（如 `pricing.py`）时，从关联的代码仓库取出对应文件的相关片段 |

知识库、诊断、错误归类和告警按 BM25 相关度排序（英文按单词和标识符、中文按二字词切分），取最相关的 6 条；日志最多 8 条、代码最多 2 段，合计约 24000 字。模型只能根据这些资料回答，资料不足时会说明缺少什么。回答以流式输出，资料列表先于回答显示，点击回答中的 [编号] 可定位到对应资料，点击资料可跳到诊断、知识库文档、告警或日志查询。同一页面内的追问会带上最近 3 轮对话。

接口：`POST /api/ai-ops/ask/stream`（`question`、`storeId`、`hours`、`history`；SSE 事件依次为 `sources`、`message`、`done`，出错为 `error`），`POST /api/ai-ops/search`（只检索、不调用模型），知识库 `GET|POST /api/ai-ops/knowledge`、`GET|PUT|DELETE /api/ai-ops/knowledge/{id}`。适用于全部日志库的文档需要「全部日志库」权限。

## AI 诊断

## 准备

1. **AI 提供方**：后端设置 `LOGGINGX_AI_DEEPSEEK_API_KEY`、`LOGGINGX_AI_OPENAI_API_KEY` 或 `LOGGINGX_AI_ANTHROPIC_API_KEY`（见 README「AI 大模型」）。在「设置」里选择提供方和模型，留空则用第一个已配置的提供方和它的默认模型。
2. **git**：LoggingX 服务器上需要安装 `git` 命令。
3. **代码仓库**：在「设置 → 日志库与代码仓库」为日志库填写 HTTPS 地址、分支（留空为默认分支）和访问令牌（只读即可，公开仓库可留空）。GitHub、GitLab 用户名可留空；Gitee 需要填写用户名。点「同步」验证地址和令牌。

仓库以浅克隆（`--depth 1`、单分支）保存在 `loggingx.ai.repo-dir`（默认 `~/.loggingx/repos/store-<日志库 ID>`），分析前若距上次同步超过 5 分钟会先拉取最新提交。令牌加密存库，每次执行 git 时作为请求头传入，不会写进克隆的配置；git 报错信息中的令牌会被隐去。没有关联仓库时也能分析，只是只依据日志。

## 触发方式

| 方式 | 说明 |
| --- | --- |
| 新错误自动分析 | 每分钟把日志库新的 ERROR、FATAL 日志按「指纹」归类（见「错误归类」页签）。某类错误第一次出现时分析一次，同类后续只累计次数。开启时从当下开始，不分析历史日志 |
| 告警触发 | 该日志库的日志告警开始告警时，分析窗口内最新一条匹配的日志，记录关联到告警事件 |
| 手动 | 日志查询里展开一条 ERROR / FATAL 日志，点「结合代码分析这条错误」；或在「错误归类」里点「AI 分析」，在诊断详情里点「重新分析」 |

自动分析和告警触发每天最多「每日上限」次（默认 50，按服务器时区的自然日），超过的跳过；手动分析不受限制。

**指纹**：有堆栈时取异常类型 + 第一个业务代码栈帧的「类.方法」（Java、Kotlin）或「文件#函数」（Python、JavaScript / TypeScript、Go），不含行号，代码小改动后仍归为同一类；`java.`、`org.springframework.`、`site-packages/`、`node_modules/` 等库代码的栈帧不算业务代码。没有堆栈时取日志首行，去掉数字、ID、引号中的值后计算。

## 分析过程

1. 从日志中解析出异常类型和栈帧，业务栈帧优先。
2. 在仓库里按路径找到栈帧对应的文件：Java / Kotlin 按包名推出路径（`com.shop.OrderService` → `com/shop/OrderService.java`），其他语言按日志里的路径从长到短匹配。最多取 5 处、每处出错行上下 30 行，合计不超过约 24000 字符。
3. 把日志和代码（带行号，标出出错行）发给模型，要求返回 JSON：`summary`、`rootCause`、`suggestion`（Markdown）、`diff`（统一 diff）、`confidence`（high / medium / low）。模型没有按要求返回 JSON 时，原文作为修改建议保存。
4. 结果保存在「诊断记录」，可标记已处理、忽略或重新分析；标记会同步到它所属的错误归类。

注意：代码片段会发送给所选的 AI 提供方，请确认这符合公司的代码安全要求；需要时可让 `_BASE_URL` 指向公司内部的模型网关。AI 给出的修改需要评审和测试后再合入。

## 提醒

- **站内**：顶栏铃铛显示近 7 天已完成、待处理的诊断和正在告警的告警，有未读时显示红点，「标为已读」后清除。
- **邮件 / 短信**：在「设置 → 分析完成后通知」选择渠道和接收人（用户、通知组，与告警中心共用邮件和短信配置）。邮件包含结论和完整的修改建议与 diff；短信只发一句结论，模板变量同告警（级别为「提示」，状态为「AI 建议」）。发送结果记录在告警中心「通知记录」，类型为「AI 诊断」。

## 接口

按上表的页面授权（诊断详情还对「错误归类」「智能问答」开放，日志库列表对所有 AI 页面开放），且只能访问有权限的日志库（见 README「日志库权限」）：

- `GET|PUT /api/ai-diagnosis/settings`：提供方、模型、每日上限、通知
- `GET /api/ai-diagnosis/stores`、`PUT /api/ai-diagnosis/stores/{storeId}`（`repoUrl`、`repoBranch`、`repoUsername`、`token`、`autoNew`、`onAlert`）、`POST /api/ai-diagnosis/stores/{storeId}/sync`
- `GET /api/ai-diagnosis/groups?storeId=&status=`、`PUT /api/ai-diagnosis/groups/{id}/status`、`POST /api/ai-diagnosis/groups/{id}/analyze`
- `GET /api/ai-diagnosis/diagnoses?storeId=&state=&trigger=&days=`、`GET /api/ai-diagnosis/diagnoses/{id}`、`PUT /api/ai-diagnosis/diagnoses/{id}/state`、`POST /api/ai-diagnosis/diagnoses/{id}/reanalyze`
- `POST /api/ai-diagnosis/analyze`（`storeId`、`message`、`host`、`source`、`time`）
- `GET /api/notices`、`POST /api/notices/read`：顶栏通知（任何登录用户，按菜单和日志库权限过滤）
