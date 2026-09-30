# NOTICE — 来源、署名与改动说明

## 上游

本仓库的 `plugin/` 目录是 **[dsh-pocket](https://github.com/shaobeichen/dsh-pocket)** 的修改版。

- 上游作者：**shaobeichen**
- 上游版本：**2.10.6**
- 上游许可：**GPL-2.0**

依据 GPL-2.0 第 2(a) 条，此处声明：**本目录下的文件已被修改**，修改时间自 2026-09-09 起，
修改者为本仓库维护者。原始版权归 shaobeichen 及 dsh-pocket 贡献者所有。

完整许可证见 [`LICENSE`](LICENSE)（与上游一致，GPL-2.0）。

## 改动清单

### 新增文件

| 文件 | 说明 |
|---|---|
| `lib/devices.mjs` | 邀请码存储。一张邀请码 = 一台手机 = 一个二维码。支持创建、列出、撤销、记录最近使用（设备型号 / 时间）。写入采用临时文件 + rename 原子替换，避免读半截。 |

### 修改文件

| 文件 | 改动 |
|---|---|
| `lib/proxy.mjs` | 接上 `auth.getAltTokens(host)`（上游早已预留这个扩展点、但一直没有调用方）。它让邀请码与总密码一起参与鉴权。同时新增可选的 `auth.onAuthenticated(rawToken, req)` 回调，在 `?token=` 命中后触发，用于记录设备使用情况。改动集中在 `authCheck` 的调用点附近，鉴权主流程未变。 |
| `lib/index.js` | 提供 `getAltTokens`（返回当前有效邀请码）与 `onAuthenticated`（记录设备使用）；把 `listInvites` / `createInvite` / `revokeInvite` 传给 RPC 层；`getTunnelConfig` 的 hostname 增加自动发现回退。 |
| `lib/web-rpc.js` | 新增 `devices.list` / `devices.create` / `devices.revoke` 三个端点。 |
| `lib/settings.mjs` | 新增 `detectPublicHost()`：从 `~/.cloudflared/config.yml` 里找出 `service` 指向本插件端口的那条 `hostname`。因为隧道常常不是本插件起的（例如以 Windows 服务方式运行的 cloudflared），插件自身配置里并没有域名，拿不到就会让二维码退化成纯局域网码。 |
| `client/index.jsx` | 面板重写：删去宣传 / star / 反馈 / GitHub 链接 / 更新与重启横幅 / 恢复出厂设置 / 局域网二维码与密码开关 / 手动地址 / 公网隧道配置，只保留「扫码连接手机（二维码）+ 手机授权（列表与删除）+ 修改密码」。二维码改为在客户端用 `qrcode` 生成，主机端不需要新增渲染接口。密码合并为一个（局域网与公网共用，修改时两份一起写）。 |
| `client/api.js` | 新增 `devicesList` / `devicesCreate` / `devicesRevoke` 三个端点常量。 |
| `client/client.js` | 上述前端源码构建产物（`node client/build.mjs`）。 |
| `client/index.jsx.bak-before-panel-rewrite` | 已被移除，不随仓库发布（仅本地重写前的备份）。 |

### 未改动

`lib/ip.mjs`、`lib/restart.js`、`lib/service.mjs`、`lib/tunnel.mjs`、`bin/`、`cordis.patch.yml`、
`client/build.mjs`、`client/pocket-locales.js`、`client/mobile/` 均保持上游原样。

## `android/` 目录

`android/` 是**原创代码**，与上游 dsh-pocket 无关（它不是 dsh-pocket 的一部分）。
本仓库统一采用 GPL-2.0 发布，以便与 `plugin/` 保持一致。

App 与插件之间只通过 **HTTP 通信**，没有代码链接关系。
