# DSH Android App

自用的手机端 DSH 外壳：**打开即用，不用扫码、不用输密码**，自动判断该连局域网还是公网。

```
包名   cn.kaelorvyn.dsh
版本   1.0 (versionCode 1)
大小   约 118 KB
签名   调试签名（自用，不上架）
minSdk 24 (Android 7.0)   targetSdk 34
```

产物：仓库根目录 `DSH.apk`，也复制了一份到桌面 `%USERPROFILE%\Desktop\DSH.apk`。

---

## 它是怎么工作的

启动时**并行探测三个候选地址**，谁先返回有效响应就用谁：

| 候选 | 地址 | 超时 |
|---|---|---|
| ① 上次成功的局域网地址 | `http://<记住的IP>:3081/?token=<局域网密码>` | 900ms |
| ② 按当前网段推算的局域网地址 | `http://<当前网段>.<记住的尾号>:3081/?token=<局域网密码>` | 900ms |
| ③ 公网（永远可用的兜底） | `https://dsh.kaelorvyn.cn/?token=<公网密码>` | 4000ms |

局域网只给 900ms —— 同网段 RTT 通常 <20ms，够用；给了也白给只会拖慢切到公网的时机。
公网给 4s —— 要走 TLS 握手 + Cloudflare 隧道，链路长。

**为什么 `?token=` 就能免密**：pocket 的反代（`dsh-pocket/lib/proxy.mjs`）里，
`?token=<原始 PIN>` 是「扫码直达」通道——校验通过后会**种下 HttpOnly cookie**
（`dsh_pocket_token`），浏览器后续请求就都带着它了。所以 App 只要拼对 URL 就是登录态。

**cookie 只搬一半**：探测请求顺手把 `dsh_pocket_token` 塞进 WebView 的 cookie 罐，
但**故意不搬** `dsh-auth-*`（dsh web 自己的会话 cookie）——因为 pocket 专门为
「扫码进来的浏览器」写了补丁：首次 `GET /` 没有该 cookie 时自动补一次 launch token。
我们替它搬过去，反而会让补丁以为会话已建立而跳过握手。

**防呆**：pocket 未认证时返回的是**登录页，但 HTTP 状态码是 200**（不是 401）。
所以探测时除了看状态码，还会读一小段正文，命中 `name="token"` + `method="post"` 就判为失败，
免得 App 停在一个让人输密码的页面上——那就违背「免密」的初衷了。

---

## 密码是怎么进去的（重要）

密码**不写在源码里**。构建时 `app/build.gradle` 从本机
`%USERPROFILE%\.dsh\dsh-pocket\token`（公网）和 `token-lan`（局域网）读出来，
**逐字符异或 `0x5A` 后转十六进制**，作为 `buildConfigField` 注入。
运行时 `MainActivity.unscramble()` 再还原。

- ✅ 源码、仓库里都不会出现明文密码
- ⚠️ 但这只是防「`strings` 一眼看穿」，**不是密码学保护** —— APK 里本来就带着凭证，
  这是「免密」这个需求本身的代价。别把这个 APK 发给别人。
- ⚠️ **改了密码必须重新构建 APK**，否则 App 里还是旧的。

当前两个密码都是「自定义」状态（`settings.json` 里 `publicPinCustom` / `lanPinCustom` 均为 true），
所以**不会自动轮换**，打一次包可以长期用。这也是能免密的前提。

---

## 重新构建

工具链（本机已装好）：

| 组件 | 位置 |
|---|---|
| JDK 21 | `C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot` |
| Android SDK | `D:\Android\Sdk`（cmdline-tools + platform-tools + android-34 + build-tools 34.0.0） |
| Gradle 8.7 | `D:\gradle-8.7`（用户自己装的） |
| AGP | 8.5.2（Gradle 自动下载） |

```powershell
$env:JAVA_HOME   = 'C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot'
$env:ANDROID_HOME= 'D:\Android\Sdk'
& 'D:\gradle-8.7\bin\gradle.bat' -p 'D:\DSH Desktop\无分组\dsh-android' assembleRelease
```

产物在 `app/build/outputs/apk/release/app-release.apk`。

**工程放在中文目录（「无分组」）下**，AGP 默认会拒绝，所以 `gradle.properties` 里加了
`android.overridePathCheck=true`。不加这行会直接构建失败。

---

## 安装到手机

1. 把 `DSH.apk` 传到手机（微信文件传输 / 数据线 / 网盘都行）
2. 手机上点开安装，**允许「安装未知来源应用」**
3. 装好后桌面出现 **DSH** 图标（鲸鱼素材 `DSniang1.png`）

**没有签名冲突问题**：这是全新包名 `cn.kaelorvyn.dsh`，和现有的任何 App 都不冲突。

---

## 窄屏适配（注入式，不碰 DSH 源码）

手机上原本「比例不对、底部统计条又挤又大」。查下来是**两个独立的问题**：

### 病根一：Android WebView 的「字号自动放大」（Font Boosting）

DSH 的 CSS 里**完全没有声明 `text-size-adjust`**（`index-*.css` 和 `vendor-*.css` 都查过，
一个都没有）。Chrome/WebView 对「窄布局 + 小字号 + 较宽文本块」的页面会自动把字撑大以提升可读性，
整页比例因此失真 —— 这才是「画面比例不合适 / 太大」的真正根源。

