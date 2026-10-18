# GitHub Release 自动镜像到 Cloudflare R2

工作流 `.github/workflows/release.yml` 在正式发布 GitHub Release 后，按配置将 APK 和 SHA256 校验文件上传到 R2。使用发布页的原始文件，上传后从 R2 读回校验；不会重新签名。

## 1. 创建 R2 存储桶

进入 Cloudflare 控制台 → Storage & databases → R2 → Overview，按提示开通 R2，然后创建桶，例如 `uonest-downloads`。

选择 Standard 标准存储、默认 jurisdiction。本流程使用默认 S3 endpoint，不适用于 EU、US、FedRAMP 专用 jurisdiction 的桶。

R2 有免费用量额度，并非超过额度自动停用的永久免费服务。开通需要完成订阅/结算流程；付款方式以账户页面提示为准。标准存储免费额度包括每月 10 GB-month 存储、100 万次 A 类操作、1000 万次 B 类操作，直接从 R2 下载免出站流量费。关注控制台用量，避免积累过多历史版本。

## 2. 创建专用上传凭据

在 R2 Overview 的 Account Details → API Tokens → Manage 中创建 R2 API token：

- 名称：`github-uonest-upload`。
- 权限：Object Read & Write。
- 范围：仅 `uonest-downloads` 桶。
- 保存生成的 Access Key ID 和 Secret Access Key。这里使用 S3 凭据，不是普通 Cloudflare API Token 文本。
- 同时记录 Cloudflare Account ID（32 位），不是 Zone ID。

密钥只填写到 GitHub Secrets，不要放进代码或聊天。

## 3. 配置 GitHub Actions

仓库 → Settings → Secrets and variables → Actions。

在 Secrets 中新增 Repository secrets：

| 名称 | 值 |
| --- | --- |
| `R2_ACCESS_KEY_ID` | R2 Access Key ID |
| `R2_SECRET_ACCESS_KEY` | R2 Secret Access Key |

在 Variables 中新增 Repository variables：

| 名称 | 值 |
| --- | --- |
| `R2_ACCOUNT_ID` | Cloudflare Account ID |
| `R2_BUCKET` | `uonest-downloads`，或实际桶名 |
| `R2_ENABLED` | 完成配置后填写 `true` |

现有 APK 签名 Secrets 继续保留。没有启用 `R2_ENABLED` 时会跳过镜像；启用后缺少配置或上传失败会明确报错，不会静默跳过。

## 4. 发布并验证

将新增脚本、测试和工作流提交并推送到 GitHub。现有规则：推送 `main` 或 `vX.Y.Z` 标签会构建并发布，随后镜像到 R2。手动 Run workflow 只构建，不发布或上传。

Actions 中 `Mirror published APK to R2` 成功后，桶内应有如下文件（版本号仅为示例）：

```text
releases/v1.0.1/uonest-1.0.1.apk
releases/v1.0.1/uonest-1.0.1.apk.sha256
```

上传失败可以使用 Re-run failed jobs 重试；GitHub Release 不会因为 R2 上传失败而被删除。重试镜像同一版本的发布文件，不会改变 APK 签名。

## 5. 提供公开下载

测试时可以在桶 Settings 中启用 Public Development URL，使用页面给出的 `https://pub-….r2.dev` 地址。正式使用建议在 Custom Domains 中绑定自己的下载子域名，例如 `download.example.com`；域名需要位于同一 Cloudflare 账户。r2.dev 有速率限制，仅适合开发测试。

完整下载地址是公开域名加对象路径，例如：

```text
https://download.example.com/releases/v1.0.1/uonest-1.0.1.apk
```

S3 API endpoint（`ACCOUNT_ID.r2.cloudflarestorage.com`）用于带凭据的上传，不是公开下载地址。访问桶根目录不会显示文件列表，要测试完整 APK 地址。用未登录浏览器实际下载，并核对 SHA256。

此版本只提供按版本号保存的下载文件，没有 `latest.apk` 或自动更新官网链接。获得实际下载域名后，可将官网按钮接到具体版本；若要官网自动跟随最新版，再接入版本清单。普通下载链接不需要 CORS；将来由网页 fetch 版本清单时再按官网域名配置 CORS。

## 官方参考

- [开通 R2](https://developers.cloudflare.com/r2/get-started/)
- [R2 定价](https://developers.cloudflare.com/r2/pricing/)
- [R2 API 凭据](https://developers.cloudflare.com/r2/api/tokens/)
- [AWS CLI 接入 R2](https://developers.cloudflare.com/r2/examples/aws/aws-cli/)
- [公开访问与自定义域名](https://developers.cloudflare.com/r2/buckets/public-buckets/)
