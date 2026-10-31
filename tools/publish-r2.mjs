#!/usr/bin/env node
/**
 * 把构建产物上传到 Cloudflare R2，并生成一个「国内可用」的下载入口。
 *
 * 为什么需要：实测国内直连 GitHub 只有 ~17 KB/s，23MB 的 APK 要下 20 多分钟；
 * Cloudflare 约 100 KB/s，快约 6 倍。R2 出网流量免费、10GB 存储免费，
 * 单对象最大 5TB，不受 Pages 的 25MiB 单文件上限约束。
 *
 * 依赖：只用 Node 内置模块 + AWS Signature V4（手写，不引第三方 SDK）。
 *
 * 用法：
 *   node tools/publish-r2.mjs \
 *     --account <account-id> \
 *     --bucket arknights-recruit \
 *     --access-key <R2_ACCESS_KEY_ID> \
 *     --secret-key <R2_SECRET_ACCESS_KEY> \
 *     --public-base https://pub-xxxx.r2.dev
 *
 * 或从环境变量读：R2_ACCOUNT_ID / R2_BUCKET / R2_ACCESS_KEY_ID / R2_SECRET_ACCESS_KEY / R2_PUBLIC_BASE
 *
 * 会做的事：
 *   1. 上传 build/outputs/apk/release/ArknightsRecruit-release.apk →  <version>/ArknightsRecruit-<version>.apk
 *   2. 再传一份到固定路径 latest.apk（App/二维码永远指向它，不用每次改链接）
 *   3. 上传 update.json（版本号 + SHA256 + 下载地址），供将来做版本检查用
 *   4. 打印可访问的公网 URL，并自检能否取到
 */
import { createHash, createHmac } from 'node:crypto';
import { readFileSync, statSync, existsSync } from 'node:fs';
import { join, resolve } from 'node:path';

// ---------------------------------------------------------------- 参数
const args = process.argv.slice(2);
const opt = (n, d) => {
  const i = args.indexOf(n);
  return i >= 0 ? args[i + 1] : d;
};

const ACCOUNT = opt('--account') || process.env.R2_ACCOUNT_ID;
const BUCKET = opt('--bucket') || process.env.R2_BUCKET;
const ACCESS_KEY = opt('--access-key') || process.env.R2_ACCESS_KEY_ID;
const SECRET_KEY = opt('--secret-key') || process.env.R2_SECRET_ACCESS_KEY;
const PUBLIC_BASE = (opt('--public-base') || process.env.R2_PUBLIC_BASE || '').replace(/\/$/, '');

const ROOT = resolve(process.argv[1], '..', '..');
const APK = join(ROOT, 'build', 'outputs', 'apk', 'release', 'ArknightsRecruit-release.apk');

function die(msg) {
  console.error('[r2] ' + msg);
  process.exit(2);
}

if (!ACCOUNT) die('missing --account (R2 account id)');
if (!BUCKET) die('missing --bucket');
if (!ACCESS_KEY || !SECRET_KEY) die('missing --access-key / --secret-key');
if (!existsSync(APK)) die('APK not found, run gradlew assembleRelease first: ' + APK);

// ---------------------------------------------------------------- AWS SigV4
const sha256hex = (b) => createHash('sha256').update(b).digest('hex');
const hmac = (k, d) => createHmac('sha256', k).update(d).digest();

function amzDate(d = new Date()) {
  const s = d.toISOString().replace(/[-:]/g, '').replace(/\.\d{3}/, '');
  return { full: s, short: s.slice(0, 8) };
}

/**
 * 对单个对象做 PutObject（SigV4 签名）。
 * R2 的 S3 兼容端点：https://<account>.r2.cloudflarestorage.com/<bucket>/<key>
 */
