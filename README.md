# CF 优选助手（Android）

**Cloudflare 优选 IP 筛选、连接测速与订阅管理工具**

CF 优选助手是一个 Android Studio 项目，用于加载 Cloudflare 优选 IP 列表、识别地区与线路、测试连接延迟和下载速度，并将选中的订阅记录上传到 Cloudflare Worker。

Android App 使用 WebView 显示 `index.html` 页面，由 Kotlin 本地服务提供页面 API；不需要 Termux 或 Node.js。

---

## 功能

### 地区与数据源

- **地区列表**：内置 HK、TW、SG、JP、US、KR、GB、DE、RU、NL、SE、AU、VN、IN、LV、FI。
- **数据源**：使用内置 TXT 源，或填写自定义 HTTPS TXT 数据源。
- **IP 解析**：解析 IPv4/IPv6 与端口，提取线路及国家码，并按 IP:端口去重。

### 筛选与测速

- **国家和线路筛选**：支持国家代码筛选及「其他」分类，线路选项随国家筛选更新。
- **连接优选**：并发执行 TCP 连接测试，隐藏连接失败的 IP，并按延迟排序。
- **速度测试**：对单个 IP 执行 HTTPS 下载测速，使用 `speed.cloudflare.com` 的 SNI/Host，并校验证书主机名。
- **结果操作**：支持分页、手动勾选、勾选前 3 个及按速度排序。

### 订阅上传与配置

- **覆盖上传**：将当前勾选的数据按标准订阅格式上传，替换 Worker 中的订阅内容。
- **追加上传**：读取服务器已有内容，与本次勾选内容按 IP 去重，再上传合并后的列表；没有新增 IP 时不修改服务器内容。
- **配置管理**：通过 App 菜单中的「上传配置」保存 Worker HTTPS 地址和 Worker Secret。

---

## 配置 Worker 上传

在 Android App 中打开菜单，选择 **上传配置**，填写以下信息：

| 配置项 | 说明 |
|---|---|
| Worker HTTPS 地址 | 例如 `https://example.workers.dev`，填写根地址，不要添加 `/update` 或 `/sub.txt`。 |
| Worker Secret | 填写 Cloudflare Worker 中配置的 `UPDATE_SECRET` 值。 |

保存后即可使用上传功能。

密钥保存在 App 私有存储中；清除 App 数据会同时清除这项配置。

---

## Worker 部署要求

部署 Worker 前，必须配置以下两项。

### 1. KV 命名空间绑定

在 Cloudflare Workers 的 **Settings → Bindings** 中添加 KV namespace 绑定：

- **Variable name：** `IP_KV`
- **KV namespace：** 选择已创建的 KV 命名空间

Worker 使用 KV 中的 `subscription.txt` 保存订阅文本。

### 2. 环境变量

在 Cloudflare Workers 的 **Variables and Secrets** 中添加：

- **Variable name：** `UPDATE_SECRET`
- **Value：** 自行设置的上传密钥
- **类型：** 建议使用 Secret

Android App 中填写的 Worker Secret 必须与 `UPDATE_SECRET` 完全一致。

无需配置其他变量。

---

## Worker 支持的接口

| 请求方式 | 路径 | 用途 | 权限 |
|:---:|---|---|---|
| `GET` | `/health` | 健康检查，返回 `ok` | 公开 |
| `GET` | `/` | 读取现有订阅，供追加上传使用 | 需要 `UPDATE_SECRET` |
| `GET` | `/sub.txt` | 获取订阅文本 | 公开 |
| `POST` | `/update` | 上传或覆盖订阅 | 需要 `UPDATE_SECRET` |

### 追加上传说明

追加上传要求 Worker 根路径支持 HTTPS GET，并返回当前订阅的原始文本内容。

根据本项目使用的 Worker 代码，根路径需要携带以下请求头：

```http
Authorization: Bearer 你的UPDATE_SECRET
```

如果 Worker 无法正确返回现有订阅文本，追加操作会失败。读取失败不应被当作空订阅继续覆盖。

### 安全提醒

- `/` 和 `/update` 都需要正确的 Bearer 密钥。
- `/sub.txt` 是公开接口，任何知道地址的人都可以读取订阅内容。
- 不要将 `UPDATE_SECRET` 提交到公开 GitHub 仓库。

---

## 构建 APK

### 使用 GitHub Actions

项目包含以下工作流文件：

`.github/workflows/build-apk.yml`

构建步骤：

1. 将项目上传到自己的 GitHub 仓库。
2. 打开仓库的 **Actions** 页面。
3. 选择 **Build Android APK** 并运行工作流。
4. 工作流完成后，在对应运行记录的 **Artifacts** 中下载 `CFOptimizer-debug-apk`。
5. 解压 Artifact，取得 `app-debug.apk`。

首次使用时，需要允许仓库运行 GitHub Actions。

---

## 验证说明

生成项目压缩包不等于 APK 已成功编译或完成真机测试。

请在 Android Studio 或 GitHub Actions 中实际构建，并在设备上验证：

- 数据加载
- 测速
- 国家与线路筛选
- 覆盖上传
- 追加上传

追加上传能否成功，取决于 App 与 Worker 的请求方式、认证密钥及根路径返回内容是否匹配。
