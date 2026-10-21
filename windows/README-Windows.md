# CF 优选助手 — Windows 便携版

## 使用
1. 解压 ZIP 到普通文件夹。
2. 双击 `start.bat`，或直接运行 `CFYouxuan.exe`。
3. 程序启动本地服务并尝试打开浏览器：`http://127.0.0.1:8788`。
4. 关闭程序即可停止本地服务。若需要查看错误，可运行 `start-console.bat`。

不需要安装 Node.js、Go 或 Python。程序只监听 `127.0.0.1`，不向局域网或公网开放本地 API。

## 功能
- 地区选择、线路筛选、默认数据源、自定义数据源、IP/域名/CIDR 输入。
- TCP 延迟优选、单条延迟测试。
- 逐 IP TLS 下载测速，沿用 `speed.cloudflare.com`、SNI、`/__down?bytes=10000000` 下载端点和实时速度事件流。
- 原网页中的筛选、分页、选择、速度排序、最终上传列表编辑/拖动等交互。
- Worker 配置、覆盖上传和追加上传。
- Windows 宽屏布局，同时保留原 Android 项目源码。

## 订阅配置
需要先部署原有 Cloudflare Worker。在网页右上角菜单中打开“上传配置”，填写 HTTPS Worker 地址和 `UPDATE_SECRET`。配置保存在当前 Windows 用户配置目录的 `CFYouxuan/config.json`，不会硬编码进程序。不要把密钥提交到 GitHub。

## 说明
- 默认数据源需要可访问 `https://bestcf.pages.dev/random-region/{地区}/all.txt`。
- Windows 和 Android 网络栈不同，测速结果可能有差异；发布前建议用实际网络做完整对照测试。
- 本程序未做代码签名，Windows 可能显示未知发布者提示；请仅从可信来源获取程序。
- 不提供 Linux 版本，不附加 SHA-256 文件。
