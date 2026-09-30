// dsh-pocket 设备授权：**一个二维码 = 一张邀请码**。
//
// 手机扫码后就用这张邀请码连进来；把这张邀请码删掉，那台手机立刻连不上，
// App 下次打开会自己回到扫码界面。这样「谁能访问我的电脑」是可查、可撤的，
// 而不是所有设备共用同一个总密码（那种情况下一台泄露只能整体换密码）。
//
// 总密码（token / token-lan）仍然有效，用于电脑自己的浏览器等场景，不受影响。
import { mkdirSync, readFileSync, renameSync, writeFileSync } from 'node:fs';
import { randomBytes } from 'node:crypto';
import { dirname, join } from 'node:path';
import { homedir } from 'node:os';

const HOME = process.env.DSH_HOME ?? join(homedir(), '.dsh');
const FILE = join(HOME, 'dsh-pocket', 'devices.json');

/** 64 位随机邀请码：够长，且与 8 位密码区分开（一眼能看出这是邀请码而不是总密码）。 */
function newToken() {
  return randomBytes(8).toString('hex');
}

function read() {
  try {
    const j = JSON.parse(readFileSync(FILE, 'utf8'));
    return Array.isArray(j?.invites) ? j.invites : [];
  } catch {
    return [];   // 文件不存在/损坏：当作没有邀请码，不影响总密码登录
  }
}

function write(list) {
  mkdirSync(dirname(FILE), { recursive: true });
  const tmp = `${FILE}.tmp`;
  writeFileSync(tmp, JSON.stringify({ invites: list }, null, 2), 'utf8');
  renameSync(tmp, FILE);   // 原子替换，避免写一半被读到
}

export function listInvites() {
  return read();
}

/** 认证层用：当前所有仍然有效的邀请码。 */
export function activeInviteTokens() {
  return read().map((i) => i.token).filter(Boolean);
}

export function createInvite(name) {
  const list = read();
  const inv = {
    id: randomBytes(4).toString('hex'),
    token: newToken(),
    name: typeof name === 'string' && name.trim() ? name.trim().slice(0, 40) : '未命名设备',
    createdAt: Date.now(),
    lastSeen: null,
    device: '',
  };
  list.push(inv);
  write(list);
  return inv;
}

/** 删除一张邀请码 —— 用它的那台手机随即失去访问权。 */
export function revokeInvite(id) {
  const list = read();
  const next = list.filter((i) => i.id !== id);
  if (next.length === list.length) return false;
  write(next);
  return true;
}

/** 记录某张邀请码最近一次被使用（谁在用、什么时候）。 */
export function touchInvite(token, info = {}) {
  if (!token) return;
  const list = read();
  let hit = false;
  for (const i of list) {
    if (i.token !== token) continue;
    i.lastSeen = Date.now();
    if (info.device) i.device = String(info.device).slice(0, 60);
    if (info.ip) i.ip = String(info.ip).slice(0, 45);
    hit = true;
  }
  if (hit) write(list);
}
