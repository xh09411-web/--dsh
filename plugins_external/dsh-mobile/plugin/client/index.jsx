// dsh-pocket 网页客户端：
//   1. 设置页签「手机访问」（局域网/公网二维码 + 更新/重启提示）
//   2. 移动端适配（移植自 MIT 项目 dsh-web-mobile，见 client/mobile/LICENSE.dsh-web-mobile）
//
// 手机扫码打开的就是电脑上的 dsh web，实时同步；窄屏自动变成抽屉布局。
//
// 注：Web Push 已移除——浏览器推送依赖 Google FCM（Chrome）等境外服务，
// 国内直连被墙，普通用户用不了。专注扫码同屏这一件事。

import { createElement as h, useEffect, useRef, useState } from 'react';
import QRCode from 'qrcode';

import { POCKET_RPC_CHANNEL, POCKET_ENDPOINTS, redactStatus, compareVersions } from './api.js';
import { mobileApply } from './mobile/mobile-apply.tsx';
import { NS as POCKET_NS, zh as POCKET_ZH, en as POCKET_EN } from './pocket-locales.js';

const name = 'dsh-pocket';
const inject = ['slots', 'connection', 'layout', 'locale', 'sessionLogDownload'];

// 词典在 pocket-locales.js；这里只做「取 key → 替换 {占位符} → 字符串」。
// 不依赖 DSH t() 的插值能力，避免行为不一致。
function fmt(t, key, vars) {
  let s = t(key);
  if (vars) {
    for (const [k, v] of Object.entries(vars)) {
      s = String(s).split(`{${k}}`).join(String(v));
    }
  }
  return s;
}

// 官方 DeepSeek Harness 设计系统（dsh-client-ui-theme design-platform.css）：
// 按钮 md=36px 胶囊形 / sm=28px；品牌色 --dsw-alias-brand-primary；
// hover 走 --dsw-alias-button-*-hover；间距 4px 栅格；正文 13px。
const styles = {
  card: { background: 'var(--dsw-alias-bg-layer-1,#fff)', border: '1px solid var(--dsw-alias-border-l2,#e5e7eb)', borderRadius: 12, padding: '16px 20px', maxWidth: 480 },
  block: { borderTop: '1px solid var(--dsw-alias-border-l2,#e5e7eb)', marginTop: 16, paddingTop: 16 },
  muted: { color: 'var(--dsw-alias-label-tertiary,#8b93a1)', fontSize: 12, lineHeight: 1.5 },
  code: { fontFamily: 'ui-monospace,Menlo,monospace', fontSize: 12, wordBreak: 'break-all', margin: '6px 0 10px', color: 'var(--dsw-alias-label-primary,inherit)' },
  // 主按钮：官方 md 胶囊形（36px）
  primary: { font: 'inherit', cursor: 'pointer', border: 'none', background: 'var(--dsw-alias-button-primary-fill, var(--dsw-alias-brand-primary,#4f6ef7))', color: 'var(--dsw-alias-label-primary-foreground, #fff)', height: 36, padding: '0 16px', borderRadius: 999, fontSize: 13, fontWeight: 500, display: 'inline-flex', alignItems: 'center', justifyContent: 'center' },
  // 次级按钮：官方 outline/ghost 胶囊形
  btn: { font: 'inherit', cursor: 'pointer', border: '1px solid var(--dsw-alias-button-ghost-active-border, var(--dsw-alias-border-l2,#d1d5db))', background: 'var(--dsw-alias-bg-layer-1,#fff)', color: 'var(--dsw-alias-label-primary,inherit)', height: 36, padding: '0 16px', borderRadius: 999, fontSize: 13, display: 'inline-flex', alignItems: 'center', justifyContent: 'center' },
  qr: { width: 220, height: 220, borderRadius: 10, border: '1px solid var(--dsw-alias-border-l2,#e5e7eb)', margin: '8px 0' },
  warn: { color: 'var(--dsw-alias-state-warn-primary,#b45309)', fontSize: 12, lineHeight: 1.5 },
};

