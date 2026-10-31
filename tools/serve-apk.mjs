#!/usr/bin/env node
/**
 * 局域网 APK 分发服务器（零依赖）。
 *
 * 为什么存在：每次改完代码都要「编译 → 找 APK → 数据线 / 微信传到手机 → 安装」，
 * 太麻烦。起一个只读的局域网 HTTP 服务，手机连同一个 WiFi 用浏览器打开就能下载安装。
 *
 * 用法：
 *   node tools/serve-apk.mjs              # 默认端口 8000
 *   node tools/serve-apk.mjs --port 8080
 *
 * 页面路径：
 *   /            下载页（显示版本、大小、SHA256）
 *   /latest.apk  直接下载最新 APK（固定路径，手机可存成书签）
 *   /apk/<name>  按文件名下载
 *
 * 安全边界：
 *   · 只读，只暴露 build/outputs/apk/release 目录下的 *.apk，不列目录、不传文件
 *   · 不监听公网（默认绑 0.0.0.0 但只在局域网可达；路由器 NAT 需用户自己开）
 *   · 控制台输出保持纯 ASCII，避免 Windows GBK 代码页乱码
 */
import { createServer } from 'node:http';
import { createReadStream, statSync, readdirSync } from 'node:fs';
import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { join, resolve, basename } from 'node:path';
import { networkInterfaces } from 'node:os';

const ROOT = resolve(process.argv[1], '..', '..');
const APK_DIR = join(ROOT, 'build', 'outputs', 'apk', 'release');

// ---------------------------------------------------------------- 参数
let port = 8000;
const portIdx = process.argv.indexOf('--port');
if (portIdx > 0) port = parseInt(process.argv[portIdx + 1], 10) || port;

// ---------------------------------------------------------------- 工具
function lanAddresses() {
  const out = [];
  for (const [name, addrs] of Object.entries(networkInterfaces())) {
    for (const a of addrs || []) {
      if (a.family === 'IPv4' && !a.internal) out.push({ iface: name, ip: a.address });
    }
  }
  return out;
}

function humanSize(n) {
  if (n < 1024) return n + ' B';
  if (n < 1024 * 1024) return (n / 1024).toFixed(1) + ' KB';
  return (n / 1024 / 1024).toFixed(2) + ' MB';
}

/** 扫描 APK 目录，按修改时间倒序 */
function listApks() {
  let names;
  try {
    names = readdirSync(APK_DIR).filter((f) => f.toLowerCase().endsWith('.apk'));
  } catch {
    return [];
  }
  return names
    .map((f) => {
      const p = join(APK_DIR, f);
      const st = statSync(p);
      return { name: f, path: p, size: st.size, mtime: st.mtimeMs };
    })
    .sort((a, b) => b.mtime - a.mtime);
}

/** 从 APK 的 AndroidManifest 里猜版本号太费劲，改为读构建产物同目录的 output-metadata.json */
function readMeta() {
  const candidates = [
    join(ROOT, 'build', 'outputs', 'apk', 'release', 'output-metadata.json'),
    join(ROOT, 'build', 'outputs', 'apk', 'release', 'output-metadata.json'),
  ];
  for (const c of candidates) {
    try {
      const j = JSON.parse(readFileSync(c, 'utf8'));
      const el = (j.elements || [])[0];
      if (el) return { versionName: el.versionName, versionCode: el.versionCode };
    } catch {
      /* 忽略 */
    }
  }
  return null;
}

function sha256(path) {
  return new Promise((res, rej) => {
    const h = createHash('sha256');
    createReadStream(path)
      .on('data', (d) => h.update(d))
      .on('end', () => res(h.digest('hex')))
      .on('error', rej);
  });
}

function esc(s) {
  return String(s).replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));
}

