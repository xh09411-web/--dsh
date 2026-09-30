// 临时跳转助手：读本机 pocket 公网密码，302 跳转过去换登录 cookie。
// 目的是让浏览器拿到 DSH 的登录态去检查真实 DOM，同时密码不出现在任何命令行里。
// 只监听 127.0.0.1，用完即弃。
const http = require('http');
const fs = require('fs');
const os = require('os');
const path = require('path');

const pin = fs.readFileSync(path.join(os.homedir(), '.dsh', 'dsh-pocket', 'token'), 'utf8').trim();
const target = 'https://dsh.kaelorvyn.cn/?token=' + pin;

http.createServer((req, res) => {
  res.writeHead(302, { Location: target });
  res.end();
}).listen(8765, '127.0.0.1', () => {
  console.log('redirect helper listening on http://127.0.0.1:8765/');
});