**修法**：注入 `html,body{-webkit-text-size-adjust:100%!important;text-size-adjust:100%!important;}`
关闭该行为，文字按 CSS 里的真实字号渲染。

### 病根二：底部统计条信息量对手机来说太大

底部两行来自**两个不同元素**，**都带 `data-dsh-stats`**（官方留的稳定钩子）：

| 元素 | 来源 | 原样式 |
|---|---|---|
| `div.bxNl9a_root[data-dsh-stats]` | DSH 核心（`dsh-client-ui-chat`） | `font-size:13px` |
| `div.cm-root[data-dsh-stats]` | 记账插件（小鲸鱼） | `font-size:12px` |

两者都是 `white-space:nowrap` + `overflow:hidden`，桌面宽度正好一行放得下；
**手机上放不下就直接裁**。浏览器实测（360px 宽）：

```
修复前：scrollWidth 861px > clientWidth 360px   →  60% 的内容被裁掉
```

**处理：手机上只保留三项、排成一行** —— `xxx tok/s | 缓存命中 xx% | 本会话 ¥x`

### 实现：为什么不直接改文本

两个障碍：

1. `cm-root`（记账行）是**单个纯文本节点**（`本会话 ¥x · 输入 … · 缓存 … · 输出 …`），
   CSS 没法只留前半截；
2. `tok/s` 前面还挂着「首 token 平均 1.3秒 · 」，也得切掉。

所以必须用 JS 取数。但**不能直接改 `textContent`** —— 那是 React 管的节点，
改了会被覆写、还可能让 React 报错。做法是：

```
JS 每 500ms 读一次 → 把精简结果写进 data-dsh-mini 属性
CSS 用 content:attr(data-dsh-mini) 渲染出来
```

只写一个 **React 不认识的属性**，不碰任何子节点 —— 无侵入，且照常实时同步。

### 验证方式（值得复用）

浏览器开了个 360px 的 iframe 加载仿真页（`test/mock.html`：DOM 结构与 DSH 一致 +
每秒模拟数值变化），再用父页读取 iframe 内部的计算结果。实测：

```
视口宽度     : 360
渲染出的一行 : 249 tok/s | 缓存命中 98% | 本会话 ¥5.3839
记账行已隐藏 : true          原始子元素已藏: 9/9
行数         : 1             被裁: false
实时同步     : ✅ 249→263 tok/s、¥5.3839→¥5.4081 已跟上
```

> 方法教训：用「强制元素变窄」模拟手机**不可靠** —— 媒体查询仍按真实窗口宽度求值。
> 要验窄屏，必须让**真实视口**变窄（iframe 就是一个干净的办法）。

### 为什么用注入而不是改 DSH 源码

- 不动部署文件，**DSH 升级后补丁不会丢**
- 出问题把 `injectMobileCss()` 一删即可，影响面只有手机 App
- 选择器全部锚在 `data-dsh-stats` 这类语义属性上，**不依赖散列类名**
  （`bxNl9a_root` 这种哈希前缀一升级就变，不能用）

代价：只修 App，用手机浏览器扫码访问的路径不受影响。要给浏览器路径也修，
得改 `dsh-web-frontend` 的 CSS 并重新构建 Web 产物。

---

## 重启后还管用吗（已逐项验证）

| 项目 | 重启后 | 依据 |
|---|---|---|
| App 里的密码 | ✅ 有效 | `token`/`token-lan` 是磁盘文件，两个 `PinCustom: true` → 不自动轮换 |
| cloudflared（公网入口） | ✅ 自动起 | 服务 `Automatic` |
| SakuraFrp（官网） | ✅ 自动起 | 服务 `Automatic` + 启动文件夹有 launcher |
| 注入的 CSS/JS | ✅ 不丢 | 在 APK 里，每次加载重新注入 |
| **登录 cookie** | ⚠️ **失效** | pocket 的 `sessionKey` 每次进程启动重新随机（`randomBytes(16)`） |

cookie 失效**不影响使用**：App 每次打开都会重新用 `?token=` 登录。
唯一的边角情况是「DSH 重启时 App 正开着」—— `onResume` 里加了自动恢复：
回到前台发现是登录页/中转页就自动重连。

**DSH Desktop 本身不在开机自启列表**，所以电脑重启后要手动开 DSH，
手机才连得上（cloudflared/SakuraFrp 只是管道，源头没开就没人应答）。

---

## 已知边界

- **局域网探测是启发式的**：App 无法主动知道电脑的局域网 IP，只能用
  「上次成功的 IP」+「按当前网段换算尾号」两种猜测。换了路由器网段、或电脑 IP 变了，
  局域网会探测失败，但**会自动落到公网**，不影响使用；下次连对之后又会记住新 IP。
- **只认 `wlan`/`eth` 开头的网卡**，绕开 Hyper-V（172.x 虚拟网卡）、VPN、热点等干扰网段。
- **明文 HTTP（局域网段）**：`usesCleartextTraffic="true"` 是必需的，局域网走的是
  `http://192.168.x.x:3081`。公网段是 HTTPS，由 Cloudflare 提供证书。
- 公网段受 Cloudflare 免费版限制：100MB 上传、100 秒响应超时（日常用不到）。
