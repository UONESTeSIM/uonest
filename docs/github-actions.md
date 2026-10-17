# GitHub Actions 正式版构建

**简体中文** | [English](github-actions.en.md)

工作流：[Build release APK](../.github/workflows/release.yml)。每次推送 `main` 自动递增版本、构建并发布双签名 APK。仅构建普通版 `com.uonest.app`，不修改系统版。

## 首次上传

将源码上传到你自己的 GitHub 仓库，并将工作流提交到默认分支。当前本地目录最初为源码副本，需要先初始化 Git；本文不会自动创建仓库或上传文件。

保留 `.gitmodules` 和 lpac、cJSON 两个子模块的 Git 提交引用，确保 GitHub 能递归检出它们。不要只上传空的子模块目录。lpac 本机的运营商信息读取改动另存为 `tools/ci/patches/lpac-profile-owner.patch`，工作流在编译前自动应用，已应用时跳过。请将补丁一并提交；后续更新 lpac 时，需要检查补丁是否仍适用。

`.gitignore` 已排除本机工具链、构建产物、APK、签名密钥等。提交前检查待上传内容，不要上传 `keystore.properties`、密码、私钥或 `.local-build`。

## 配置 6 个 Secrets

打开仓库 **Settings → Secrets and variables → Actions → New repository secret**，添加以下仓库级 Secrets：

| Secret 名称 | 内容 |
| --- | --- |
| `UONEST_KEYSTORE_BASE64` | 现有 uonest 密钥库文件的 Base64 文本 |
| `UONEST_STORE_PASSWORD` | uonest 密钥库密码 |
| `UONEST_KEY_PASSWORD` | uonest 私钥密码；若与密钥库密码相同，填写相同值 |
| `NINEESIM_KEYSTORE_BASE64` | 现有 9eSIM 社区密钥库文件的 Base64 文本 |
| `NINEESIM_STORE_PASSWORD` | 9eSIM 密钥库密码 |
| `NINEESIM_KEY_PASSWORD` | 9eSIM 私钥密码；若与密钥库密码相同，填写相同值 |

密钥别名固定为 `uonest` 和 `key0`，与当前本机双签名流程一致。不要生成新密钥替代现有密钥。GitHub 托管运行器无法读取 Mac 钥匙串，需要把密码单独存入上述 Secrets。

在 macOS 上可使用以下命令把文件转换后复制到剪贴板，再粘贴到对应 Secret。将示例路径替换为真实路径；无需把密钥或密码发到聊天中。

```sh
base64 -i /path/to/uonest.keystore | tr -d '\n' | pbcopy
base64 -i /path/to/9eSIMCommunityKey.jks | tr -d '\n' | pbcopy
```

