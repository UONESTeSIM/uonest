<p align="center">
  <img src="docs/assets/uonest-logo-animated.png" alt="uonest" width="280">
</p>

<p align="center">
  <strong>简体中文</strong> | <a href="README.en.md">English</a>
</p>

uonest 是一款 Android eSIM 管理工具，面向兼容的可插拔 eSIM 和 USB 读卡器，提供配置下载、启用、禁用、删除及信息查看功能。

官网：[uonest.com](https://uonest.com)

## 项目来源

uonest 基于 [OpenEUICC / EasyEUICC](https://gitea.angry.im/PeterCxy/OpenEUICC) 修改开发，围绕界面设计、下载流程和日常管理体验进行了调整。感谢原作者 PeterCxy 及所有贡献者。

本项目为独立衍生版本，与上游项目无官方关联。上游项目及其贡献者的版权与许可证声明保留在下方；uonest 的界面和流程调整不代表上游版本的行为。

## 主要功能

- **添加 eSIM**：扫描二维码、从图片识别或手动输入激活码。
- **下载目标选择**：首页添加入口使用当前可用卡槽；桌面下载快捷方式和外部激活链接在添加页选择目标。
- **下载过程展示**：显示实际下载阶段，支持确认码输入、成功与失败结果以及失败诊断。
- **号卡管理**：查看配置、重命名、启用、禁用和删除。
- **运营商与国旗展示**：使用本地匹配数据和图标资源；显示结果取决于可读取的配置信息及匹配规则。
- **USB 读卡器**：识别兼容读卡器，经系统授权并成功读取后管理卡内 eSIM。
- **通知管理**：配置操作通知的自动发送，查看与手动发送保留的通知；成功发送的通知自动清理。
- **设置与诊断**：eSIM 详情、兼容性检查、自定义 ISD-R AID、详细日志和运行日志。

界面采用浅色背景和轻玻璃卡片，统一添加、下载、管理与设置页面的视觉风格。

## 兼容性与使用条件

| 项目 | 说明 |
| --- | --- |
| Android 版本 | 普通版最低支持 Android 9；实际可用性取决于设备与卡片 |
| 可插拔 eSIM | 需要兼容的 eUICC 卡片；通过手机卡槽访问时，卡片的访问规则需允许应用签名 |
| USB 读卡器 | 支持兼容的 USB CCID、T=0 读卡器；手机需支持 USB 主机模式，并授予 USB 访问权限 |
| 手机内置 eSIM | 普通版不作为手机内置 eSIM 的通用管理工具；仓库保留的系统版需要相应系统权限与集成 |
| 下载连接 | 下载 eSIM 需要网络连接及有效激活码，部分配置还需要确认码 |

USB 读卡器访问不依赖手机卡槽的应用签名白名单，但仍受读卡器、卡片协议和设备兼容性限制。运营商图标与国旗来自本地匹配，不应将其视为当前实际接入网络的证明。

## ARA-M 证书指纹

uonest 发布签名证书的 **SHA-1 指纹**：

```text
23BEBA4A34379ED306CFE63EAF4FE8EECA9979C9
```

该值用于可插拔 eSIM 的 ARA-M 应用访问规则，标识 uonest 的签名证书，并非 APK 文件的哈希。需要为卡片配置访问权限时，可将此值提供给卡片厂商。

此指纹对应 uonest 自有发布密钥；使用其他密钥签名的构建，其指纹会不同。实际安装版本可在应用的“关于 → ARA-M 证书指纹”中核对。

## 安装与使用

1. 安装由 uonest 项目提供的 APK。上游 EasyEUICC 的安装包是另一个版本。
2. 插入兼容的可插拔 eSIM，或连接兼容读卡器并完成系统授权。
3. 在首页选择可用目标，点击右上角 **+**，选择添加方式。
4. 输入或识别激活码后开始下载；如需要确认码，按弹窗提示填写。
5. 下载完成后，在首页查看和管理号卡。

删除配置后无法在应用内恢复，重新下载可能需要向运营商获取新的激活码。安装更新时，应使用与已安装版本兼容的包名和签名。

## 从源码构建

使用 Android Studio 或配置好 JDK、Android SDK 的命令行环境。当前普通版模块配置使用 Android SDK 37、NDK `26.1.10909125`；本地构建使用 JDK 21。构建工具版本以仓库中的 Gradle 配置为准。

Git 克隆后初始化依赖：

```sh
git submodule update --init --recursive
```

使用源码压缩包时，请确认 `libs/lpac-jni/src/main/jni/` 下的 lpac 与 cJSON 源码完整。

普通版调试构建：

```sh
./gradlew :app-unpriv:assembleDebug
```

正式版可在项目根目录配置本机专用的 `keystore.properties`，以下仅为占位示例：

```properties
storeFile=/absolute/path/to/your-release.keystore
storePassword=YOUR_STORE_PASSWORD
keyAlias=YOUR_KEY_ALIAS
keyPassword=YOUR_KEY_PASSWORD
unprivKeyAlias=YOUR_KEY_ALIAS
unprivKeyPassword=YOUR_KEY_PASSWORD
```

该文件已加入忽略规则，请勿将实际密码或签名私钥提交到源码仓库。此配置用于单密钥签名；额外的发布签名流程需单独配置。

```sh
./gradlew :app-unpriv:assembleRelease
```

APK 输出位于 `app-unpriv/build/outputs/apk/`。没有 Release 签名配置时，产物需要签名后才能安装。

**发布配置说明：** 当前模块的默认 `applicationId` 仍为上游的 `im.angry.easyeuicc`；uonest 正式版通过本机或 CI 发布配置覆盖为 `com.uonest.app`。GitHub Actions 会自动应用 CI 配置；单独运行上述命令不会自动应用发布覆盖配置。发布自己的版本前，请明确设置应用名称、包名、版本号及签名，避免与上游应用混淆。

仓库还保留系统版 `app` 模块，可使用 `./gradlew :app:assembleRelease` 构建。系统版需要系统集成和特权权限，不等同于普通安装的 APK；相关集成文件见 [Android.bp](Android.bp) 与 [权限白名单](privapp_whitelist_im.angry.openeuicc.xml)。

## 文档与第三方来源

- [外部激活链接格式](docs/DEEP-LINK.md)：保留的上游协议说明；实际界面使用 uonest 添加流程。
- [OpenEUICC / EasyEUICC](https://gitea.angry.im/PeterCxy/OpenEUICC)：应用基础与 LPA 实现。
- [lpac](https://github.com/estkme-group/lpac)：eSIM 协议相关实现；各组件许可见对应源码目录。
- [cJSON](https://github.com/DaveGamble/cJSON)：JSON 解析库，见 [MIT 许可证](libs/lpac-jni/src/main/jni/cjson/cjson/LICENSE)。
- [NekokoLPA/operator-icons](https://github.com/NekokoLPA/operator-icons)：运营商匹配数据与图标，见 [来源及版本记录](app-common/src/main/assets/operator_catalog/SOURCE.txt)。该快照未包含 LICENSE 文件，不能据此将素材声明为 MIT；相关许可与商标说明以来源记录为准。
- 国旗素材：见 [来源说明](docs/flag-assets.md)。
- 国家代码数据：见 [来源与 Apache-2.0 许可证](app-common/src/main/assets/iccid_country_codes_LICENSE.txt)。
- Lucide 图标：见 [许可证说明](docs/lucide-license.txt)。

## 版权与许可证

应用主体沿用上游的 **GNU GPL 第 3 版（GPL-3.0-only，不含“或更新版本”条款）**，完整文本见 [LICENSE](LICENSE)。修改与再分发须遵守相应许可证，包括适用的对应源代码提供要求。第三方组件和素材按各自的许可与来源说明处理。

以下保留上游版权及许可声明；`art/` 的署名指仓库内保留的上游美术资源：


Everything except `libs/lpac-jni` and `art/`:

```
Copyright 2022-2026 OpenEUICC contributors

This program is free software; you can redistribute it and/or
modify it under the terms of the GNU General Public License
as published by the Free Software Foundation, version 3.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program; if not, write to the Free Software
Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
```

`libs/lpac-jni`:

```
Copyright (C) 2022-2026 OpenEUICC contributiors

This library is free software; you can redistribute it and/or
modify it under the terms of the GNU Lesser General Public
License as published by the Free Software Foundation, version 2.1.

This library is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
Lesser General Public License for more details.

You should have received a copy of the GNU Lesser General Public
License along with this library; if not, write to the Free Software
Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301  USA
```

`art/`: Courtesy of [Aikoyori](https://github.com/Aikoyori), CC NC-SA 4.0.
