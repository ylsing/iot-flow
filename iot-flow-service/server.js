// server.js —— 本地小服务：定时抓取流量 + 提供 /api/flow 给手机页面
// 启动: node server.js        （读 config.json）
// 端口默认 8080，手机同一 Wi-Fi 访问 http://<电脑IP>:8080/?card=90154379
'use strict';

const http = require('http');
const fs = require('fs');
const path = require('path');
const { fetchFlow } = require('./fetch_flow');

const ROOT = __dirname;
const UI_DIR = path.join(ROOT, '..', 'iot-flow-ui');
const CONFIG_PATH = path.join(ROOT, 'config.json');
const CACHE_PATH = path.join(ROOT, 'flow.json');

function loadConfig() {
  const def = { port: 8080, card: '', verify: '', intervalMinutes: 30, days: 7 };
  try {
    return { ...def, ...JSON.parse(fs.readFileSync(CONFIG_PATH, 'utf8')) };
  } catch {
    return def;
  }
}

const cfg = loadConfig();
let state = {
  data: null,
  error: null,
  fetchedAt: 0,
  fetching: false,
};

function readCache() {
  try {
    const j = JSON.parse(fs.readFileSync(CACHE_PATH, 'utf8'));
    state.data = j;
    state.fetchedAt = Date.parse(j.updatedAt || 0) || 0;
  } catch { /* 无缓存 */ }
}

function writeCache(d) {
  try { fs.writeFileSync(CACHE_PATH, JSON.stringify(d, null, 2), 'utf8'); } catch { /* 忽略 */ }
}

async function refresh(reason) {
  if (state.fetching) return;
  state.fetching = true;
  const t0 = Date.now();
  try {
    if (!cfg.card || !cfg.verify) throw new Error('config.json 里还没填 card / verify（手机号后四位）');
    const d = await fetchFlow(cfg);
    state.data = d;
    state.error = null;
    state.fetchedAt = Date.now();
    writeCache(d);
    console.log(`[${new Date().toLocaleTimeString()}] 抓取成功(${reason}) 用时 ${Date.now() - t0}ms  ` +
      `已用 ${d.usedMB}MB / 总量 ${d.totalMB}MB`);
  } catch (e) {
    state.error = { message: e.message, code: e.code || null, at: new Date().toISOString() };
    console.error(`[${new Date().toLocaleTimeString()}] 抓取失败(${reason}): ${e.message}`);
  } finally {
    state.fetching = false;
  }
}

const MIME = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.png': 'image/png',
  '.ico': 'image/x-icon',
  '.json': 'application/json; charset=utf-8',
};

function sendJson(res, code, obj) {
  const body = JSON.stringify(obj);
  res.writeHead(code, { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store' });
  res.end(body);
}

const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, `http://${req.headers.host || 'localhost'}`);
  const p = url.pathname;

  if (p === '/api/flow') {
    // ?refresh=1 强制刷新
    if (url.searchParams.get('refresh') === '1') await refresh('手动刷新');
    if (state.data) {
      return sendJson(res, 200, { ...state.data, stale: Date.now() - state.fetchedAt > (cfg.intervalMinutes + 10) * 60000, lastError: state.error });
    }
    return sendJson(res, 503, { error: state.error || { message: '还没抓到数据' }, fetching: state.fetching });
  }

  if (p === '/api/refresh') {
    await refresh('请求刷新');
    return sendJson(res, state.data ? 200 : 503, state.data ? { ...state.data, lastError: state.error } : { error: state.error });
  }

  if (p === '/api/health') {
    return sendJson(res, 200, {
      ok: !!state.data, fetching: state.fetching, fetchedAt: state.fetchedAt,
      intervalMinutes: cfg.intervalMinutes, error: state.error, card: cfg.card,
    });
  }

  // 静态文件（默认 index.html）
  let file = p === '/' ? '/index.html' : p;
  file = file.replace(/\.\.+/g, '');
  const full = path.join(UI_DIR, file);
  if (!full.startsWith(UI_DIR)) { res.writeHead(403); return res.end('forbidden'); }
  fs.readFile(full, (err, buf) => {
    if (err) { res.writeHead(404, { 'Content-Type': 'text/plain; charset=utf-8' }); return res.end('404 ' + file); }
    res.writeHead(200, { 'Content-Type': MIME[path.extname(full).toLowerCase()] || 'application/octet-stream', 'Cache-Control': 'no-cache' });
    res.end(buf);
  });
});

readCache();
server.listen(cfg.port, '0.0.0.0', async () => {
  console.log(`流量服务已启动: http://localhost:${cfg.port}/  (卡号 ${cfg.card || '未配置'})`);
  console.log(`抓取间隔: ${cfg.intervalMinutes} 分钟`);
  await refresh('启动');
  setInterval(() => refresh('定时'), Math.max(5, cfg.intervalMinutes) * 60000);
});
