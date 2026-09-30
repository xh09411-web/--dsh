package cn.kaelorvyn.dsh;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsetsController;
import android.webkit.CookieManager;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.ValueCallback;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * DSH —— 自用 WebView 外壳。
 *
 * 启动时并行探测「局域网入口」与「公网入口」，谁先通就加载谁：
 *   局域网 http://<PC 局域网 IP>:3081/?token=<局域网密码>
 *   公网   https://dsh.kaelorvyn.cn/?token=<公网密码>
 * pocket 见到 `?token=` 会直接放行并种下 HttpOnly cookie，所以整个过程不用手输密码。
 * 局域网只给 900ms（同网段 RTT 极低），公网给 4s（TLS + 隧道链更长）。
 */
public class MainActivity extends Activity {

    /** 公网入口，永远可用的兜底地址。 */
    private static final String PUBLIC_HOST = "https://dsh.kaelorvyn.cn";
    /** pocket 反代端口。 */
    private static final int LAN_PORT = 3081;
    private static final int LAN_TIMEOUT_MS = 900;
    // 公网超时给足：这条是「在外面用流量」的兜底路，要经过 DNS + TLS + Cloudflare 隧道，
    // 冷启动经常 4 秒都走不完 —— 早先写 4000，流量下会误判成失败，用户看到的就是
    // 「扫了码也连不上」。候选是并发探测、谁先成功用谁，所以放宽它不会拖慢正常连接。
    private static final int PUBLIC_TIMEOUT_MS = 12000;
    /** 记不住 PC 的局域网尾号时的默认猜测（本机就是 192.168.1.3）。 */
    private static final String DEFAULT_LAST_OCTET = "3";

    private static final String PREF = "dsh";
    private static final String KEY_LAN_HOST = "lan_host";
    /** 本次冷启动是否已经撤销过布局强制（防止重载循环）。 */
    private boolean layoutPinned = false;
    /** pocket 的认证 cookie 名，见 dsh-pocket/lib/proxy.mjs 的 TOKEN_COOKIE。 */
    private static final String COOKIE_POCKET_TOKEN = "dsh_pocket_token=";
    /** 申请相机权限的请求码（App 内扫码用）。 */
    private static final int REQ_CAMERA = 1001;
    private static final int REQ_PICK = 1002;

    /**
     * 窄屏样式补丁（注入到页面里，不进 DSH 源码，DSH 升级也不会丢）。
     *
     * 病根一：**Android WebView 的字号自动放大（Font Boosting）**
     *   DSH 的 CSS 里完全没有声明 `text-size-adjust`（本文件和 vendor CSS 都查过）。
     *   于是 Chrome/WebView 对「窄布局 + 小字号 + 较宽文本块」的页面会自动把字撑大，
     *   手机上整页比例就失真——这是「画面比例不合适 / 太大」的根源。
     *   声明 `text-size-adjust:100%` 即关闭该行为，文字按 CSS 里的真实字号渲染。
     *
     * 病根二：**底部统计条信息量对手机来说太大**
     *   底部两行来自两个元素，都带 `data-dsh-stats`：
     *     div.bxNl9a_root  核心统计（29 轮·659 步 | LLM 耗时 | 首token | 缓存命中 | 输入输出）
     *     div.cm-root      记账插件（本会话 ¥x · 输入 … · 缓存 … · 输出 …）
     *   手机上一行放不下就被 `overflow:hidden` 直接裁掉。
     *
     * 处理：手机上**只保留三项、排成一行** —— `xxx tok/s | 缓存命中 xx% | 本会话 ¥x`。
     *
     * 实现要点：`cm-root` 是**单个纯文本节点**，CSS 没法只留前半截；`tok/s` 前面还挂着
     * 「首 token 平均 1.3秒 · 」。所以由 JS 取数、写进 `data-dsh-mini` 属性，
     * 再用 `content:attr(data-dsh-mini)` 渲染。
     * **不碰 React 管的子节点**——直接改 textContent 会被 React 覆写、还可能让它报错；
     * 只设一个 React 不认识的属性，是无侵入的。数值每 500ms 重读，所以照常实时同步。
     */
    private static final String MOBILE_CSS =
            // ① 关掉 WebView 的字号自动放大——影响整页比例
            "html,body{-webkit-text-size-adjust:100%!important;text-size-adjust:100%!important;}"

            // ② 【失效安全门】以下全部挂在 html[data-dsh-mini-ready] 之下。
            //    这个标记只在 JS **成功算出精简行**之后才打上；JS 失败时整段不生效，
            //    原始统计条原样保留，绝不会变空白。
          + "html[data-dsh-mini-ready] [data-mobile-nav=\"stats\"]{display:none!important;}"

            // ③ 【关键】手机上真正的钩子是 `data-mobile-nav="stats"`（pocket 的
            //    dsh-mobile-nav 插件加的），**不是** `data-dsh-stats`。
            //    后者是 aqua 主题插件用 seam-stamper 盖的章，手机上 aqua 没启用，
            //    所以那个属性在手机上根本不存在 —— 这是前几版全部失效的真正原因。
            //    记账行的 `.cm-root` 没有任何属性，用 dock 结构定位。
          + "html[data-dsh-mini-ready] [data-slot=\"conversation.composer.dock\"] > .cm-root{display:none!important;}"

            // ④ 只放行被 JS 选中并打了 data-dsh-mini 的那一条；顺手压掉可能的固定巨宽
          + "html[data-dsh-mini-ready] [data-mobile-nav=\"stats\"][data-dsh-mini]{"
          + "  display:block!important;"
          + "  width:auto!important;"
          + "  max-width:100%!important;"
          + "  min-width:0!important;"
          + "  box-sizing:border-box!important;"
          + "  margin:0 auto!important;"
          + "  padding:3px 10px 0!important;"
          + "  position:static!important;"
          + "  z-index:auto!important;"
          + "  background:none!important;"
          + "  border:0!important;"
          + "  box-shadow:none!important;"
          + "  backdrop-filter:none!important;"
          + "  border-radius:0!important;"
          + "  white-space:normal!important;"
          + "  overflow:visible!important;"
          + "  text-overflow:clip!important;"
          + "}"

