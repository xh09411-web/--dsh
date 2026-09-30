# DSH Mobile

把 **DeepSeek Harness（DSH）** 装进手机：**扫码即连，免输密码**；电脑上能看见「哪些手机连过」并**逐台收回权限**。

本仓库 = 两部分：

| 目录 | 是什么 |
|---|---|
| `plugin/` | **dsh-pocket 的魔改版**（GPL-2.0 分支）。DSH 插件，在电脑上跑一个反向代理，把本机 DSH 暴露到局域网 / 公网，并负责鉴权。 |
| `android/` | **DSH Android App**。一个 WebView 壳 + 自带相机扫码，扫电脑上的二维码后自动绑定并选路连接。 |

---

## 它解决什么问题

原生 dsh-pocket 的远程访问：**一个总密码给所有设备**。带来的麻烦是——

- 想让第二台手机连进来，得把密码告诉对方；
- 某台手机丢了 / 不想让它连了，**只能整体换密码**，所有设备都得重连；
- 密码轮换后，之前发出去的链接全部失效。

这套改动把授权模型换成 **「一张邀请码 = 一台手机」**：

- 电脑上那个二维码里装的是**邀请码**，不是总密码；
- 手机扫了就用它连，**不用输密码**；
- **删掉哪张邀请码，用它的那台手机立刻失效**，下次打开 App 自动回到扫码界面；
- 总密码仍然保留，给电脑自己的浏览器等场景用。

## 一个二维码，两种用法

二维码内容形如：

```
https://<你的域名>/?dshbind=1&pp=<邀请码>&lp=<邀请码>&li=<局域网IP>&lport=<端口>
```

- **手机浏览器**扫 → 就是个普通网址，正常打开 DSH 网页，**照旧要输密码**（`dshbind` 参数被无视）；
- **DSH App** 扫 → 认出参数，把公网地址和局域网地址一起存下来，**免密码**连上，并自动选择更快的局域网。

二维码同时带公网和局域网入口，所以**在家和在外面扫的是同一个码**。

## 手机端

- **自带相机扫码**：全屏取景，用系统 `Camera` + ZXing 本地解码，**不依赖任何扫码 SDK**。
- **支持从相册选码**：二维码在截图里、聊天记录里、另一台设备上时，直接选图识别。走系统相册，**不需要任何存储权限**。
- **自动选路**：并发探测局域网和公网入口，谁先通用谁；网络刚切换（比如 WiFi 断掉改走流量）导致的瞬时失败会**自动重试一次**。
- **连不上时给说人话的原因**：失败页会列出每个入口的探测结果（超时 / 密码不对 / 端口连不上 / 域名解析不了），而不是一句「连不上」。
- 全面屏适配（沉浸式状态栏）、针对手机窄屏做了界面压缩。

## 电脑端（插件面板）

DSH → 设置 → 手机访问，只有三块：

```
┌ 扫码连接手机 ───────────────┐
│         [ 二维码 ]           │
├ 手机授权 ─────────────────┤
│ PJH110     最近连接 09-11 22:30  [删除] │
│                     [再加一台] │
├ 修改密码 ──────────────────┤
│ 密码        ••••••••  [修改]  │
└───────────────────────────┘
```

- **手机授权**：列出每台扫码连过的手机（型号 + 最近连接时间），逐台删除。
- **只有一个密码**：局域网和公网共用同一个，改的时候两份一起改。

---

## 安装

### 1. 电脑端：安装插件

把 `plugin/` 覆盖到你的 dsh-pocket 安装位置（通常是 `~/.dsh/profiles/<profile>/node_modules/dsh-pocket/`），然后重启 DSH。

前端包 `client/client.js` 已经构建好，开箱即用。改了 `client/index.jsx` 的话重新构建：

```bash
cd plugin/client && node build.mjs
```

### 2. 公网入口（可选）

插件会**自动发现**公网域名：它读取 `~/.cloudflared/config.yml`，找出 `service` 指向本插件端口（默认 3081）的那条 `hostname`。

所以用命名隧道（Cloudflare Named Tunnel）时，**不需要在插件里重复配一遍域名**：

```yaml
tunnel: <你的隧道 ID>
credentials-file: <凭据文件>
ingress:
  - hostname: dsh.example.com
    service: http://127.0.0.1:3081
    originRequest:
      # 【关键】不设这个，cloudflared 会把 Host 改成 127.0.0.1:3081，
      # 插件会按来源判定成「局域网」，于是公网访问却要局域网密码。
      httpHostHeader: dsh.example.com
  - service: http_status:404
```

### 3. 手机端：编译 App

```bash
cd android
./gradlew assembleRelease
```

需要 JDK 17+ 和 Android SDK（`minSdk 24`、`targetSdk 34`）。产物在 `app/build/outputs/apk/release/`。

> 注意：仓库里的 `applicationId` 是 `cn.kaelorvyn.dsh`。要做成自己的，改 `app/build.gradle` 里的 `namespace` / `applicationId` 即可。

### 4. 配对

1. 电脑上打开 DSH → 设置 → 手机访问，会显示一个二维码；
2. 手机打开 App，扫这个码（扫不上就点「从相册选择」）；
3. 自动连上。以后直接开 App 就行 —— 在家走局域网，在外面走公网，不用管。

---

## 安全说明

- 邀请码是 64 位随机值，存在 `~/.dsh/dsh-pocket/devices.json`；**该文件不在本仓库**。
- 邀请码只授予「访问你这台电脑上的 DSH」的权限，**不等同于** DSH 本身的凭据。
- 公网入口**始终要求鉴权**（这条是 fail-closed 的：除了 loopback / 私网 Host，一切陌生域名都按公网处理）。
- 删掉邀请码后，用它的手机在**下一次请求**就会被拒；已建立的登录 cookie 也因为绑定进程级密钥而失效。
- **请不要把 `~/.cloudflared/` 里的凭据文件、`devices.json`、`settings.json` 提交到任何仓库。**

---

## 与上游的差异

上游：[`shaobeichen/dsh-pocket`](https://github.com/shaobeichen/dsh-pocket)（GPL-2.0，版本 2.10.6）。

本分支新增 / 改动：

**新增**
- `lib/devices.mjs` —— 邀请码存储（创建 / 列出 / 撤销 / 记录最近使用）。
- `client/index.jsx` 里的二维码面板（客户端用 `qrcode` 直接画，主机端不用加渲染接口）。
- Android App 全部代码（`android/`，原创）。

**改动**
- `lib/proxy.mjs` —— 接上早已预留但没人提供的 `auth.getAltTokens()` 扩展点（让邀请码和总密码一起参与鉴权），并新增 `auth.onAuthenticated()` 回调用于记录「哪台设备用了哪张邀请码」。
- `lib/index.js` —— 提供 `getAltTokens` / `onAuthenticated`；新增 `devices.*` RPC。
- `lib/web-rpc.js` —— 新增 `devices.list` / `devices.create` / `devices.revoke`。
- `lib/settings.mjs` —— 新增 `detectPublicHost()`，从 cloudflared 配置里自动发现公网域名。
- `client/index.jsx` —— 面板大幅精简（删掉宣传 / star / 反馈 / 更新横幅 / 恢复出厂 / 局域网开关 / 手动选地址 / 公网隧道配置等），只留「二维码 + 手机授权 + 改密码」；密码合并为一个。
- `client/api.js` —— 新增三个 RPC 端点常量。

更详细的改动记录见 [`NOTICE.md`](NOTICE.md)。

## 许可

**GPL-2.0**（继承上游 dsh-pocket）。见 [`LICENSE`](LICENSE)。
