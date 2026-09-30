// 把 dsh-pocket 的「手机访问」面板重写成我们自己的极简版：
//   ① 一个二维码（DSH App 扫码绑定，同时含局域网 + 公网）
//   ② 连接状态两行（只读，公网可开关）
//   ③ 修改密码两项
// 砍掉：宣传/star/反馈/GitHub 链接、更新与重启横幅、恢复出厂设置、
//       局域网二维码与密码开关、手动选地址、公网二维码与域名模式配置。
//
// 保留一处安全底线：手动开启公网前的免责确认弹框（只在点「开启」时出现）。
import { readFileSync, writeFileSync } from 'node:fs';

const FILE = process.argv[2];
const lines = readFileSync(FILE, 'utf8').split('\n');

// 1-based 行号：425 = `  return h('div', { style: styles.card },`，679 = `  );`
const START = 425;
const END = 679;

const before = lines.slice(0, START - 1);
const after = lines.slice(END);

if (!lines[START - 1].includes("return h('div', { style: styles.card }")) {
  throw new Error('起点行不匹配: ' + lines[START - 1]);
}
if (after[0].trim() !== '}') {
  throw new Error('终点行不匹配: ' + after[0]);
}

const replacement = String.raw`
  // 状态行：名称 + 地址 + 可选操作（只读为主，公网那行给个开关）
  const statusRow = (label, addr, ok, onToggle, btnText) => h('div', {
    style: { display: 'flex', alignItems: 'center', gap: 8, padding: '7px 0', borderTop: '1px solid var(--dsw-alias-border-l2,#e5e7eb)', fontSize: 12 },
  },
    h('span', { style: { width: 48, flexShrink: 0, color: 'var(--dsw-alias-label-secondary,#6b7280)' } }, label),
    h('span', {
      style: { flex: 1, fontFamily: 'ui-monospace,Menlo,monospace', wordBreak: 'break-all', color: ok ? 'var(--dsw-alias-label-primary,inherit)' : 'var(--dsw-alias-label-tertiary,#8b93a1)' },
    }, addr || '未开启'),
    onToggle ? h('button', {
      style: { ...styles.btn, height: 26, padding: '0 10px', fontSize: 12, flexShrink: 0 },
      onClick: onToggle,
      disabled: busy || tunnelStarting,
    }, btnText) : null,
  );

  // 密码行：值 + 「修改」按钮（点开就地输入，就地保存）
  const pinRow = (which, label, value) => h('div', {
    style: { borderTop: '1px solid var(--dsw-alias-border-l2,#e5e7eb)', paddingTop: 9, marginTop: 9 },
  },
    h('div', { style: { display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 8 } },
      h('span', { style: { fontSize: 13, color: 'var(--dsw-alias-label-secondary,#6b7280)' } }, label),
      customPin?.which === which ? null : h('span', { style: { display: 'inline-flex', alignItems: 'center', gap: 8 } },
        h('span', { style: { fontFamily: 'ui-monospace,Menlo,monospace', fontSize: 13, letterSpacing: 1 } }, value || '—'),
        h('button', {
          style: { ...styles.btn, height: 26, padding: '0 10px', fontSize: 12 },
          onClick: () => setCustomPin({ which, value: '', err: null }),
        }, '修改'),
      ),
    ),
    customPin?.which === which ? h('div', { style: { marginTop: 6 } }, customPinRow(which)) : null,
  );

  return h('div', { style: styles.card },
    h('div', null,
      h('strong', null, t('title')),
      h('div', { style: styles.muted }, '用 DSH App 扫下面的码绑定。一个码同时含局域网和公网，在哪都能连。'),
    ),

    // ① 二维码：整块面板的主角
    bindBlock,

    // ② 连接状态：只读为主，公网那行给个开关
    h('div', { style: styles.block },
      h('div', { style: { fontWeight: 600, fontSize: 13 } }, '连接状态'),
      statusRow('局域网', lanUrl, status?.lanEnabled !== false),
      statusRow('公网', tunnelUrl, !!tunnelUrl,
        tunnelUrl ? stopTunnel : startTunnel,
        tunnelUrl ? '关闭' : (busy || tunnelStarting ? '开启中…' : '开启')),
      tunnelPhase === 'error'
        ? h('div', { style: { marginTop: 6, fontSize: 12, color: 'var(--dsw-alias-state-error-primary,#dc2626)' } },
            fmt(t, 'error', { detail: errText(tunnelStateDetail) || t('unknownError') }))
        : null,
    ),

    // ③ 改密码：就留这一件事
    h('div', { style: styles.block },
      h('div', { style: { fontWeight: 600, fontSize: 13 } }, '修改密码'),
      h('div', { style: { ...styles.muted, marginTop: 4 } }, '8 位，只能用字母和数字。改完对方要重新扫一次码。'),
      pinRow('public', t('pinLabel'), status?.accessToken),
      pinRow('lan', t('lanPin'), status?.lanToken),
    ),

    error ? h('div', { style: { color: 'var(--dsw-alias-state-error-primary,#dc2626)', fontSize: 12, marginTop: 8 } }, ` + '`❌ ${errText(error)}`' + `) : null,

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
`;

writeFileSync(FILE, before.concat(replacement.split('\n').slice(1, -1), after).join('\n'), 'utf8');
console.log('已重写面板：' + (END - START + 1) + ' 行 → ' + (replacement.split('\n').length - 2) + ' 行');