            // ⑤ 宿主的原始内容全部藏掉，只留精简行
          + "html[data-dsh-mini-ready] [data-mobile-nav=\"stats\"][data-dsh-mini] > *{display:none!important;}"
          + "html[data-dsh-mini-ready] [data-mobile-nav=\"stats\"][data-dsh-mini]::after{"
          + "  content:attr(data-dsh-mini);"
          + "  display:block;"
          + "  font-size:11.5px;"
          + "  line-height:1.5;"
          + "  white-space:nowrap;"      // 用户要求「一排就行」
          + "  overflow:hidden;"
          + "  text-overflow:ellipsis;"
          + "}"

            // ⑥ 输入框下方那排工具：指令 / 访问模式 / 上传文件 / 模型 / 上下文 / 发送。
            //    真机(360px)实测的毛病：模型选择器宽 138px，而它的父级只有 116px ——
            //    **溢出并盖住右边的「上下文已用」16px**；发送按钮右边到 336px，
            //    而可用宽度只有 328px，**超出屏幕 8px**。
            //    这一排本来 300 + 间隙 30 = 330 > 328，怎么都挤。
            //    按 aria-label 精确缩（这些标签是稳定钩子，比散列类名可靠）：
            //    收紧后 无重叠，最右 306px，留 22px 余量。
          + "[data-composer-card] > div:last-child{gap:5px!important;padding:2px 8px 6px!important;}"
            // 纯图标按钮
          + "[data-composer-card] button[aria-label=\"指令\"],"
          + "[data-composer-card] button[aria-label=\"上传文件\"],"
          + "[data-composer-card] button[aria-label^=\"上下文已用\"]{"
          + "  width:24px!important;height:24px!important;"
          + "  min-width:24px!important;min-height:24px!important;padding:0!important;"
          + "}"
            // 发送 / 停止：主要操作，留略大一点。
            // 注意用前缀匹配 —— 空闲时 aria-label 是「发送消息」，生成中是「停止生成」，
            // 精确匹配 "发送" 两个都匹配不上（踩过这个坑：按钮一直是 34px 没被缩小）。
          + "[data-composer-card] button[aria-label^=\"发送\"],"
          + "[data-composer-card] button[aria-label=\"停止生成\"]{"
          + "  width:28px!important;height:28px!important;"
          + "  min-width:28px!important;min-height:28px!important;padding:0!important;"
          + "}"
            // 带文字的：降高度、收紧内边距、缩字号
          + "[data-composer-card] button[aria-label^=\"访问模式\"],"
          + "[data-composer-card] button[aria-label^=\"选择模型\"]{"
          + "  height:24px!important;min-height:24px!important;padding:0 6px!important;"
          + "}"
          + "[data-composer-card] button[aria-label^=\"访问模式\"] span,"
          + "[data-composer-card] button[aria-label^=\"选择模型\"] span{"
          + "  font-size:11px!important;line-height:1.2!important;"
          + "}"
            // 模型名太长撑破父级：限宽 + 省略号
          + "[data-composer-card] button[aria-label^=\"选择模型\"]{max-width:104px!important;}"
          + "[data-composer-card] button[aria-label^=\"选择模型\"] span{"
          + "  overflow:hidden!important;text-overflow:ellipsis!important;"
          + "  white-space:nowrap!important;max-width:86px!important;"
          + "}"
            // ⑦ 右侧动作组显式贴住右边缘。
            //    DSH 原本是靠 trailing 里的内容恰好把宽度填满，才"看起来"贴右的 —— 那是巧合。
            //    我把按钮缩小后内容变窄，右边就凭空多出 30~54px 空档，右组不再贴边。
            //    显式 flex-end，内容或宽或窄都稳定贴右。
          + "[data-composer-card] > div:last-child > div:last-child{"
          + "  justify-content:flex-end!important;"
          + "}";

    /**
     * 把 pocket 的布局模式**改回自动**，撤销之前强行钉成 mobile 的副作用。
     *
     * 为什么撤：手机上强行走 pocket 的 mobile 布局后，它会用抽屉式导航替换侧边栏
     * （dsh-pocket/client/mobile/nav-targets.mjs 那套），而那个抽屉在手机上
     * **关不掉、里面还是空的** —— 等于用一个坏导航换掉一个好用的侧边栏，不划算。
     *
     * 而底部统计条的精简修复**不依赖布局模式**（那段 CSS 是无条件生效的），
     * 所以撤掉布局强制不影响它。
     *
     * 实现：localStorage 里没有这个 key 就等于 auto，交给 pocket 自己按 matchMedia 判。
     * 之前被我写进去的 'mobile' 要删掉，删完重载一次让前端重新判定（只重载一次，不循环）。
     */
    private static final String RESET_LAYOUT_JS =
            "(function(){try{"
          + "if(localStorage.getItem('dsh-pocket.layout')===null)return 'OK';"
          + "localStorage.removeItem('dsh-pocket.layout');return 'RELOAD';"
          + "}catch(e){return 'OK';}})()";

