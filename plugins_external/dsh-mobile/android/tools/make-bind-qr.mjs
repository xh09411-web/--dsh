#!/usr/bin/env node
/**
 * 生成 DSH App 的「扫码连接」二维码（不开设置页时的备用工具）。
 *
 * 平时用不到：电脑上 DSH → 设置 → 手机访问 面板已经会显示二维码，
 * 还能列出已连接的手机并逐台删除。这个脚本只是给「不想开设置页」的场合兜底。
 *
 * 二维码内容与面板完全一致：
 *   https://<公网域名>/?dshbind=1&pp=<邀请码>&li=<局域网IP>&lport=<端口>&lp=<邀请码>
 * 一个码两用：手机浏览器扫 = 正常打开网页（要输密码）；
 *            DSH App 扫   = 读出参数直接绑定（免密码）。
 *
 * 凭证用的是**邀请码**（dsh-pocket/lib/devices.mjs），不是总密码：
 * 想收回这台手机的权限，就在设置页里把对应的邀请码删掉。
 *
 * 用法:
 *   node make-bind-qr.mjs                       # 新建一张邀请码并出码
 *   node make-bind-qr.mjs --name "老爸的手机"     # 给邀请码起个名
 *   node make-bind-qr.mjs --out D:/qr.png        # 指定输出路径
 *   DSH_PUBLIC_HOST=https://x.example.com node make-bind-qr.mjs
 */
import os from 'node:os';
import path from 'node:path';
import { createRequire } from 'node:module';
import { pathToFileURL } from 'node:url';

const require = createRequire(import.meta.url);

const HOME = process.env.DSH_HOME ?? path.join(os.homedir(), '.dsh');
const POCKET_LIB = path.join(HOME, 'profiles', 'desktop', 'node_modules', 'dsh-pocket', 'lib');

function arg(flag, fallback) {
  const i = process.argv.indexOf(flag);
  return i >= 0 && process.argv[i + 1] ? process.argv[i + 1] : fallback;
}

/** 找局域网 IPv4：优先物理网卡，跳过 Hyper-V / 虚拟网卡 / 169.254 链路本地地址。 */
function detectLanIp() {
  const candidates = [];
  for (const [ifName, addrs] of Object.entries(os.networkInterfaces())) {
    for (const a of addrs ?? []) {
      if (a.family !== 'IPv4' || a.internal) continue;
      if (a.address.startsWith('169.254.')) continue;
      if (/^172\.(1[6-9]|2\d|3[01])\./.test(a.address)) continue;   // Hyper-V 默认段
      const low = ifName.toLowerCase();
      let rank = 9;
      if (low.startsWith('wl') || low.includes('wi-fi') || low.includes('wlan')) rank = 0;
      else if (low.startsWith('eth') || low.includes('以太网')) rank = 1;
      candidates.push({ ifName, ip: a.address, rank });
    }
  }
  candidates.sort((x, y) => x.rank - y.rank);
  return candidates[0] ?? null;
}

const name = arg('--name', '未命名设备');
const pubHost = arg('--host', process.env.DSH_PUBLIC_HOST ?? '').replace(/\/+$/, '');
const lanPort = arg('--port', '3081');
const outPath = arg('--out', path.join(process.cwd(), 'DSH-bind-qr.png'));

const lan = detectLanIp();

// 邀请码走 pocket 自己的存储，与设置页共用同一份 devices.json，
// 所以这里建的码在设置页里也看得到、也能删。
let createInvite;
try {
  ({ createInvite } = await import(pathToFileURL(path.join(POCKET_LIB, 'devices.mjs')).href));
} catch (err) {
  console.error('❌ 读不到 dsh-pocket 的设备模块：');
  console.error('   ' + path.join(POCKET_LIB, 'devices.mjs'));
  console.error('   ' + (err?.message ?? err));
  process.exit(1);
}

const invite = createInvite(name);

const base = pubHost || (lan ? `http://${lan.ip}:${lanPort}` : null);
if (!base) {
  console.error('❌ 既没给 --host，也没检测到可用网卡，没法生成二维码。');
  process.exit(1);
}

const p = new URLSearchParams();
p.set('dshbind', '1');
p.set('pp', invite.token);
if (lan) {
  p.set('lp', invite.token);
  if (pubHost) { p.set('li', lan.ip); p.set('lport', lanPort); }
}
const bindUri = `${base}/?${p.toString()}`;

let QRCode;
try {
  QRCode = require('qrcode');
} catch {
  QRCode = require(path.join(HOME, 'profiles', 'desktop', 'node_modules', 'qrcode'));
}

await QRCode.toFile(outPath, bindUri, {
  width: 720,
  margin: 2,
  errorCorrectionLevel: 'M',
  color: { dark: '#101418', light: '#FFFFFF' },
});

console.log('✅ 二维码已生成（并已在设置页里登记为一张邀请码）');
console.log('');
console.log('  设备名称  : ' + invite.name);
console.log('  基准入口  : ' + base + (pubHost ? '  (公网)' : '  (局域网，仅同一 Wi-Fi 可用)'));
console.log('  局域网    : ' + (lan ? 'http://' + lan.ip + ':' + lanPort + '  [' + lan.ifName + ']' : '未检测到'));
console.log('  邀请码 id : ' + invite.id);
console.log('  输出文件  : ' + outPath);
console.log('');
console.log('  要收回这台手机的权限：DSH → 设置 → 手机访问 → 已连接的手机 → 删除。');
