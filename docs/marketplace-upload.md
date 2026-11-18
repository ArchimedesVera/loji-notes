# Marketplace 上架填表清单

上传页：<https://plugins.jetbrains.com/author/me/upload>
（登录后也可以从右上角头像 → **Upload plugin** 进入）

## 1. 上传文件

把 `build/dist/loji-notes-0.1.0.zip` 拖进页面中央的上传框，或点 **Choose file**。

页面会解析 zip 里的 `META-INF/plugin.xml`，自动带出插件名、ID、描述、变更日志、兼容版本。
**解析失败会直接报错**，那就说明包结构坏了 —— 重新跑 `build.cmd`。

## 2. 表单字段（可直接照抄）

| 字段 | 填什么 |
| --- | --- |
| Plugin name | `Loji Notes 注释栏` |
| Version | `0.1.0`（自动带出） |
| Category | `Code editing`（次选 `Productivity`） |
| Tags | `comments`, `notes`, `code review`, `annotation` |
| License | `MIT` —— 勾选 "This plugin is licensed under" 后从下拉里选 MIT；仓库根的 `LICENSE` 就是全文 |
| Pricing | `Free` |
| Vendor name | `ArchimedesVera`（和 `plugin.xml` 里的 `<vendor>` 一致） |
| Vendor email | 你的邮箱（审核结果和用户反馈都发这里） |
| Source code URL | 有仓库就填 GitHub 地址；没有可留空，但会降低审核信任度 |
| Issue tracker URL | 可选，同上 |

## 3. 简介文案

短描述（搜索页用，一行）：

```
可拖动的浮动注释卡，用指向线连回代码行；注释存在仓库里的 .loji/notes/，随 git 共享。
```

长描述不用担心 —— 已经写在 `plugin.xml` 的 `<description>` 里，页面会自动渲染成 HTML。

## 4. 截图

在表单的 **Screenshots** 区域上传，建议 2 张：

1. `docs/verify.png` —— 编辑器里三张卡片的真实效果
2. `docs/preview.png` —— 卡片布局与指向线的示意图

每张图下可以写一句说明（可选）。

## 5. 提交

页面**最底部**的 **Upload** 按钮才是提交。

提交后状态变成 `Pending review`，可以在
<https://plugins.jetbrains.com/author/me/plugins> 看到。

审核结果会发邮件到 vendor email。新插件人工审核，通常 1–3 个工作日；
被拒会写明原因（常见：描述与功能不符、缺少许可证、图标不合规、`since-build` 写错）。

## 6. 通过之后

- 插件页地址形如 `https://plugins.jetbrains.com/plugin/12345-loji-notes`
- 用户在 `Settings → Plugins → Marketplace` 搜 `Loji Notes` 即可安装
- 新版本：`build.cmd -Version 0.1.1` 重新打包，到同一页面覆盖上传，更新版本一般自动过审