    /**
     * 临时诊断条：冷启动后显示 12 秒再自动消失。
     *
     * 前两版都出现「我这边验证通过、你手机上没反应」。这个条子把判断依据直接摆到屏幕上，
     * 装完打开就能看到 —— 不用再来回猜：
     *   W<视口宽>  布局<mobile|desktop>  条<有(9)|无>  精简<✓|✗>
     * 正常应当是：布局 mobile、条 有(9)、精简 ✓。
     */
    private static final String DIAG_JS =
            "(function(){try{"
          + "var old=document.getElementById('dsh-diag');if(old&&old.parentNode)old.parentNode.removeChild(old);"
          + "var d=document.createElement('div');d.id='dsh-diag';"
          + "d.style.cssText='position:fixed;left:0;right:0;top:0;z-index:2147483647;background:#111;"
          + "color:#0f0;font:11px/1.45 monospace;padding:4px 6px;text-align:center;word-break:break-all';"
          + "function has(a){return (document.documentElement.hasAttribute(a)||(document.body&&document.body.hasAttribute(a)))?'\\u6709':'\\u65e0';}"
          + "function upd(){try{"
          + "var bars=document.querySelectorAll('[data-dsh-stats]'),host=null,vis=0;"
          + "for(var i=0;i<bars.length;i++){"
          + "  if(bars[i].hasAttribute('data-dsh-mini'))host=bars[i];"
          + "  if(getComputedStyle(bars[i]).display!=='none')vis++;"
          + "}"
          + "d.textContent='W'+window.innerWidth"
          + "+' \\u5e03:'+(document.body?document.body.getAttribute('data-dsh-pocket-layout'):'-')"
          + "+' aqua:'+has('data-dsh-aqua')+' \\u6d6e:'+has('data-dsh-float')"
          + "+' \\u6761:'+bars.length+' \\u663e:'+vis"
          + "+' \\u95e8:'+(document.documentElement.hasAttribute('data-dsh-mini-ready')?'\\u5f00':'\\u5173')"
          + "+' \\u5bbf\\u4e3b:'+(host?'\\u2713':'\\u2717')"
          + "+' \\u5bbd:'+(host?Math.round(host.getBoundingClientRect().width):'-')"
          + "+' \\u7cbe\\u7b80:'+(host&&host.getAttribute('data-dsh-mini')?'\\u2713':'\\u2717');"
          + "}catch(e){d.textContent='ERR '+e.message;}}"
          + "upd();setInterval(upd,500);"
          + "(document.body||document.documentElement).appendChild(d);"
          + "setTimeout(function(){if(d.parentNode)d.parentNode.removeChild(d);},20000);"
          + "}catch(e){}})()";

    /**
     * 每 500ms 从原始统计节点读出三项数值，拼成精简行写回 `data-dsh-mini`。
     * 只读 DOM、只写一个自定义属性，不动 React 的子节点，所以不会被 React 覆盖。
     */
    private static final String MINI_STATS_JS =
            "(function(){"
          + "if(window.__dshMiniStats)return;window.__dshMiniStats=true;"
          + "function pick(){"
            // 手机上真正的钩子；找不到就退回 aqua 的（桌面端/装了 aqua 时）
          + "  return document.querySelector('[data-mobile-nav=\"stats\"]')"
          + "      || document.querySelector('[data-dsh-stats]:not(.cm-root)');"
          + "}"
          + "function tick(){try{"
          + "  var core=pick();"
          + "  if(!core){document.documentElement.removeAttribute('data-dsh-mini-ready');return;}"
            // 记账行没有属性，用 dock 结构定位；退回全页第一个 .cm-root
          + "  var costEl=document.querySelector('[data-slot=\"conversation.composer.dock\"] > .cm-root')"
          + "           || document.querySelector('.cm-root');"
          + "  var cost='';"
          + "  if(costEl){var ct=costEl.textContent||'',ck=ct.indexOf(' · ');cost=(ck>0?ct.slice(0,ck):ct).trim();}"
          + "  var txt=core.textContent||'';"
          + "  var mTok=txt.match(/[\\d.]+\\s*tok\\/s/);"
          + "  var mCache=txt.match(/缓存命中\\s*[\\d.]+%/);"
          + "  var parts=[];"
          + "  if(mTok)parts.push(mTok[0]);"
          + "  if(mCache)parts.push(mCache[0]);"
          + "  if(cost)parts.push(cost);"
          + "  var out=parts.join(' | ');"
            // 标记只能存在于宿主身上
          + "  var bars=document.querySelectorAll('[data-dsh-mini]');"
          + "  for(var j=0;j<bars.length;j++){if(bars[j]!==core)bars[j].removeAttribute('data-dsh-mini');}"
          + "  if(out){"
          + "    if(core.getAttribute('data-dsh-mini')!==out)core.setAttribute('data-dsh-mini',out);"
          + "    document.documentElement.setAttribute('data-dsh-mini-ready','');"   // 算出结果才开门
          + "  }else{"
          + "    document.documentElement.removeAttribute('data-dsh-mini-ready');"     // 没结果就保持原样
          + "    if(core.hasAttribute('data-dsh-mini'))core.removeAttribute('data-dsh-mini');"
          + "  }"
          + "}catch(e){"
          + "  try{document.documentElement.removeAttribute('data-dsh-mini-ready');}catch(e2){}"  // 异常也退回原样
          + "}}"
          + "setInterval(tick,500);tick();"
          + "})()";

    private WebView web;
    private TextView overlayText;
    private LinearLayout overlay;
    private Button retryButton;
    private Button scanButton;
    private FrameLayout rootView;
    private QrScanner scanner;
    private SharedPreferences prefs;
    private ExecutorService pool;
    /** 每次重新连接自增，用来丢弃过期请求的回调。 */
    private volatile int generation = 0;
    /** 这一轮连接是否已经自动重试过（网络刚切换时补一次，避免瞬时 DNS 失败就报错）。 */
    private final java.util.concurrent.atomic.AtomicBoolean retried = new java.util.concurrent.atomic.AtomicBoolean(false);
    /** 主线程 Handler：只用于「延迟重试」这一处。 */
    private final android.os.Handler ui = new android.os.Handler(android.os.Looper.getMainLooper());

    // ---------------------------------------------------------------- 生命周期

