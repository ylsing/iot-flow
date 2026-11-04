// fetch_flow.js —— 从 xj.mywuliankj.cn 抓物联卡流量，输出前端契约 JSON
// 用法: node fetch_flow.js [card] [verify]        verify = 绑定手机号后四位
// 实测接口:
//   POST /app/card/login                                  form: card, verify
//   GET  /app/client/card/get?card=…                      卡信息: used(已用MB) free(剩余MB) expirationTime state iccid phone
//   GET  /app/client/card/daily/dosage/get/list?card=…&page=1&size=100   日用量 [{time:20261103, used:803.26}]
'use strict';

const BASE = 'http://xj.mywuliankj.cn';
const UA = 'Mozilla/5.0 (iPhone; CPU iPhone OS 16_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.0 Mobile/15E148 Safari/604.1';

function makeJar() {
  const jar = new Map();
  return {
    absorb(res) {
      const raw = res.headers.getSetCookie ? res.headers.getSetCookie() : [];
      for (const c of raw) {
        const [kv] = c.split(';');
        const i = kv.indexOf('=');
        if (i > 0) jar.set(kv.slice(0, i).trim(), kv.slice(i + 1).trim());
      }
    },
    header() { return [...jar.entries()].map(([k, v]) => `${k}=${v}`).join('; '); },
  };
}

function makeClient() {
  const jar = makeJar();
  async function req(path, opts = {}) {
    const method = opts.method || 'GET';
    let url = BASE + path;
    if (opts.query) {
      const qs = new URLSearchParams(opts.query).toString();
      if (qs) url += (url.includes('?') ? '&' : '?') + qs;
    }
    const headers = { 'User-Agent': UA, Accept: 'application/json, text/plain, */*' };
    const c = jar.header();
    if (c) headers.Cookie = c;
    let body;
    if (opts.form) {
      headers['Content-Type'] = 'application/x-www-form-urlencoded; charset=UTF-8';
      body = new URLSearchParams(opts.form).toString();
    }
    const res = await fetch(url, { method, headers, body, redirect: 'manual' });
    jar.absorb(res);
    const text = await res.text();
    let json = null;
    try { json = JSON.parse(text); } catch (e) { /* 非 JSON */ }
    return { status: res.status, json, text, url };
  }
  return { req };
}

async function login(cli, card, verify) {
  const r = await cli.req('/app/card/login', { method: 'POST', form: { card, verify } });
  if (!r.json) throw new Error('登录返回非 JSON: ' + (r.text || '').slice(0, 120));
  if (r.json.code !== 0) {
    const e = new Error(r.json.msg || ('登录失败 code=' + r.json.code));
    e.code = r.json.code;
    e.detail = r.json;
    throw e;
  }
  return r.json.data;
}

async function getCard(cli, card) {
  const r = await cli.req('/app/client/card/get', { query: { card } });
  if (!r.json || r.json.code !== 0) {
    const e = new Error((r.json && r.json.msg) || '卡信息接口失败');
    e.code = r.json && r.json.code;
    throw e;
  }
  return r.json.data;
}

async function getDaily(cli, card) {
  const r = await cli.req('/app/client/card/daily/dosage/get/list', { query: { card, page: 1, size: 100 } });
  if (!r.json || r.json.code !== 0) return [];
  return (r.json.data && r.json.data.list) || [];
}

function operatorFromIccid(iccid, fallback) {
  const s = String(iccid || '');
  if (/^89860[024567]/.test(s)) return '中国移动';
  if (/^89860[135]/.test(s)) return '中国联通';
  if (/^89861[01]/.test(s)) return '中国电信';
  return fallback || '物联卡';
}

function normDate(v) {
  if (v === null || v === undefined || v === '') return null;
  if (typeof v === 'number' && v > 19000000 && v < 30000000) {
    const s = String(v);
    return s.slice(0, 4) + '-' + s.slice(4, 6) + '-' + s.slice(6, 8);
  }
  const s = String(v).trim();
  const m = s.match(/(\d{4})[-/.](\d{1,2})[-/.](\d{1,2})/);
  if (m) return m[1] + '-' + String(m[2]).padStart(2, '0') + '-' + String(m[3]).padStart(2, '0');
  const m2 = s.match(/^(\d{4})(\d{2})(\d{2})$/);
  if (m2) return m2[1] + '-' + m2[2] + '-' + m2[3];
  return s;
}

// 平台 state：0=正常；其它按停机/注销粗分（仅 0 已实测）
function normState(state) {
  const s = Number(state);
  if (s === 0) return 'active';
  if (s === 1 || s === 2) return 'suspended';
  if (s === 3 || s === 4) return 'cancelled';
  return 'active';
}

function toMB(v) {
  if (v === null || v === undefined || v === '') return null;
  const n = Number(v);
  return Number.isFinite(n) ? n : null;
}

// 补满最近 N 天（平台只返回有流量的日子）
function buildDaily(records, days) {
  const pad = (n) => String(n).padStart(2, '0');
  const iso = (d) => d.getFullYear() + '-' + pad(d.getMonth() + 1) + '-' + pad(d.getDate());
  const map = new Map();
  for (const it of records) {
    if (!it || it.time === null || it.time === undefined) continue;
    const date = normDate(typeof it.time === 'number' ? it.time : Number(it.time));
    const mb = toMB(it.used);
    if (date && mb !== null) map.set(date, mb);
  }
  const out = [];
  const today = new Date();
  for (let i = (days || 7) - 1; i >= 0; i--) {
    const d = new Date(today.getFullYear(), today.getMonth(), today.getDate() - i);
    const key = iso(d);
    out.push({ date: key, mb: map.get(key) || 0 });
  }
  return out;
}

async function fetchFlow(cfg) {
  const card = cfg.card;
  const verify = cfg.verify;
  if (!card) throw new Error('缺少卡号');
  const cli = makeClient();
  await login(cli, card, verify);
  const c = await getCard(cli, card);
  const dailyRecords = await getDaily(cli, card);

  const usedMB = toMB(c.used) || 0;
  const freeMB = toMB(c.free);
  const totalMB = freeMB === null ? null : usedMB + freeMB;

  return {
    cardNo: String(c.card || card),
    iccid: c.iccid || null,
    phone: c.phone || null,
    operator: operatorFromIccid(c.iccid, cfg.operator),
    plan: cfg.plan || '',
    totalMB: totalMB,
    usedMB: usedMB,
    leftMB: freeMB,
    expireDate: normDate(c.expirationTime),
    status: normState(c.state),
    dailyMB: buildDaily(dailyRecords, cfg.days || 7),
    updatedAt: new Date().toISOString(),
    flowRefreshTime: c.flowRefreshTime || null,
    source: {
      login: '/app/card/login',
      card: '/app/client/card/get',
      daily: '/app/client/card/daily/dosage/get/list',
    },
  };
}

module.exports = { fetchFlow: fetchFlow, login: login, getCard: getCard, getDaily: getDaily, makeClient: makeClient, normDate: normDate, buildDaily: buildDaily, operatorFromIccid: operatorFromIccid };

if (require.main === module) {
  const card = process.argv[2] || process.env.CARD;
  const verify = process.argv[3] || process.env.VERIFY;
  fetchFlow({ card: card, verify: verify })
    .then(function (d) { console.log(JSON.stringify(d, null, 2)); process.exit(0); })
    .catch(function (e) { console.error('FAILED:', e.message, e.detail ? JSON.stringify(e.detail) : ''); process.exit(1); });
}