// ---------------------------------------------------------------- 页面
function pageHtml(apks, meta, host) {
  const top = apks[0];
  const rows = apks
    .map(
      (a, i) => `
      <tr${i === 0 ? ' class="new"' : ''}>
        <td><a href="/apk/${encodeURIComponent(a.name)}">${esc(a.name)}</a>${i === 0 ? ' <span class="tag">最新</span>' : ''}</td>
        <td class="num">${humanSize(a.size)}</td>
        <td class="num">${new Date(a.mtime).toLocaleString('zh-CN')}</td>
      </tr>`
    )
    .join('');

  return `<!doctype html>
<html lang="zh-CN"><head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>公招助手 · 局域网安装</title>
<style>
  :root{--bg:#14181c;--card:#1c2228;--line:#2b333b;--text:#e6e9ec;--dim:#8b959e;--amber:#e8a33d;--teal:#3fb6a8}
  *{box-sizing:border-box}
  body{margin:0;padding:20px;background:var(--bg);color:var(--text);
       font:15px/1.6 -apple-system,BlinkMacSystemFont,"Segoe UI","Noto Sans CJK SC",sans-serif}
  .wrap{max-width:720px;margin:0 auto}
  h1{font-size:20px;margin:0 0 4px;letter-spacing:.5px}
  h1 span{color:var(--amber)}
  .sub{color:var(--dim);font-size:13px;margin-bottom:22px}
  .card{background:var(--card);border:1px solid var(--line);border-radius:10px;padding:16px;margin-bottom:16px}
  .ver{font-size:13px;color:var(--dim);margin-bottom:14px}
  .ver b{color:var(--teal)}
  .big{display:block;text-align:center;padding:16px;border-radius:8px;
       background:linear-gradient(180deg,#e8a33d,#d18f2c);color:#1a1a1a;
       font-size:17px;font-weight:700;text-decoration:none;letter-spacing:.5px}
  .big:active{transform:scale(.99)}
  .hint{color:var(--dim);font-size:12.5px;margin-top:12px}
  .hint b{color:var(--text)}
  table{width:100%;border-collapse:collapse;font-size:13px}
  th,td{text-align:left;padding:7px 6px;border-bottom:1px solid var(--line)}
  th{color:var(--dim);font-weight:500;font-size:12px}
  td.num{color:var(--dim);white-space:nowrap}
  tr.new td{color:var(--text)}
  a{color:var(--teal);text-decoration:none}
  .tag{background:var(--teal);color:#0d1216;font-size:10.5px;padding:1px 6px;border-radius:4px;vertical-align:1px}
  code{background:#0f1316;border:1px solid var(--line);border-radius:4px;padding:1px 5px;font-size:12.5px}
  .sha{font-family:ui-monospace,Consolas,monospace;font-size:11px;color:var(--dim);
       word-break:break-all;margin-top:8px}
</style></head><body><div class="wrap">
  <h1>公招助手 <span>· 局域网安装</span></h1>
  <div class="sub">手机与电脑连同一个 WiFi，用手机浏览器打开本页即可下载安装</div>

  ${
    top
      ? `<div class="card">
    <div class="ver">
      ${
        meta && meta.versionName
          ? `版本 <b>${esc(meta.versionName)}</b>（code ${esc(meta.versionCode)}） · `
          : ''
      }${humanSize(top.size)} · ${new Date(top.mtime).toLocaleString('zh-CN')}
    </div>
    <a class="big" href="/latest.apk">下载并安装 ${esc(top.name)}</a>
    <div class="hint">
      下载完成后在通知栏点开安装包。<b>首次需要允许「安装未知应用」</b>，
      授权给浏览器即可。<br>
      已装旧版可以直接覆盖升级（签名一致），安装时选「更新」，
      <b>不用先卸载</b> —— 卸载会清掉悬浮窗与截屏授权，得重新点一遍 ①②③。<br>
      下载地址固定：<code>http://${esc(host)}:${port}/latest.apk</code>，可以存成书签。
    </div>
  </div>`
      : `<div class="card"><div class="ver">还没有构建产物，先跑一次 <code>gradle assembleRelease</code>。</div></div>`
  }

  ${
    apks.length
      ? `<div class="card">
    <table>
      <thead><tr><th>文件</th><th>大小</th><th>构建时间</th></tr></thead>
      <tbody>${rows}</tbody>
    </table>
  </div>`
      : ''
  }

  <div class="card">
    <div class="hint" style="margin:0">
      页面由 <code>tools/serve-apk.mjs</code> 提供，只读、只暴露 APK 文件，不做任何上传。
      关掉那个命令行窗口服务就停了。
    </div>
  </div>
</div></body></html>`;
}

