# 告警与通知

告警中心（一级菜单「告警中心」，路由名与权限标识 `HostAlert`）管理两类告警，并通过邮件、短信通知。

| 类型 | 条件 | 对象 |
| --- | --- | --- |
| 日志告警 | 最近 N 分钟内，某日志库中匹配**级别**和/或**关键字**（可再限定主机）的日志条数 **超过** 阈值 | 日志库 |
| 监控告警 | 主机 CPU / 内存 / 磁盘 / 负载超过阈值并持续一段时间，或 Agent 离线 | 主机 |

两类规则都每分钟评估一次：条件成立时产生一条「告警中」事件（同一规则 / 主机只有一条，持续更新当前值与峰值），条件不再成立时自动「恢复」。停用或删除规则会直接恢复它的告警（不发恢复通知）。

## 日志告警规则

- **级别**：可多选，不选为所有级别；**关键字**：日志内容包含的文字，不区分大小写。两者至少填一个。
- **统计窗口** 1–1440 分钟，**阈值** ≥ 0（0 表示出现即告警）。
- 邮件中附最新一条匹配日志；告警列表的「日志」按钮按规则条件打开日志查询。

## 通知

每条规则单独设置：

- **通知方式**：邮件、短信，可同时勾选；都不勾选则只记录告警。
- **接收人**：系统用户（使用其个人资料中的邮箱、手机号，停用用户不通知）和**通知组**（一组系统用户 + 外部联系人，外部联系人无需账号）。接收人按地址去重。
- **恢复通知**：恢复时再通知一次（默认开）。
- **重复提醒**：告警持续未恢复时每隔 30 分钟 – 1 天再通知一次。

通知在后台依次发送，每个接收人的每次发送都记入「通知记录」（保留 90 天），失败时记录服务器 / 服务商返回的原因。

### 邮件（SMTP）

填写 SMTP 服务器、端口、加密方式（SSL/TLS 465、STARTTLS 587、不加密 25）、账号、密码（QQ、163 等邮箱为**授权码**）与发件地址。页面提供常用邮箱预设，可用当前填写的配置（无需先保存）发送测试邮件。服务器证书按 JDK 信任库校验。

### 短信（阿里云 / 腾讯云）

在页面中选择服务商，两家都需先在控制台申请**签名**和**模板**并通过审核。模板变量依次为：级别（严重 / 警告）、规则名、对象（主机或日志库）、状态（告警 / 已恢复 / 持续告警 / 测试）、详情（如 `CPU 95.2%>90%`、`5分钟32条>10`）。

| 服务商 | 模板示例 | 配置项 | 变量长度 |
| --- | --- | --- | --- |
| 阿里云 | `【${level}】${rule}：${target} ${status}，${detail}` | AccessKey ID / Secret、签名、模板 CODE | 每个 ≤ 30 字 |
| 腾讯云 | `【{1}】{2}：{3} {4}，{5}` | SecretId / SecretKey、SdkAppId、地域（默认 ap-guangzhou）、签名、模板 ID | 每个 ≤ 20 字 |

超出长度的变量会被截断。建议使用只授权短信发送（`AliyunDysmsFullAccess` / `QcloudSMSFullAccess`）的子账号密钥。「接口地址」留空使用公网地址，经代理或专线访问时可修改。

手机号：中国大陆号码可写 `13800138000` 或 `+86 138 0013 8000`；其他国家和地区带国家码，如 `+85291234567`。

### 密钥保存

SMTP 密码与短信密钥使用 AES-GCM 加密后存库，密钥派生自 `loggingx.backup.secret`（默认同 JWT 密钥，与备份存储共用），接口不会返回明文。**修改该密钥后需重新填写**密码 / 密钥。

## 接口

均以 `/api` 开头，需要 `HostAlert` 菜单权限：

- 日志告警：`GET|POST /alert-center/log-rules`、`PUT|DELETE /alert-center/log-rules/{id}`、`GET /alert-center/log-alerts?status=&storeId=&days=`
- 监控告警：`/monitor/alert-rules`、`/monitor/alerts`（规则请求 / 响应中的 `notification` 为通知设置）
- 通知设置：`GET /alert-center/notify/channels`、`PUT /alert-center/notify/email|sms`、`POST /alert-center/notify/email/test`（`{settings, to}`）、`POST /alert-center/notify/sms/test`（`{settings, phone}`）
- 通知组：`GET|POST /alert-center/notify/groups`、`PUT|DELETE /alert-center/notify/groups/{id}`
- 通知记录：`GET /alert-center/notify/records?eventType=host|log|test&eventId=`
- 选项：`GET /alert-center/stores`、`GET /alert-center/users`（邮箱、手机号脱敏）

规则中的 `notification`：

```json
{ "channels": ["email", "sms"], "userIds": [1], "groupIds": [2], "notifyResolved": true, "repeatMinutes": 60 }
```
