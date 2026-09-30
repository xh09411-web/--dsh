// ==UserScript==
// @name         DSH 小鲸鱼 · DeepSeek 平台令牌自动同步
// @namespace    dsh-whale-widget-w
// @version      1.0.1
// @description  自动把 platform.deepseek.com 的登录令牌（userToken）同步给本地 DSH 小鲸鱼挂件，用于「用量」视图校准模式的官方数据拉取。装好后无需再手动 F12 抓令牌；重新登录平台后会自动同步新令牌。
// @author       dsh-whale-widget-w
// @match        https://platform.deepseek.com/*
// @run-at       document-idle
// @grant        none
// ==/UserScript==

/*
 * 工作原理：
 *   1. 读取本页 localStorage 的 userToken（DeepSeek 平台网页登录后写入的会话 JWT）
 *   2. POST 到本机 DSH Web 服务（http://127.0.0.1:3080/dsh-whale/platform-token）
 *   3. 小鲸鱼挂件「用量」校准模式即可用它调用官方用量接口
 * 触发时机：页面加载后 / localStorage 变化（重新登录）/ 每 10 分钟兜底
 * 仅推送到本机回环地址，令牌不经过任何第三方。
 */
(function () {
  'use strict'
  var ENDPOINT = 'http://127.0.0.1:3080/dsh-whale/platform-token'
  var lastSent = ''

  function extractToken(raw) {
    var token = String(raw == null ? '' : raw)
    if (!token) return ''
    // userToken 可能是裸 JWT、带引号的 JWT，或 {"value":"jwt"} 这类包装结构
    try {
      var j1 = JSON.parse(token)
      if (typeof j1 === 'string') token = j1
      else if (j1 && typeof j1.value === 'string') token = j1.value
      else if (j1 && typeof j1.token === 'string') token = j1.token
    } catch (err) {}
    token = token.replace(/^"|"$/g, '').replace(/^Bearer\s+/i, '').trim()
    return /^ey/i.test(token) ? token : ''
  }

  var warnedNoToken = false
  function sync() {
    try {
      var token = extractToken(localStorage.getItem('userToken'))
      if (!token) {
        if (!warnedNoToken) console.info('[dsh-whale] localStorage 里没有 userToken（平台未登录？），跳过同步')
        warnedNoToken = true
        return
      }
      if (token === lastSent) return
      fetch(ENDPOINT, {
        method: 'POST',
        headers: { 'Content-Type': 'text/plain;charset=UTF-8' },
        body: JSON.stringify({ token: token }),
      })
        .then(function (r) {
          if (r.ok) { lastSent = token; console.info('[dsh-whale] 平台令牌已同步到本地 DSH 挂件') }
          else console.warn('[dsh-whale] 令牌同步失败：HTTP ' + r.status)
        })
        .catch(function (err) { console.warn('[dsh-whale] 令牌同步请求未送达（被浏览器拦截？）:', err) })
    } catch (err) {}
  }

  sync()
  window.addEventListener('storage', function (e) { if (!e.key || e.key === 'userToken') sync() })
  setInterval(sync, 10 * 60 * 1000)
})()