两条命令应分别执行、分别粘贴保存，后一条会覆盖剪贴板。Base64 只是编码，不是加密，不能将结果提交到仓库。更多说明见 [GitHub Secrets 文档](https://docs.github.com/en/actions/how-tos/write-workflows/choose-what-workflows-do/use-secrets)。

无需额外设置 GitHub PAT；发布使用工作流自带的 `GITHUB_TOKEN`。仓库或组织策略需要允许发布任务使用 `contents: write`，并允许工作流创建 `v*` 标签与 Release。Secrets 只传入签名步骤，不传入 Gradle 构建步骤。工作流没有 PR 触发器；只在你信任的分支和标签上运行签名工作流，并限制对工作流文件、发布标签和仓库的写入权限。

## 推送 main 自动发布（默认流程）

首次上传并配置 Secrets 后，日常只需提交修改并推送：

```sh
git push origin main
```

每次推送会自动执行：读取已有版本标签 → 递增版本号 → 测试 → 编译 → uonest＋9eSIM 双签名 → 验证 → 创建版本标签 → 发布 GitHub Release。

- 没有已有版本标签时，首次为 **v1.0.1**，之后是 **v1.0.2、v1.0.3…**。
- 有已有版本标签时，在最高有效 `vX.Y.Z` 版本上加一；非此格式的上游标签不参与计算。
- 每个版本绑定触发推送的确切提交，不会在构建过程中改用 main 的最新提交。
- 不需要手动修改版本文件，也不会自动提交版本号回 main。
- 使用 `GITHUB_TOKEN` 创建标签，不会再触发一轮标签构建。
- 连续推送采用共享发布队列，不取消正在运行的发布，也不以新推送替换等待中的发布。使用 GitHub 的 `queue: max`，最多等待 100 个运行；顺序以进入等待队列为准，超限会被取消。见 [官方并发队列说明](https://docs.github.com/en/actions/how-tos/write-workflows/choose-when-workflows-run/control-workflow-concurrency)。
- 一次推送包含多个提交时，生成一个对应推送末端提交的版本；提交消息中的 GitHub 跳过 CI 指令仍可能跳过运行。

**失败重跑：** 测试或签名失败不会发布 Release。创建标签前失败不占用版本；创建标签后失败会保留版本供重跑使用。草稿上传失败可通过 Actions 的 **Re-run jobs** 重试。同一提交已发布时保留现有 Release，不覆盖 APK；旧版本重试不替换较新的 Latest。不要手动移动或删除自动生成的版本标签。

## 手动构建

1. 打开 **Actions → Build release APK → Run workflow**。
2. 选择可信分支，填写版本号，例如 `1.0.1`，不加 `v`。
3. 构建完成后，从该运行的 **Artifacts → uonest-1.0.1** 下载文件。

手动运行仅上传构建产物，不自动创建 Release。压缩包内包含：

- `uonest-1.0.1-dual-signed.apk`
- `uonest-1.0.1-dual-signed.apk.sha256`

Artifacts 默认保留 30 天；长期分发请使用标签发布。

## 手动指定版本标签发布（可选）

日常推送 main 无需手动打标签。如果需要指定版本号，可对一个尚未自动发布的提交创建并推送标签：

```sh
git tag v1.0.1
git push origin v1.0.1
```

工作流将执行测试、编译、双签名、签名和包名校验，然后创建 GitHub Release，上传 APK 和 SHA-256 校验文件，并生成发布说明。上传先在草稿中完成，随后转为公开发布。

仅接受 `vX.Y.Z` 格式，不支持预发布后缀或前导零。`v*` 标签会触发工作流，但格式不合法时会在编译前失败。不要移动已发布标签或重复使用版本号。若同一提交已自动发布，不要再为它创建另一个版本标签。已有同名标签指向其他提交时，工作流会停止；草稿可重跑续传，已发布版本不会覆盖。

## 版本号与签名

- 包名固定为 `com.uonest.app`。
- `versionName` 使用 main 自动计算、手动输入或标签中的 `X.Y.Z`。
- `versionCode = X × 1,000,000 + Y × 1,000 + Z`。例如 `1.0.1` 对应 `1000001`。
- X 最大为 2099，Y 和 Z 最大为 999；不接受 `0.0.0`。自动版本在补丁号达到 999 后进位，例如 `1.0.999 → 1.1.0`。手动指定版本也应递增。
- CI 版本码高于之前本机 `versionCode=1` 的构建。之后若改回本机发布，也必须使用同一版本码规则，才能继续正常更新。
- 保持 APK Signature Scheme **v2 双签名**，关闭 v1、v3、v4，与现有本机双签名方式一致。

签名脚本检查恰好包含以下两个证书的 SHA-1 指纹，否则删除产物并失败：

| 签名者 | SHA-1 |
| --- | --- |
| uonest | `23BEBA4A34379ED306CFE63EAF4FE8EECA9979C9` |
| 9eSIM | `D1C0F48B370E74D4EA4770ED4C3CD70A3198D31F` |

签名之前还会检查包名、版本名和版本码；签名后检查 APK 对齐及签名有效性，再生成校验文件。

## 构建环境与排错

工作流使用 Ubuntu 24.04、Temurin JDK 21、Android SDK 37.0（SDK 包名为 `platforms;android-37.0`）、Build Tools 37.0.0 和 NDK 26.1.10909125，使用仓库的 Gradle Wrapper。Android 组件需可从 SDK 仓库下载。第三方 Actions 固定到已核实的提交 SHA。

- `Missing Actions Secrets`：检查上述 6 个名称和值。
- 签名失败：检查密钥库、密码和别名；不会输出包含签名输入的原始错误日志。
- 证书不匹配：确认使用的是原有两个密钥库。
- 子模块或补丁失败：确认依赖提交已记录、可访问，且补丁与版本匹配。
- 发布权限失败：检查仓库/组织的 Actions 权限和版本标签规则。
- SDK 或依赖下载失败：查看安装/Gradle 步骤的日志；不自动更换 SDK 或依赖版本。

本地可执行发布工具测试：

```sh
python3 -m unittest discover -s tools/ci/tests -v
```

这套配置不上传源码或密钥到 GitHub；你需要自行创建仓库、推送代码并配置 Secrets。首次云端运行通过后，才能确认 GitHub 环境中的完整发布结果。