async function putObject(key, body, contentType) {
  const host = `${ACCOUNT}.r2.cloudflarestorage.com`;
  const canonicalUri = `/${BUCKET}/${key.split('/').map(encodeURIComponent).join('/')}`;
  const payloadHash = sha256hex(body);
  const { full, short } = amzDate();

  const canonicalHeaders =
    `content-type:${contentType}\n` +
    `host:${host}\n` +
    `x-amz-content-sha256:${payloadHash}\n` +
    `x-amz-date:${full}\n`;
  const signedHeaders = 'content-type;host;x-amz-content-sha256;x-amz-date';

  const canonicalRequest = [
    'PUT',
    canonicalUri,
    '', // 无 query
    canonicalHeaders,
    signedHeaders,
    payloadHash,
  ].join('\n');

  const scope = `${short}/auto/s3/aws4_request`;
  const stringToSign = ['AWS4-HMAC-SHA256', full, scope, sha256hex(Buffer.from(canonicalRequest, 'utf8'))].join('\n');

  // 派生签名密钥：kDate → kRegion → kService → kSigning
  let k = hmac('AWS4' + SECRET_KEY, short);
  k = hmac(k, 'auto');
  k = hmac(k, 's3');
  k = hmac(k, 'aws4_request');
  const signature = createHmac('sha256', k).update(stringToSign).digest('hex');

  const authorization =
    `AWS4-HMAC-SHA256 Credential=${ACCESS_KEY}/${scope}, ` +
    `SignedHeaders=${signedHeaders}, Signature=${signature}`;

  const res = await fetch(`https://${host}${canonicalUri}`, {
    method: 'PUT',
    headers: {
      'Content-Type': contentType,
      'x-amz-content-sha256': payloadHash,
      'x-amz-date': full,
      Authorization: authorization,
    },
    body,
  });

  if (!res.ok) {
    const t = await res.text();
    throw new Error(`PUT ${key} -> ${res.status}: ${t.slice(0, 300)}`);
  }
  return { size: body.length, sha256: payloadHash };
}

// ---------------------------------------------------------------- 主流程
function readVersion() {
  // 从 build.gradle 里读 versionName / versionCode，避免手抄
  const g = readFileSync(join(ROOT, 'build.gradle'), 'utf8');
  const name = (g.match(/versionName\s+"([^"]+)"/) || [])[1] || 'unknown';
  const code = (g.match(/versionCode\s+(\d+)/) || [])[1] || '0';
  return { name, code };
}

async function main() {
  const { name: versionName, code: versionCode } = readVersion();
  const apkBuf = readFileSync(APK);
  const apkSha = sha256hex(apkBuf);

  console.log(`[r2] version ${versionName} (${versionCode}), apk ${(apkBuf.length / 1e6).toFixed(2)} MB`);
  console.log(`[r2] sha256 ${apkSha}`);

  const versionedKey = `${versionName}/ArknightsRecruit-${versionName}.apk`;
  const apkType = 'application/vnd.android.package-archive';

  console.log(`[r2] uploading ${versionedKey} ...`);
  await putObject(versionedKey, apkBuf, apkType);

  console.log('[r2] uploading latest.apk ...');
  await putObject('latest.apk', apkBuf, apkType);

  // update.json：给未来的「检查更新」用。现在 App 不联网，但先存着不吃亏。
  const update = {
    versionName,
    versionCode: Number(versionCode),
    apk: 'latest.apk',
    size: apkBuf.length,
    sha256: apkSha,
    publishedAt: new Date().toISOString(),
    notes: `https://github.com/TttMc/arknights-recruit-helper/releases/latest`,
  };
  const updateBuf = Buffer.from(JSON.stringify(update, null, 2), 'utf8');
  console.log('[r2] uploading update.json ...');
  await putObject('update.json', updateBuf, 'application/json; charset=utf-8');

  console.log('');
  if (PUBLIC_BASE) {
    console.log('[r2] public URLs:');
    console.log(`       ${PUBLIC_BASE}/latest.apk`);
    console.log(`       ${PUBLIC_BASE}/${versionedKey}`);
    console.log(`       ${PUBLIC_BASE}/update.json`);

    // 自检：真取一次头
    try {
      const r = await fetch(`${PUBLIC_BASE}/update.json`, { method: 'GET' });
      console.log(`[r2] self-check update.json -> HTTP ${r.status}`);
      if (r.ok) console.log('       ' + (await r.text()).replace(/\n/g, '\n       '));
    } catch (e) {
      console.log('[r2] self-check failed (公共访问可能还没开启): ' + e.message);
    }
  } else {
    console.log('[r2] no --public-base given; upload done, but public access not verified.');
  }
}

main().catch((e) => die(e.message));