    /**
     * 全面屏（边到边）—— 只做「让内容铺满整屏」这一件事，配色全交给主题。
     *
     * 不这么做的时候，内容从状态栏下面才开始，状态栏露出系统的深色底（主题里原本写死
     * statusBarColor=#FF101418），而 DSH 页面是纯白的 —— 顶部一条黑带、下面全白。
     * 实测：状态栏区域 #101418、正文 #FFFFFF。
     *
     * 状态栏透明 + 深色图标都写在 `res/values/styles.xml` 的 AppTheme 里，
     * 比运行时调 WindowInsetsController 可靠：后者在 setContentView 之前调用时
     * decor view 还没创建，getInsetsController() 返回 null，会 NPE 直接闪退（踩过）。
     */
    private void applyEdgeToEdge() {
        Window w = getWindow();
        if (Build.VERSION.SDK_INT >= 30) {
            w.setDecorFitsSystemWindows(false);
        } else {
            w.getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                  | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        applyEdgeToEdge();
        prefs = getSharedPreferences(PREF, MODE_PRIVATE);
        pool = Executors.newCachedThreadPool();
        buildUi();
        handleBindIntent(getIntent());   // 冷启动也可能是扫码拉起来的
        connect();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        // App 已经开着时扫码：重新绑定并立刻连接过去
        if (handleBindIntent(intent)) connect();
    }

    /**
     * 处理扫码绑定。
     *
     * @return true 表示这次 intent 带了一个有效的绑定码，并且已经存下来了。
     */
    private boolean handleBindIntent(Intent intent) {
        if (intent == null) return false;
        Uri data = intent.getData();
        if (data == null) return false;
        return applyBindUri(data);
    }

    /** 相机扫到二维码：是绑定码就绑上并连过去，不是就提示重扫。 */
    private void onQrScanned(String text) {
        String t = text == null ? "" : text.trim();
        if (!applyBindUri(Uri.parse(t))) {
            showOverlay("这个码不是 DSH 的绑定码。\n\n请扫电脑上「设置 → 手机访问」里显示的那个二维码。", true);
            return;
        }
        connect();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // DSH 重启后 pocket 会换新的进程级 sessionKey（index.js 里 randomBytes(16)），
        // 已种下的登录 cookie 随之失效。若此时 App 正开着，就会停在登录页或错误页上。
        // 回到前台时探一下：一旦发现是登录页/中转页，就重走一遍带 token 的连接。
        if (web == null || scanner.isShowing()) return;
        web.evaluateJavascript(
                "(function(){try{"
              + "var h=document.documentElement.innerHTML;"
              + "var login=h.indexOf('name=\"token\"')>=0 && h.indexOf('method=\"post\"')>=0;"
              + "var t=document.body?document.body.innerText:'';"
              + "var opening=t.length<200 && t.indexOf('正在进入')>=0;"
              + "return login||opening;"
              + "}catch(e){return false}})()",
                new ValueCallback<String>() {
                    @Override
                    public void onReceiveValue(String value) {
                        if ("true".equals(value)) connect();
                    }
                });
    }

    @Override
    protected void onPause() {
        super.onPause();
        // 退到后台就把摄像头放掉，别占着资源
        if (scanner != null) scanner.hide();
    }

    @Override
    protected void onDestroy() {
        if (scanner != null) scanner.dispose();
        if (pool != null) pool.shutdownNow();
        super.onDestroy();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK && scanner != null && scanner.isShowing()) {
            scanner.hide();
            if (!isBound()) requestScan();   // 还没绑定时，关掉也要能再扫
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_BACK && web != null && web.canGoBack()) {
            web.goBack();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    // ---------------------------------------------------------------- 界面

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);

        web = new WebView(this);
        web.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        web.setBackgroundColor(Color.WHITE);

        // 打开 WebView 远程调试：USB 连着电脑时可以用 chrome://inspect 或 CDP
        // 直接看 WebView 里的真实 DOM / 控制台。之前几轮排查全在盲改，
        // 就是因为没有这个口子。个人专用 App，开着没有副作用。
        WebView.setWebContentsDebuggingEnabled(true);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setSupportZoom(false);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(web, true);

        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView v, String url) {
                injectMobileCss(v);
                resetLayoutPin(v);
                hideOverlay();
            }