// ---------------------------------------------------------------- 服务
const server = createServer(async (req, res) => {
  const url = new URL(req.url, 'http://localhost');
  const host = req.headers.host ? req.headers.host.split(':')[0] : 'localhost';

  const send = (code, headers, body) => {
    res.writeHead(code, headers);
    res.end(body);
  };

  // 只允许 GET / HEAD
  if (req.method !== 'GET' && req.method !== 'HEAD') {
    return send(405, { 'Content-Type': 'text/plain; charset=utf-8' }, 'Method Not Allowed');
  }

  const apks = listApks();

  if (url.pathname === '/' || url.pathname === '/index.html') {
    const html = pageHtml(apks, readMeta(), host);
    return send(200, {
      'Content-Type': 'text/html; charset=utf-8',
      'Cache-Control': 'no-store',
    }, html);
  }

  // 解析要下发的目标文件
  let target = null;
  if (url.pathname === '/latest.apk') {
    target = apks[0] || null;
  } else if (url.pathname.startsWith('/apk/')) {
    const name = decodeURIComponent(url.pathname.slice(5));
    // 防目录穿越：只接受 basename，且必须真实存在于扫描结果里
    if (name === basename(name)) target = apks.find((a) => a.name === name) || null;
  }

  if (!target) {
    return send(404, { 'Content-Type': 'text/plain; charset=utf-8' }, 'Not Found');
  }

  const digest = await sha256(target.path);
  // 中文文件名走 RFC 5987，避免老浏览器乱码
  const asciiName = 'GongZhaoZhuShou.apk';
  const utf8Name = encodeURIComponent(target.name);

  const headers = {
    'Content-Type': 'application/vnd.android.package-archive',
    'Content-Length': target.size,
    'Content-Disposition': `attachment; filename="${asciiName}"; filename*=UTF-8''${utf8Name}`,
    'X-Content-SHA256': digest,
    'Cache-Control': 'no-store',
  };

  console.log(`[serve-apk] GET ${url.pathname} -> ${target.name} (${humanSize(target.size)}) sha256=${digest.slice(0, 16)}...`);

  if (req.method === 'HEAD') return send(200, headers);

  res.writeHead(200, headers);
  createReadStream(target.path).pipe(res);
});

server.on('error', (e) => {
  if (e.code === 'EADDRINUSE') {
    console.error(`[serve-apk] port ${port} is already in use. Try: node tools/serve-apk.mjs --port 8080`);
  } else {
    console.error('[serve-apk] server error:', e.message);
  }
  process.exit(1);
});

server.listen(port, '0.0.0.0', async () => {
  const t = listApks()[0];
  const meta = readMeta();
  console.log('');
  console.log('  Arknights Recruit - LAN installer');
  console.log('  ---------------------------------');
  if (t) {
    console.log(`  APK   : ${t.name}`);
    console.log(`  Size  : ${humanSize(t.size)}`);
    if (meta && meta.versionName) console.log(`  Ver   : ${meta.versionName} (code ${meta.versionCode})`);
  } else {
    console.log('  APK   : (none - run gradle assembleRelease first)');
  }
  console.log('');
  console.log('  Open on your phone (same WiFi):');
  for (const { iface, ip } of lanAddresses()) {
    console.log(`    http://${ip}:${port}/        [${iface}]`);
  }
  console.log('');
  console.log('  Ctrl+C to stop.');
  console.log('');
});