function PocketSettingsTab({ rpcCall, t }) {
  const [status, setStatus] = useState(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(null);
  const [tunnelState, setTunnelState] = useState(null); // 隧道进度 {phase, detail, startedAt}
  const [restartNotice, setRestartNotice] = useState(false); // 重启后提示
  const [updateInfo, setUpdateInfo] = useState(null); // { current, latest, updating, result, startedAt } | null
  const [isDesktop, setIsDesktop] = useState(false); // DSH Desktop（Electron）环境：更新/重启由桌面版管理
  const [now, setNow] = useState(Date.now()); // 每秒 tick，驱动倒计时

  // 进行中操作的「已等待 X 秒」倒计时
  useEffect(() => {
    const t = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(t);
  }, []);
  const elapsed = (startedAt) => (startedAt ? Math.max(0, Math.floor((Date.now() - startedAt) / 1000)) : 0);

  const call = async (endpoint, payload) => {
    const res = await rpcCall(endpoint, payload);
    if (!res?.ok) throw new Error(res?.error?.message ?? 'RPC failed');
    return res.value;
  };

  const load = async () => {
    try {
      const s = await call(POCKET_ENDPOINTS.status, {});
      setStatus(s);
      setTunnelState(s.tunnelState ?? null);
      if (s.desktop) setIsDesktop(true);
      if (s.restartNotice) {
        // 新进程确认起来了：显示一次「已重启」，清掉旧的更新横幅（单状态，不并存），
        // 然后自动刷新页面加载新代码——不用用户手动刷新
        setRestartNotice(true);
        setUpdateInfo(null);
        if (!sessionStorage.getItem('dshp-auto-reloaded')) {
          sessionStorage.setItem('dshp-auto-reloaded', '1');
          setTimeout(() => { try { location.reload(); } catch { /* 忽略 */ } }, 2000);
        }
      }
    } catch { /* 忽略瞬时失败 */ }
  };

  useEffect(() => {
    load();
    const t = setInterval(load, 3000);
    return () => clearInterval(t);
  }, []);

  // 每次页面加载清掉自动刷新标记——这样下次重启（更新后）才能再次触发自动刷新
  useEffect(() => {
    try { sessionStorage.removeItem('dshp-auto-reloaded'); } catch { /* 忽略 */ }
  }, []);

  // 版本检测：host 当前版本 vs npm registry latest（registry 带 CORS *）
  // 两种情况显示横幅：① 有新版可更新；② 磁盘已更新但进程还是旧代码（重启生效）
  // cache: 'no-store' —— registry 响应带缓存头，浏览器会缓存旧版本号导致「小版本不提示」
  // 周期重查（每 5 分钟）：npm registry 的 /latest 走 CDN 边缘缓存，刚发布后打开页面
  // 可能拿到旧版本号——周期性重查让更新提示在缓存刷新后自动出现，不用重开页面。
  // 桌面端（isDesktop）：更新/重启由 DSH Desktop 管理，这里不做版本检测、不显示更新横幅
  useEffect(() => {
    if (isDesktop) return;
    let alive = true;
    const check = async () => {
      try {
        const v = await call(POCKET_ENDPOINTS.version, {});
        const meta = await (await fetch('https://registry.npmjs.org/dsh-pocket/latest', { cache: 'no-store' })).json();
        if (!alive) return;
        const latest = typeof meta?.version === 'string' ? meta.version : null;
        if (latest && v.current && compareVersions(latest, v.current) > 0) {
          setUpdateInfo({ current: v.current, latest, updating: false, result: null });
        } else if (v.current && v.loaded && compareVersions(v.current, v.loaded) > 0) {
          // 已更新未重启：显示「已更新，重启生效」+ 重启按钮
          setUpdateInfo({ current: v.current, latest: v.current, updating: false, result: 'ok', updated: true });
        }
      } catch { /* 网络失败静默 */ }
    };
    check();
    const t = setInterval(check, 5 * 60 * 1000);
    return () => { alive = false; clearInterval(t); };
  }, [isDesktop]);

  // 重启宿主（更新生效必需：刷新页面不会重载服务端代码）
  const restartPocket = async () => {
    setUpdateInfo((u) => ({ ...u, restarting: true, startedAt: Date.now() }));
    try {
      // 宿主 500ms 后自杀，RPC 响应可能来不及送达 → 3 秒超时兜底，别让按钮永远卡「重启中…」
      await Promise.race([
        call(POCKET_ENDPOINTS.restart, {}),
        new Promise((_, rej) => setTimeout(() => rej(new Error('restart requested (no reply within 3s)')), 3000)),
      ]);
      setUpdateInfo((u) => ({ ...u, restarting: true, result: 'ok' }));
    } catch (err) {
      // 网络断连/超时同样视为「已请求重启」——旧进程即将退出，等新进程起来后刷新即可
      const msg = String(err?.message ?? '');
      if (/connection|socket|fetch|network|abort|cancelled|ECONN|disconnect|closed|timeout/i.test(msg)) {
        setUpdateInfo((u) => ({ ...u, restarting: true, result: 'ok' }));
        return;
      }
      setUpdateInfo((u) => ({ ...u, restarting: false, result: 'fail', output: err.message }));
    }
  };

  // 一键更新：调宿主 dsh plugin update（成功后宿主自动重启生效，用户只点一次）
  const runUpdate = async () => {
    setUpdateInfo((u) => ({ ...u, updating: true, result: null, startedAt: Date.now() }));
    try {
      const r = await call(POCKET_ENDPOINTS.update, {});
      setUpdateInfo((u) => ({
        ...u,
        updating: false,
        result: r.ok ? 'ok' : 'fail',
        autoRestart: r.autoRestart === true,
        output: r.output ?? r.error,
      }));
    } catch (err) {
      setUpdateInfo((u) => ({ ...u, updating: false, result: 'fail', output: err.message }));
    }
  };

  // 安全免责声明（issue #31）：每次开启公网都必须先弹框勾选「我已知情」。
  // 服务端同样强制（tunnel.start 需 disclaimer: true），防绕过前端直接调 RPC。

  const [disclaimerOpen, setDisclaimerOpen] = useState(false);
  const [disclaimerChecked, setDisclaimerChecked] = useState(false);

  const doStartTunnel = async () => {
    // 命名隧道模式：Token/域名没配齐就不发起（服务端同样会拒绝）
    const cfg = status?.tunnelConfig;
    if (cfg?.mode === 'named' && (!cfg.hostname || !cfg.tokenSet)) {
      setError(t('namedNeedCfg'));
      return;
    }
    setBusy(true);
    setError(null);
    setTunnelState({ phase: 'starting', detail: '正在开启…', startedAt: Date.now() });
    try {
      setStatus(await call(POCKET_ENDPOINTS.tunnelStart, { disclaimer: true }));
    } catch (err) {
      setError(err.message);
    } finally {
      setBusy(false);
    }
  };
  const startTunnel = () => {
    // 每次开启都弹免责确认（勾选后才能继续）
    setDisclaimerChecked(false);
    setDisclaimerOpen(true);
  };
  const confirmDisclaimer = () => {
    if (!disclaimerChecked) return; // 未勾选不允许
    setDisclaimerOpen(false);
    doStartTunnel();
  };

  const stopTunnel = async () => {
    try { setStatus(await call(POCKET_ENDPOINTS.tunnelStop, {})); } catch { /* 忽略 */ }
  };

  // 公网模式（issue #66）：随机域名（默认零配置）/ 固定域名（Cloudflare 命名隧道 + Tunnel Token）
  // tunnelCfg：编辑态 { hostname, token, err } | null；token 输入留空 = 保持已存的 Token 不变
  const [tunnelCfg, setTunnelCfg] = useState(null);
  const switchToQuick = async () => {
    try { setStatus(await call(POCKET_ENDPOINTS.tunnelSetConfig, { mode: 'quick' })); } catch (err) { setError(err.message); }
  };
  const saveNamedTunnel = async () => {
    try {
      setStatus(await call(POCKET_ENDPOINTS.tunnelSetConfig, {
        mode: 'named',
        hostname: tunnelCfg?.hostname ?? '',
        token: tunnelCfg?.token || undefined, // 留空不覆盖已存 Token
      }));
      setTunnelCfg(null);
    } catch (err) {
      setTunnelCfg((c) => ({ ...c, err: err.message }));
    }
  };

  // 恢复出厂设置：清本机设置 + 重设随机密码（弹窗确认；RPC 端也强制校验 confirm）
  const [resetOpen, setResetOpen] = useState(false);
  const doFactoryReset = async () => {
    setResetOpen(false);
    setBusy(true);
    setError(null);
    try {
      setStatus(await call(POCKET_ENDPOINTS.pocketReset, { confirm: true }));
      setTunnelCfg(null);
      setCustomPin(null);
      setAdvOpen(false);
      showToast(t('resetDone'));
    } catch (err) {
      setError(err.message);
      showToast(t('resetFailed'));
    } finally {
      setBusy(false);
    }
  };

  // 刷新局域网访问密码（旧密码立即作废）
  const refreshLanPin = async () => {
    try {
      const r = await call(POCKET_ENDPOINTS.lanTokenRefresh, {});
      setStatus((s) => ({ ...s, lanToken: r.lanToken }));
    } catch { /* 忽略 */ }
  };

  // 局域网访问密码开关（issue #24）：默认开启；关闭后局域网扫码直连（公网不受影响）
  const setLanAuth = async (on) => {
    try {
      const r = await call(POCKET_ENDPOINTS.lanAuthSetEnabled, { on });
      setStatus((s) => ({ ...s, lanAuthEnabled: r.lanAuthEnabled }));
    } catch { /* 忽略 */ }
  };

  // 局域网访问总开关：关闭后局域网扫码/链接直接失效（公网不受影响）。
  // 切换前弹窗确认（弹窗提醒）；服务端用 setLanEnabled 持久化，代理按 Host 实时拦截。
  const [lanToggleOpen, setLanToggleOpen] = useState(null); // null | true | false（目标 on 状态）
  const requestLanToggle = (on) => setLanToggleOpen(on);
  const confirmLanToggle = async () => {
    const on = lanToggleOpen;
    setLanToggleOpen(null);
    if (on === null) return;
    try {
      const r = await call(POCKET_ENDPOINTS.lanSetEnabled, { on });
      setStatus((s) => ({ ...s, lanEnabled: r.lanEnabled }));
    } catch (err) {
      setError(err.message);
    }
  };

  // 局域网地址手动覆盖（Tailscale/VPN 等远程访问场景）：空值恢复自动选择
  const setLanAddress = async (ip) => {
    try {
      setStatus(await call(POCKET_ENDPOINTS.lanSetOverride, { ip }));
    } catch (err) {
      setError(err.message);
    }
  };

  // 自定义访问密码（issue #33）：公网/局域网各自设固定 8 位密码（英文字母大小写或数字）；自定义后公网不再自动轮换。
  // customPin: { which: 'public'|'lan', value, err } | null —— 正在输入自定义密码的区块
  const [customPin, setCustomPin] = useState(null);
  // 只有一个密码：pocket 底层仍然分「公网」「局域网」两份存储，但对用户来说是一个。
  // 所以改的时候两份一起写同一个值，内外都用这一个密码进去。
  const saveCustomPin = async () => {
    const value = customPin?.value ?? '';
    try {
      const pub = await call(POCKET_ENDPOINTS.pinSetCustom, { which: 'public', value });
      const lan = await call(POCKET_ENDPOINTS.pinSetCustom, { which: 'lan', value });
      setStatus((s) => ({
        ...s,
        accessToken: pub.pin,
        lanToken: lan.pin,
        publicPinCustom: true,
        lanPinCustom: true,
      }));
      setCustomPin(null);
      showToast('密码已更新，对方要重新扫一次码');
    } catch (err) {
      setCustomPin((c) => ({ ...c, err: err.message }));
    }
  };
  // 渲染自定义输入行（共用）：输入框 + 保存/取消
  const customPinRow = () => h('div', { style: { marginTop: 6, fontSize: 12, color: 'var(--dsw-alias-label-secondary,#6b7280)', lineHeight: 1.5 } },
    t('customizing'),
    h('input', {
      style: { width: 130, margin: '0 6px', padding: '4px 8px', fontSize: 14, letterSpacing: 1, textAlign: 'center', border: '1px solid var(--dsw-alias-border-l2,#d1d5db)', borderRadius: 6, outline: 'none' },
      type: 'password',
      maxLength: 8,
      value: customPin?.value ?? '',
      autoFocus: true,
      onChange: (e) => setCustomPin((c) => ({ ...c, value: e.target.value.replace(/[^a-zA-Z0-9]/g, ''), err: null })),
      onKeyDown: (e) => { if (e.key === 'Enter') saveCustomPin(); if (e.key === 'Escape') setCustomPin(null); },
    }),
    h('button', { style: { ...styles.btn, height: 26, padding: '0 10px', fontSize: 12, marginLeft: 2 }, onClick: () => saveCustomPin() }, t('save')),
    h('button', { style: { ...styles.btn, height: 26, padding: '0 10px', fontSize: 12 }, onClick: () => setCustomPin(null) }, t('cancel')),
    customPin?.err ? h('div', { style: { color: 'var(--dsw-alias-state-error-primary,#dc2626)', marginTop: 4 } }, errText(customPin.err)) : null,
  );
  // 「修改」按钮（非输入态显示在密码行末尾）
  const customBtn = () => h('button', { style: { ...styles.btn, height: 26, padding: '0 10px', fontSize: 12, marginLeft: 8 }, onClick: () => setCustomPin({ value: '', err: null }) }, '修改');

  const lanUrl = status?.lanUrl;
  const tunnelUrl = status?.tunnelUrl;
  const tunnelPhase = tunnelState?.phase ?? 'idle';
  const tunnelStarting = ['downloading', 'starting', 'registering'].includes(tunnelPhase);
  const tunnelStateDetail = tunnelState?.detail ?? '';
  const tunnelStateStarted = tunnelState?.startedAt ?? null;
  // 公网模式视图（issue #66）：{ mode, hostname, tokenSet }
  const tunnelModeView = status?.tunnelConfig ?? { mode: 'quick', hostname: '', tokenSet: false };
  const namedMode = tunnelModeView.mode === 'named';
  // 模式按钮选中态高亮：固定域名模式本身，或正在编辑固定域名配置，都视为「选中」
  const namedActive = namedMode || tunnelCfg !== null;
  // 后端错误消息统一为「中文 | English」混排；按当前界面语言只显示对应一半
  const errText = (msg) => {
    const s = String(msg ?? '');
    const i = s.indexOf(' | ');
    if (i < 0) return s;
    return (t('ok') === POCKET_ZH.ok ? s.slice(0, i) : s.slice(i + 3)).trim();
  };
  // 轻量 Toast：操作成功/失败后短暂提示（自动消失，不打断操作）
  const [toast, setToast] = useState(null);
  const toastTimer = useRef(null);
  const showToast = (text) => {
    setToast(text);
    clearTimeout(toastTimer.current);
    toastTimer.current = setTimeout(() => setToast(null), 2600);
  };
  useEffect(() => () => clearTimeout(toastTimer.current), []);
  const modeBtnStyle = (active) => ({
    ...styles.btn, height: 28, padding: '0 12px', fontSize: 12,
    fontWeight: active ? 600 : 400,
    background: active ? 'var(--dsw-alias-button-primary-fill, var(--dsw-alias-brand-primary,#4f6ef7))' : 'var(--dsw-alias-bg-layer-1,#fff)',
    color: active ? 'var(--dsw-alias-label-primary-foreground, #fff)' : 'var(--dsw-alias-label-primary,inherit)',
  });
  // iOS 风格小开关（重排后统一用：局域网总开关 / 局域网密码开关）
  const Switch = (on, onClick) => h('button', {
    role: 'switch', 'aria-checked': !!on,
    style: { flexShrink: 0, width: 40, height: 22, borderRadius: 11, border: 'none', padding: 0, position: 'relative', cursor: 'pointer', font: 'inherit', background: on ? 'var(--dsw-alias-button-primary-fill, var(--dsw-alias-brand-primary,#4f6ef7))' : 'var(--dsw-alias-border-l2,#d1d5db)' },
    onClick,
  }, h('span', { style: { position: 'absolute', top: 2, left: on ? 20 : 2, width: 18, height: 18, borderRadius: '50%', background: '#fff' } }));
  // 卡片内主内容：二维码 + 地址 + 提示
  const qrArea = (src, url, hint) => h('div', { style: { background: 'var(--dsw-alias-bg-layer-2,#f3f4f6)', borderRadius: 10, padding: '10px 12px', textAlign: 'center', margin: '10px 0' } },
    h('img', { src, alt: 'QR', style: styles.qr }),
    h('div', { style: styles.code }, url),
    h('div', { style: styles.muted }, hint));
  // 设置行：上分隔线，内部第一行 = 左标签 + 右操作；extra 作为第二段渲染
  const row = (label, control, extra) => h('div', { style: { borderTop: '1px solid var(--dsw-alias-border-l2,#e5e7eb)', paddingTop: 9, marginTop: 9 } },
    h('div', { style: { display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 8 } },
      h('span', { style: { fontSize: 13 } }, label), control), extra ?? null);
  // 高级（手动选地址）展开态
  const [advOpen, setAdvOpen] = useState(false);

  // ── 扫码连接手机 ───────────────────────────────────────────────────
  // 二维码内容 = 一个普通访问网址 + 一串参数，**一个码两用**：
  //   · 手机浏览器扫 → 老老实实打开 DSH 网页，照旧要输密码；
  //   · DSH App 扫    → 读出参数直接绑定，免密码。
  // 码里的凭证是「邀请码」（dsh-pocket/lib/devices.mjs），不是总密码：
  // 一张邀请码对应一台手机，删掉它那台手机立刻失效，下次必须重新扫码。
  // 二维码在客户端画（qrcode 打进前端包），主机端不用管渲染。
  const [invites, setInvites] = useState([]);
  const [inviteBusy, setInviteBusy] = useState(false);
  const [bindQr, setBindQr] = useState(null);

  // 当前用来出码的那张邀请码 = 最新的一张
  const currentInvite = invites.length ? invites[invites.length - 1] : null;

  const bindUri = (() => {
    if (!status || !currentInvite) return null;
    // 公网入口取**配置里那个固定域名**（命名隧道），不取「此刻隧道是否在跑」。
    // 早先写成取 status.tunnelUrl 是错的：隧道一停它就变 null，二维码会当场退化成
    // 局域网码、甚至跟着隧道开关偷偷换一个，用户截的图和实际内容对不上。
    // 域名是配置里写死的，永远存在，所以码恒定。
    const cfgHost = String(status?.tunnelConfig?.hostname ?? '')
      .replace(/^https?:\/\//, '').replace(/\/+$/, '');
    const pub = cfgHost ? 'https://' + cfgHost : null;

    const lanHost = status.lanUrl ? String(status.lanUrl).replace(/^https?:\/\//, '') : '';
    const lanIp = lanHost ? lanHost.split(':')[0] : null;
    const lanPort = lanHost.includes(':') ? lanHost.split(':')[1] : '3081';
    const base = pub || (lanIp ? `http://${lanIp}:${lanPort}` : null);
    if (!base) return null;

    const p = new URLSearchParams();
    p.set('dshbind', '1');
    // 邀请码：这台手机以后就用它连；删掉这张邀请码它就进不来了。
    // 内网地址也认同一张邀请码（proxy 的 getAltTokens 对内外一视同仁），
    // 所以在家走局域网加速、在外面走公网，都不用额外配。
    p.set('pp', currentInvite.token);
    if (lanIp) { p.set('lp', currentInvite.token); p.set('li', lanIp); p.set('lport', lanPort); }
    return base + '/?' + p.toString();
  })();

  const applyInvites = (r) => setInvites(Array.isArray(r?.invites) ? r.invites : []);

  // 这里**刻意不自动创建邀请码**。
  // 早先写成「一张都没有就自动建一张，免得面板上没码可扫」，结果是每进一次设置页
  // 就凭空多出一台手机（每次进入组件重新挂载，useRef 也拦不住），授权列表越攒越乱，
  // 分不清哪台是真连过的。改成：一张都没有时只提示一下，点「生成二维码」才建。
  useEffect(() => {
    call(POCKET_ENDPOINTS.devicesList, {}).then(applyInvites).catch(() => setInvites([]));
  }, []);

  useEffect(() => {
    let alive = true;
    if (!bindUri) { setBindQr(null); return () => { alive = false; }; }
    QRCode.toDataURL(bindUri, { errorCorrectionLevel: 'M', margin: 2, width: 340 })
      .then((url) => { if (alive) setBindQr(url); })
      .catch(() => { if (alive) setBindQr(null); });
    return () => { alive = false; };
  }, [bindUri]);

  const newInvite = async () => {
    setInviteBusy(true);
    try { applyInvites(await call(POCKET_ENDPOINTS.devicesCreate, {})); }
    catch (err) { setError(err.message); }
    finally { setInviteBusy(false); }
  };
  const revokeInviteById = async (id) => {
    setInviteBusy(true);
    try { applyInvites(await call(POCKET_ENDPOINTS.devicesRevoke, { id })); showToast('已删除，那台手机下次要重新扫码'); }
    catch (err) { setError(err.message); }
    finally { setInviteBusy(false); }
  };

  const fmtTime = (ms) => {
    if (!ms) return null;
    const d = new Date(ms);
    const p = (n) => String(n).padStart(2, '0');
    return `${d.getMonth() + 1}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`;
  };

  const bindBlock = h('div', { style: styles.block },
    h('div', { style: { fontWeight: 600, fontSize: 13 } }, '扫码连接手机'),
    h('div', { style: { ...styles.muted, marginTop: 4 } },
      '手机打开 DSH App 扫这个码，自动连上，不用输密码。'),
    h('div', { style: { background: 'var(--dsw-alias-bg-layer-2,#f3f4f6)', borderRadius: 10, padding: '10px 12px', textAlign: 'center', margin: '10px 0' } },
      bindQr
        ? h('img', { src: bindQr, alt: 'QR', style: styles.qr })
        : h('div', { style: { ...styles.muted, padding: '92px 0' } },
            currentInvite ? '正在生成…' : '点下面的「生成二维码」，才会新建一台手机'),
    ),

    // 授权列表：扫过码的手机都在这里，可以逐台收回
    h('div', { style: { display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 8, marginTop: 12 } },
      h('span', { style: { fontSize: 13, fontWeight: 600 } }, '手机授权'),
      h('button', { style: { ...styles.btn, height: 26, padding: '0 10px', fontSize: 12 }, onClick: newInvite, disabled: inviteBusy },
        invites.length === 0 ? '生成二维码' : '再加一台'),
    ),
    invites.length === 0
      ? h('div', { style: { ...styles.muted, marginTop: 6 } }, '还没有手机连接过。点上面的「生成二维码」新建一台。')
      : invites.slice().reverse().map((inv) => h('div', {
          key: inv.id,
          style: { display: 'flex', alignItems: 'center', gap: 8, borderTop: '1px solid var(--dsw-alias-border-l2,#e5e7eb)', paddingTop: 8, marginTop: 8, fontSize: 12 },
        },
          h('div', { style: { flex: 1, minWidth: 0 } },
            h('div', { style: { color: 'var(--dsw-alias-label-primary,inherit)' } },
              inv.device || inv.name || '未命名设备'),
            h('div', { style: styles.muted },
              inv.lastSeen
                ? '最近连接 ' + fmtTime(inv.lastSeen)
                : (inv.id === currentInvite?.id ? '上面那个码，还没被扫过' : '还没被扫过')),
          ),
          h('button', {
            style: { ...styles.btn, height: 26, padding: '0 10px', fontSize: 12, flexShrink: 0, color: 'var(--dsw-alias-state-error-primary,#dc2626)' },
            onClick: () => revokeInviteById(inv.id),
            disabled: inviteBusy,
          }, '删除'),
        )),
    h('div', { style: { ...styles.muted, marginTop: 8 } },
      '删除后那台手机立刻连不上，App 下次打开会自动回到扫码界面。'),
  );

  // 密码行：值 + 「修改」按钮（点开就地输入，就地保存）
  const pinRow = (label, value) => h('div', {
    style: { borderTop: '1px solid var(--dsw-alias-border-l2,#e5e7eb)', paddingTop: 9, marginTop: 9 },
  },
    h('div', { style: { display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 8 } },
      h('span', { style: { fontSize: 13, color: 'var(--dsw-alias-label-secondary,#6b7280)' } }, label),
      customPin ? null : h('span', { style: { display: 'inline-flex', alignItems: 'center', gap: 8 } },
        h('span', { style: { fontFamily: 'ui-monospace,Menlo,monospace', fontSize: 13, letterSpacing: 1 } }, value || '—'),
        customBtn(),
      ),
    ),
    customPin ? h('div', { style: { marginTop: 6 } }, customPinRow()) : null,
  );

  return h('div', { style: styles.card },
    // ① 二维码：整块面板就这一件事
    bindBlock,

    // ② 改密码（只有一个密码，内外通用）
    h('div', { style: styles.block },
      h('div', { style: { fontWeight: 600, fontSize: 13 } }, '修改密码'),
      h('div', { style: { ...styles.muted, marginTop: 4 } },
        '8 位，只能用字母和数字。局域网和公网共用这一个密码，改完对方要重新扫一次码。'),
      pinRow('密码', status?.accessToken),
    ),

    error ? h('div', { style: { color: 'var(--dsw-alias-state-error-primary,#dc2626)', fontSize: 12, marginTop: 8 } }, `❌ ${errText(error)}`) : null,

    // Toast：改密码后的即时反馈
    toast ? h('div', {
      style: { position: 'fixed', left: '50%', top: '50%', transform: 'translate(-50%, -50%)', zIndex: 10001, width: 'auto', maxWidth: 280, background: 'rgba(17,24,39,.92)', color: '#fff', border: 'none', borderRadius: 10, padding: '10px 16px', fontSize: 13, lineHeight: 1.5, textAlign: 'center', boxShadow: '0 8px 24px rgba(0,0,0,.22)' },
    }, toast) : null,

    // 安全免责声明弹框：只在手动开启公网时出现（安全底线，不简化）
    disclaimerOpen ? h('div', { style: { position: 'fixed', inset: 0, zIndex: 10000, background: 'rgba(0,0,0,.5)', display: 'flex', alignItems: 'center', justifyContent: 'center', padding: 20 } },
      h('div', { style: { background: 'var(--dsw-alias-bg-layer-1,#fff)', borderRadius: 12, maxWidth: 420, width: '100%', padding: '20px 22px', boxShadow: '0 8px 32px rgba(0,0,0,.18)' } },
        h('div', { style: { fontWeight: 600, fontSize: 15, color: 'var(--dsw-alias-state-warn-primary,#b45309)', marginBottom: 10 } }, t('disclaimerTitle')),
        h('div', { style: { fontSize: 13, lineHeight: 1.7, color: 'var(--dsw-alias-label-primary,inherit)' } }, t('disclaimerBody')),
        h('label', { style: { display: 'flex', alignItems: 'center', gap: 8, marginTop: 14, fontSize: 13, cursor: 'pointer' } },
          h('input', { type: 'checkbox', checked: disclaimerChecked, onChange: (e) => setDisclaimerChecked(e.target.checked), style: { width: 16, height: 16 } }),
          t('disclaimerAgree'),
        ),
        h('div', { style: { display: 'flex', gap: 8, marginTop: 16 } },
          h('button', { style: { ...styles.btn, flex: 1 }, onClick: () => setDisclaimerOpen(false) }, t('cancel')),
          h('button', {
            style: { ...styles.primary, flex: 1, opacity: disclaimerChecked ? 1 : .5 },
            disabled: !disclaimerChecked,
            onClick: confirmDisclaimer,
          }, t('disclaimerAgree')),
        ),
        !disclaimerChecked ? h('div', { style: { marginTop: 8, fontSize: 12, color: 'var(--dsw-alias-state-error-primary,#dc2626)' } }, t('disclaimerHint')) : null,
      ),
    ) : null,
  );
}

export function apply(ctx) {
  // 兜底：确保 connection.isLoopback 为 true（issue #58）。
  // 注：代理注入的 loopback 补丁（proxy.mjs LOOPBACK_ENV_PATCH）已在 #105 移除——
  // 它与 DSH Desktop 2.0.4+ 客户端运行时不兼容，会令 BootHandoff 阶段白屏。
  // #58「远程浏览器开设置页」需上游提供官方信任来源机制才能正经解决；此处仅保留兜底。
  if (ctx?.connection) {
    try {
      Object.defineProperty(ctx.connection, 'isLoopback', { value: true, writable: true, configurable: true });
    } catch {
      try { ctx.connection.isLoopback = true; } catch { /* 忽略 */ }
    }
  }

  // 移动端适配（dsh-web-mobile 移植）：抽屉布局/触控/安全区，仅窄屏生效
  mobileApply(ctx);

  const rpcCall = (endpoint, payload, signal) =>
    ctx.connection.rpc.call(POCKET_RPC_CHANNEL, endpoint, payload, signal);

  // 设置页签接入 DSH 本地化：注册 pocket 词典（zh/en），并绑定一个随当前 locale 切换的 t()。
  const translate = ctx.locale.bind(POCKET_NS);
  ctx.effect(() => ctx.locale.register(POCKET_NS, { zh: POCKET_ZH, en: POCKET_EN }), 'dsh-pocket: pocket locale dictionaries');

  // 设置一级入口（与 通用设置/模型/插件 同级，order 1 = 通用之后、最外层）
  ctx.slots.inject('settings.section', () =>
    ctx.slots.register(
      {
        name: 'settings.section',
        id: 'pocket',
        order: 1,
        label: () => translate('section'),
        inject: () => ({ rpcCall, t: translate }),
      },
      PocketSettingsTab,
    ),
  );
}

export { name, inject, redactStatus };