            @Override
            public void onReceivedError(WebView v, WebResourceRequest req, WebResourceError err) {
                if (req != null && req.isForMainFrame()) {
                    showOverlay("连接失败：" + err.getDescription(), true);
                }
            }
        });

        root.addView(web);

        overlay = new LinearLayout(this);
        overlay.setOrientation(LinearLayout.VERTICAL);
        overlay.setGravity(Gravity.CENTER);
        overlay.setBackgroundColor(0xFF101418);
        overlay.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        overlayText = new TextView(this);
        overlayText.setTextColor(Color.WHITE);
        overlayText.setTextSize(16);
        overlayText.setGravity(Gravity.CENTER);
        overlayText.setPadding(dp(32), 0, dp(32), 0);
        overlay.addView(overlayText);

        retryButton = new Button(this);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bp.topMargin = dp(20);
        retryButton.setLayoutParams(bp);
        retryButton.setText("重新连接");
        retryButton.setVisibility(View.GONE);
        retryButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                connect();
            }
        });
        overlay.addView(retryButton);

        // 「扫一扫」：换绑到另一台 DSH（也用于绑定失败后重新扫）
        scanButton = new Button(this);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sp.topMargin = dp(12);
        scanButton.setLayoutParams(sp);
        scanButton.setText("扫一扫");
        scanButton.setVisibility(View.GONE);
        scanButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                requestScan();
            }
        });
        overlay.addView(scanButton);

        root.addView(overlay);

        // 扫码取景层：盖在最上面，默认隐藏
        scanner = new QrScanner(this, new QrScanner.OnResult() {
            @Override
            public void onQr(String text) {
                onQrScanned(text);
            }
        });
        // 相册这条路：二维码躺在截图/另一台设备里、没法拿摄像头对着扫时用
        scanner.setOnPickImage(new QrScanner.OnPickImage() {
            @Override
            public void onPickImage() {
                pickQrFromGallery();
            }
        });
        scanner.attach(root);

        rootView = root;
        setContentView(root);
    }

    private void showOverlay(final String text, final boolean withRetry) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                overlayText.setText(text);
                retryButton.setVisibility(withRetry ? View.VISIBLE : View.GONE);
                // 出错时一并给出「扫一扫」：换一台 DSH、或密码改了要重扫，都走这里
                scanButton.setVisibility(withRetry ? View.VISIBLE : View.GONE);
                overlay.setVisibility(View.VISIBLE);
                overlay.bringToFront();
            }
        });
    }

    private void hideOverlay() {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                overlay.setVisibility(View.GONE);
            }
        });
    }

    // ---------------------------------------------------------------- 连接决策

    /** 一个候选入口：地址 + 探测超时 + 是否局域网。 */
    private static final class Candidate {
        final String url;
        final int timeout;
        final boolean lan;

        Candidate(String url, int timeout, boolean lan) {
            this.url = url;
            this.timeout = timeout;
            this.lan = lan;
        }
    }

    // ---------------------------------------------------------------- 扫码绑定
    //
    // 一个 App 服务多个 DSH：谁扫谁的码，就绑到谁的实例上。
    // 绑定码由电脑端「设置 → 手机访问」面板生成（也可用 tools/make-bind-qr.mjs），
    // 内容是一个普通网址加一串参数：
    //   https://<公网域名>/?dshbind=1&li=<局域网IP>&lport=<端口>&lp=<局域网密码>&pp=<公网密码>
    // 一个码两用：手机浏览器扫 = 正常打开网页（要输密码）；
    //            DSH App 扫   = 读出参数直接绑定（免密码）。
    // App 拿到两套地址后自己判断走哪条 —— 同 Wi-Fi 走局域网（快），在外面走公网（哪都能用）。

    private static final String K_NAME  = "bind_name";
    private static final String K_LIP   = "bind_lan_ip";
    private static final String K_LPORT = "bind_lan_port";
    private static final String K_LPIN  = "bind_lan_pin";
    private static final String K_PHOST = "bind_pub_host";
    private static final String K_PPIN  = "bind_pub_pin";

    private static boolean notEmpty(String s) {
        return s != null && s.length() > 0;
    }

    /** 是否已经绑定过某个 DSH。 */
    private boolean isBound() {
        return notEmpty(prefs.getString(K_PPIN, null)) || notEmpty(prefs.getString(K_LPIN, null));
    }

    /**
     * 解析并保存绑定码。两种形式都认：
     *
     * <p>① 电脑端面板生成的**普通网址**（主用）：
     * {@code https://dsh.kaelorvyn.cn/?dshbind=1&li=..&lport=..&lp=..&pp=..}
     * 同一个码两用 —— 手机浏览器扫它就是老老实实打开网页（要输密码）；
     * DSH App 扫它则读出参数直接绑定，免密码。
     *
     * <p>② 旧的 {@code dsh://bind?...} 自定义 scheme（保留兼容）。
     *
     * @return true 表示解析成功且已保存；false 表示这不像一个有效的绑定码。
     */
    private boolean applyBindUri(Uri uri) {
        if (uri == null) return false;
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
        boolean legacy = "dsh".equals(scheme) && "bind".equals(uri.getHost());
        boolean web = ("http".equals(scheme) || "https".equals(scheme))
                && uri.getQueryParameter("dshbind") != null;
        if (!legacy && !web) return false;

        String n     = uri.getQueryParameter("n");
        String li    = uri.getQueryParameter("li");
        String lp    = uri.getQueryParameter("lp");
        String lport = uri.getQueryParameter("lport");
        String ph    = uri.getQueryParameter("ph");
        String pp    = uri.getQueryParameter("pp");

        if (web) {
            // 网址本身就是入口：私网地址当局域网用，公网域名当公网用
            String host = uri.getHost() == null ? "" : uri.getHost();
            if (isPrivateIpv4(host)) {
                if (!notEmpty(li)) {
                    li = host;
                    lport = uri.getPort() > 0 ? String.valueOf(uri.getPort()) : null;
                }
            } else if (!notEmpty(ph)) {
                ph = scheme + "://" + uri.getAuthority();
            }
        }

        boolean hasLan = notEmpty(li) && notEmpty(lp);
        boolean hasPub = notEmpty(ph) && notEmpty(pp);
        if (!hasLan && !hasPub) return false;

        SharedPreferences.Editor e = prefs.edit();
        e.putString(K_NAME, n == null ? "" : n);
        if (hasLan) {
            e.putString(K_LIP, li);
            e.putString(K_LPIN, lp);
            e.putString(K_LPORT, notEmpty(lport) ? lport : String.valueOf(LAN_PORT));
        }
        if (hasPub) {
            e.putString(K_PHOST, ph.replaceAll("/+$", ""));
            e.putString(K_PPIN, pp);
        }
        // 换了绑定，之前记住的局域网地址就作废了
        e.remove(KEY_LAN_HOST);
        e.apply();
        return true;
    }

    /** 192.168.x / 10.x / 172.16-31.x 视为局域网地址。 */
    private static boolean isPrivateIpv4(String host) {
        if (host == null) return false;
        String[] p = host.split("\\.");
        if (p.length != 4) return false;
        try {
            int a = Integer.parseInt(p[0]);
            int b = Integer.parseInt(p[1]);
            if (a == 10) return true;
            if (a == 192 && b == 168) return true;
            if (a == 172 && b >= 16 && b <= 31) return true;
        } catch (NumberFormatException ignored) {
            return false;
        }
        return false;
    }

    // ---------------------------------------------------------------- 诊断
    //
    // 连不上时唯一能看清「到底哪一步断的」的办法：把绑定内容和每个候选的探测结果
    // 打到 logcat。**只打长度和状态，绝不打密码本身** —— logcat 别的 App 读不到，
    // 但日志会被截图、被复制，密码不该进去。

    private static final String TAG = "DSH";

    /** 只暴露结构，不暴露密码：`lan=192.168.1.3:3081(8位) pub=https://x(8位)`。 */
    private String describeBinding() {
        String lip = prefs.getString(K_LIP, null);
        String lport = prefs.getString(K_LPORT, null);
        String lpin = prefs.getString(K_LPIN, null);
        String phost = prefs.getString(K_PHOST, null);
        String ppin = prefs.getString(K_PPIN, null);
        return "lan=" + (lip == null ? "无" : lip + ":" + lport)
                + "(" + (lpin == null ? 0 : lpin.length()) + "位)"
                + " pub=" + (phost == null ? "无" : phost)
                + "(" + (ppin == null ? 0 : ppin.length()) + "位)"
                + " 本机=" + wifiIpv4();
    }

    /** 把 URL 里的 token/邀请码换成 ***，其余的留着好排查。 */
    private static String mask(String url) {
        if (url == null) return "null";
        return url.replaceAll("(token|pp|lp)=[^&]*", "$1=***");
    }

    /** 调起扫码界面（还没绑定时，或用户主动要换绑一台 DSH）。 */
    private void requestScan() {
        hideOverlay();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
            return;
        }
        scanner.show();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] granted) {
        if (code != REQ_CAMERA) {
            super.onRequestPermissionsResult(code, perms, granted);
            return;
        }
        if (granted.length > 0 && granted[0] == PackageManager.PERMISSION_GRANTED) {
            scanner.show();
        } else {
            showOverlay("扫码需要相机权限。\n\n请到 系统设置 → 应用 → DSH → 权限\n里打开「相机」，或者点下面用相册里的二维码图片。", true);
        }
    }

    // ------------------------------------------------------------ 从相册选二维码
    //
    // 有时候二维码根本没法拿摄像头扫：它在聊天记录里、在截图里、在另一台设备上。
    // 所以扫码界面除了摄像头，还留一条「从相册选择」的路。
    // 用系统相册 App 选图（ACTION_GET_CONTENT），因此**不需要读存储权限** ——
    // 相册把那一张图的读取权临时授给我们，选完就失效。

    private void pickQrFromGallery() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("image/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        try {
            startActivityForResult(Intent.createChooser(intent, "选择二维码图片"), REQ_PICK);
        } catch (Throwable t) {
            showOverlay("打不开相册：" + t.getMessage() + "\n\n可以直接用摄像头扫。", true);
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_PICK) return;

        // 用户在相册里点了返回：回到取景框，让他继续用摄像头
        if (res != RESULT_OK || data == null || data.getData() == null) {
            if (scanner != null && !isBound()) scanner.show();
            return;
        }

        final Uri uri = data.getData();
        showOverlay("正在识别图片里的二维码…", false);
        new Thread(new Runnable() {
            @Override
            public void run() {
                final String text = decodeQrFromUri(uri);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (text == null) {
                            showOverlay("这张图里没认出二维码。\n\n可能是图太糊、码太小，或者图里没有码。\n换一张试试，也可以直接用摄像头扫。", true);
                        } else {
                            onQrScanned(text);
                        }
                    }
                });
            }
        }).start();
    }

    /**
     * 从相册图片里解二维码。返回 null 表示这张图里没有可识别的码。
     *
     * <p>两个关键点：① 先按 1400px 上限降采样 —— 手机原图动辄 5000px，
     * 整张读进内存既慢又可能 OOM，而降采样不会影响二维码识别；
     * ② 开 TRY_HARDER，照片里的码往往不是正对镜头的，值得多花点时间。
     */
    private String decodeQrFromUri(Uri uri) {
        try {
            // 先只读尺寸，算降采样倍数
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            java.io.InputStream in = getContentResolver().openInputStream(uri);
            BitmapFactory.decodeStream(in, null, bounds);
            if (in != null) in.close();
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;

            int sample = 1;
            while (bounds.outWidth / sample > 1400 || bounds.outHeight / sample > 1400) sample *= 2;

            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
            in = getContentResolver().openInputStream(uri);
            Bitmap bmp = BitmapFactory.decodeStream(in, null, opts);
            if (in != null) in.close();
            if (bmp == null) return null;

            int w = bmp.getWidth();
            int h = bmp.getHeight();
            int[] pixels = new int[w * h];
            bmp.getPixels(pixels, 0, w, 0, 0, w, h);
            bmp.recycle();

            java.util.Map<com.google.zxing.DecodeHintType, Object> hints = new java.util.HashMap<>();
            hints.put(com.google.zxing.DecodeHintType.TRY_HARDER, Boolean.TRUE);
            com.google.zxing.Result r = new com.google.zxing.qrcode.QRCodeReader().decode(
                    new com.google.zxing.BinaryBitmap(new com.google.zxing.common.HybridBinarizer(
                            new com.google.zxing.RGBLuminanceSource(w, h, pixels))),
                    hints);
            return r == null ? null : r.getText();
        } catch (Throwable t) {
            return null;
        }
    }

    private void connect() {
        retried.set(false);   // 用户发起的连接：重试额度重置
        connectInternal();
    }

    /**
     * 真正的连接流程。与 {@link #connect()} 分开，是因为自动重试要复用同一条路径，
     * 但不能把「已重试过」这个标记重置掉（否则会无限重试）。
     */
    private void connectInternal() {
        if (!isBound()) {
            Log.i(TAG, "connect: 未绑定 -> 出扫码界面");
            requestScan();
            return;
        }
        final int gen = ++generation;
        showOverlay("正在连接…", false);

        final List<Candidate> candidates = buildCandidates();
        int candidateCount = candidates.size();
        Log.i(TAG, "connect: binding=" + describeBinding() + " candidates=" + candidateCount);
        for (Candidate c : candidates) Log.i(TAG, "  candidate: " + mask(c.url));
        final CompletionService<Candidate> cs = new ExecutorCompletionService<>(pool);
        final List<Future<Candidate>> futures = new ArrayList<>();
        // 每个候选的结果，失败时直接显示到屏幕上（手机连不上时用户看不到日志）
        final List<String> diag = java.util.Collections.synchronizedList(new ArrayList<String>());

        for (final Candidate c : candidates) {
            futures.add(cs.submit(new Callable<Candidate>() {
                @Override
                public Candidate call() {
                    String r = probeVerbose(c);
                    diag.add(mask(c.url) + "  →  " + r);
                    return r.startsWith("OK") ? c : null;
                }
            }));
        }

        pool.execute(new Runnable() {
            @Override
            public void run() {
                Candidate winner = null;
                for (int i = 0; i < futures.size() && winner == null; i++) {
                    try {
                        Future<Candidate> f = cs.take();
                        winner = f.get();
                    } catch (Exception ignored) {
                        // 单个候选探测异常不影响其它候选
                    }
                }
                if (gen != generation) return; // 已被更新的连接取代

                if (winner == null) {
                    // 网络刚切换时（WiFi 断掉改走流量）第一次探测常常因为 DNS 还没就绪
                    // 而整片失败，用户看到的就是「扫了码也连不上」。所以：
                    // 只有在**全是网络类失败**时自动重试一次 —— 如果是「密码不对」就别
                    // 重试了，那是凭证问题，重试多少次都一样，只会让人多等。
                    boolean authFailed = false;
                    synchronized (diag) {
                        for (String d : diag) if (d.endsWith("密码不对")) authFailed = true;
                    }
                    if (!authFailed && !retried.getAndSet(true)) {
                        ui.postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                if (gen == generation) connectInternal();
                            }
                        }, 1500);
                        return;
                    }

                    StringBuilder sb = new StringBuilder("连不上 DSH。\n\n试过的入口：\n");
                    synchronized (diag) {
                        for (String d : diag) sb.append("· ").append(d).append('\n');
                    }
                    sb.append("\n本机 ").append(wifiIpv4()).append(" · 绑定 ").append(describeBinding());
                    showOverlay(sb.toString(), true);
                    return;
                }
                if (winner.lan) {
                    prefs.edit().putString(KEY_LAN_HOST, hostOf(winner.url)).apply();
                }
                loadInWebView(winner.url);
            }
        });
    }

    /** 组装候选：记住的局域网地址 → 按当前网段推算的局域网地址 → 公网。全部来自扫码绑定。 */
    private List<Candidate> buildCandidates() {
        Set<String> urls = new LinkedHashSet<>();
        List<Candidate> out = new ArrayList<>();

        // 告诉电脑端「是哪台手机」：pocket 会把它记在对应的邀请码上，
        // 于是电脑的设置页能列出「PJH110 · 最近 09-11 22:30」，删除时也认得出是谁。
        String dn = "&dn=" + Uri.encode(Build.MODEL == null ? "Android" : Build.MODEL);

        String lanPin   = prefs.getString(K_LPIN, null);
        String lanPort  = prefs.getString(K_LPORT, String.valueOf(LAN_PORT));
        String pubPin   = prefs.getString(K_PPIN, null);
        String pubHost  = prefs.getString(K_PHOST, null);
        String bindIp   = prefs.getString(K_LIP, null);
        String remembered = prefs.getString(KEY_LAN_HOST, null);

        // ① 上次成功过的局域网地址（换了 Wi-Fi 也能先试一把）
        if (notEmpty(lanPin) && notEmpty(remembered)) {
            addLan(out, urls, remembered, lanPin, lanPort, dn);
        }

        // ② 绑定码里那台电脑的地址：**无条件要试**。
        // 早先写成「只有和手机同网段才试」(bindIp.startsWith(prefix))，是错的：
        // 手机在 192.168.0.x、电脑在 192.168.1.x（路由器打通了照样能到）时就
        // 把真地址整条跳过，只剩一个按手机网段瞎猜的地址，必然超时 ——
        // 屏幕上会看到「去连 192.168.0.3 超时」，可电脑其实在 192.168.1.3。
        // 网段不同不代表不通，能不能通由探测决定，不该由代码提前判死刑。
        if (notEmpty(lanPin) && notEmpty(bindIp)) {
            addLan(out, urls, bindIp, lanPin, lanPort, dn);
        }

        // ③ 再补一条「按当前网段推算」的地址，应付电脑换了 IP 的情况
        if (notEmpty(lanPin)) {
            String myIp = wifiIpv4();
            if (myIp != null && myIp.indexOf('.') > 0) {
                String prefix = myIp.substring(0, myIp.lastIndexOf('.') + 1);
                // 用绑定码里那台的尾号；没有就用记过的；再没有就用默认猜测
                String octet = DEFAULT_LAST_OCTET;
                if (notEmpty(bindIp) && bindIp.indexOf('.') >= 0) {
                    octet = bindIp.substring(bindIp.lastIndexOf('.') + 1);
                } else if (notEmpty(remembered) && remembered.indexOf('.') >= 0) {
                    octet = remembered.substring(remembered.lastIndexOf('.') + 1);
                }
                addLan(out, urls, prefix + octet, lanPin, lanPort, dn);
            }
        }

        // ③ 公网兜底
        if (notEmpty(pubHost) && notEmpty(pubPin)) {
            out.add(new Candidate(pubHost + "/?token=" + pubPin + dn, PUBLIC_TIMEOUT_MS, false));
        }
        return out;
    }

    private void addLan(List<Candidate> out, Set<String> seen, String host, String pin, String port, String dn) {
        String url = "http://" + host + ":" + port + "/?token=" + pin + dn;
        if (seen.add(url)) out.add(new Candidate(url, LAN_TIMEOUT_MS, true));
    }

    /** 探测入口是否可用；顺手把 pocket 种下的认证 cookie 塞进 WebView 的 cookie 罐。 */
    private boolean probe(Candidate c) {
        return probeVerbose(c).startsWith("OK");
    }

    /**
     * 探测并把结果说成人话。返回值既用于判断成败，也直接显示在连接失败页上 ——
     * 手机连不上时用户没法看日志，屏幕上说清楚是「DNS 解析不了」还是「密码不对」
     * 还是「端口不通」，比一句「连不上」有用得多。
     */
    private String probeVerbose(Candidate c) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(c.url).openConnection();
            conn.setConnectTimeout(c.timeout);
            conn.setReadTimeout(c.timeout);
            conn.setInstanceFollowRedirects(false);
            conn.setRequestProperty("User-Agent", "DSH-Android");

            int code = conn.getResponseCode();
            if (code < 200 || code >= 400) return "HTTP " + code;
            grabCookies(conn, c.url);
            if (code == 200) return looksLikeLoginPage(conn) ? "密码不对" : "OK";
            return "OK";
        } catch (java.net.SocketTimeoutException e) {
            return "超时（网络不通）";
        } catch (java.net.UnknownHostException e) {
            return "域名解析不了";
        } catch (java.net.ConnectException e) {
            return "端口连不上";
        } catch (javax.net.ssl.SSLException e) {
            return "HTTPS 握手失败";
        } catch (Exception e) {
            return e.getClass().getSimpleName();
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private boolean looksLikeLoginPage(HttpURLConnection conn) {
        BufferedReader r = null;
        try {
            r = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            char[] buf = new char[1024];
            int n;
            while (sb.length() < 4096 && (n = r.read(buf)) > 0) sb.append(buf, 0, n);
            String body = sb.toString();
            return body.contains("name=\"token\"") && body.contains("method=\"post\"");
        } catch (Exception e) {
            return false;
        } finally {
            if (r != null) try { r.close(); } catch (Exception ignored) { }
        }
    }

    private void grabCookies(HttpURLConnection conn, String url) {
        try {
            List<String> all = conn.getHeaderFields().get("Set-Cookie");
            if (all == null) return;
            CookieManager cm = CookieManager.getInstance();
            for (String c : all) {
                // 只搬 pocket 自己的认证 cookie。
                // dsh web 的会话 cookie（dsh-auth-*）故意不搬：pocket 专门为「扫码进来的
                // 浏览器」写了补丁——首次 GET / 且没有该 cookie 时自动补一次 launch token。
                // 我们替它搬过去反而会让补丁以为会话已建立，跳过握手。
                if (c.startsWith(COOKIE_POCKET_TOKEN)) {
                    cm.setCookie(url, c);
                }
            }
            cm.flush();
        } catch (Exception ignored) {
        }
    }

    private void loadInWebView(final String url) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                showOverlay("正在加载…", false);
                // 同一个地址再次进入时强制热一次，避免 WebView 拿旧缓存不刷新
                web.clearHistory();
                web.loadUrl(url);
            }
        });
    }

    // ---------------------------------------------------------------- 窄屏样式

    /**
     * 往页面注入一次窄屏样式。<style> 只插一次（按 id 去重），
     * DSH 是单页应用、切会话不重新加载文档，所以插一次就够；真的整页刷新时
     * onPageFinished 会再调一次，同样被 id 挡住。
     */
    private void injectMobileCss(WebView v) {
        try {
            String js = "(function(){"
                    + "if(!document.getElementById('dsh-app-mobile-css')){"
                    + "var s=document.createElement('style');"
                    + "s.id='dsh-app-mobile-css';"
                    + "s.textContent=" + JSONObject.quote(MOBILE_CSS) + ";"
                    + "(document.head||document.documentElement).appendChild(s);}"
                    + MINI_STATS_JS          // 自带 window 级去重，重复调用不会叠定时器
                    + "})()";
            v.evaluateJavascript(js, null);
        } catch (Exception ignored) {
        }
    }

    /**
     * 撤销布局强制。只在本次冷启动里做一次重载 —— 删掉 localStorage 的 key 后
     * 前端要重新判定才会恢复 auto。重载后已是 null，直接返回 OK，不会循环。
     */
    private void resetLayoutPin(WebView v) {
        if (layoutPinned) return;
        layoutPinned = true;
        try {
            v.evaluateJavascript(RESET_LAYOUT_JS, new ValueCallback<String>() {
                @Override
                public void onReceiveValue(String value) {
                    if (value != null && value.indexOf("RELOAD") >= 0) {
                        web.reload();
                    }
                }
            });
        } catch (Exception ignored) {
        }
    }

    // ---------------------------------------------------------------- 工具

    /** BuildConfig 里存的是「每个字符异或 0x5A 后的十六进制」。 */
    static String unscramble(String hex) {
        if (hex == null || hex.length() % 2 != 0) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < hex.length(); i += 2) {
            sb.append((char) (Integer.parseInt(hex.substring(i, i + 2), 16) ^ 0x5A));
        }
        return sb.toString();
    }

    private static String hostOf(String url) {
        try {
            return new URL(url).getHost();
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 手机当前 Wi-Fi 的 IPv4 地址。
     * 只认 wlan/eth 开头的网卡，绕开 Hyper-V（172.x 虚拟网卡）、VPN、热点等干扰网段。
     */
    private String wifiIpv4() {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                String name = ni.getName().toLowerCase();
                if (!name.startsWith("wlan") && !name.startsWith("eth")) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (!(a instanceof Inet4Address) || a.isLoopbackAddress()) continue;
                    String ip = a.getHostAddress();
                    if (!isPrivate(ip)) continue;
                    return ip;
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static boolean isPrivate(String ip) {
        if (ip.startsWith("192.168.") || ip.startsWith("10.")) return true;
        if (ip.startsWith("172.")) {
            try {
                int second = Integer.parseInt(ip.split("\\.")[1]);
                return second >= 16 && second <= 31;
            } catch (Exception ignored) {
            }
        }
        return false;
    }
}
