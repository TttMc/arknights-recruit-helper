#!/usr/bin/env node
/**
 * 一条命令完成一次发版：
 *   构建产物校验 → 上传 R2（版本化 + latest.apk + update.json）→
 *   裁剪 R2 旧版本 → 推送代码到 GitHub → 建 Release 并上传 APK →
 *   裁剪 GitHub 旧 Release（只留最近 N 个）
 *
 * 为什么需要它：每次发版要动 6 个地方，手工做会漏。特别是"只保留最近 5 个版本"
 * 这条，GitHub 和 R2 两边都要裁，漏一边就会悄悄攒到几十个 GB。
 *
 * 用法（在 ark-android 目录下）：
 *   node tools/release.mjs --notes "本次改了什么"          # 完整发版
 *   node tools/release.mjs --notes "..." --dry-run        # 只打印计划
 *   node tools/release.mjs --only r2                       # 只更新 R2
 *   node tools/release.mjs --only github                   # 只更新 GitHub
 *
 * 凭据来源：
 *   R2:     环境变量 R2_ACCOUNT_ID / R2_BUCKET / R2_ACCESS_KEY_ID / R2_SECRET_ACCESS_KEY / R2_PUBLIC_BASE
 *           （或读同目录上级的 r2-secrets.txt，见 loadSecrets）
 *   GitHub: --token-file 指定的文件，默认 ../.github-token.txt
 *
 * 安全：本脚本只读 git 索引决定推哪些文件，不会误传 .gitignore 里的东西。
 */
import { createHash, createHmac } from 'node:crypto';
import { readFileSync, existsSync, statSync } from 'node:fs';
import { execFileSync } from 'node:child_process';
import { join, resolve, dirname } from 'node:path';

// ---------------------------------------------------------------- 参数
const argv = process.argv.slice(2);
const opt = (n, d) => {
  const i = argv.indexOf(n);
  return i >= 0 ? argv[i + 1] : d;
};
const has = (n) => argv.includes(n);

const ROOT = resolve(process.argv[1], '..', '..');
const APK = join(ROOT, 'build', 'outputs', 'apk', 'release', 'ArknightsRecruit-release.apk');
const KEEP = parseInt(opt('--keep', '5'), 10);
const ONLY = opt('--only', null);
const DRY = has('--dry-run');
const NOTES = opt('--notes', '');
const TOKEN_FILE = opt('--token-file', join(ROOT, '..', '.github-token.txt'));
const REPO = opt('--repo', 'TttMc/arknights-recruit-helper');
const BRANCH = opt('--branch', 'main');

const die = (m) => {
  console.error('[release] ✗ ' + m);
  process.exit(1);
};
const step = (m) => console.log('\n[release] ' + m);
const info = (m) => console.log('          ' + m);

// ---------------------------------------------------------------- 工具
const sha256hex = (b) => createHash('sha256').update(b).digest('hex');
const hmac = (k, d) => createHmac('sha256', k).update(d).digest();
const git = (...a) => execFileSync('git', a, { cwd: ROOT, maxBuffer: 256 * 1024 * 1024 });

function readVersion() {
  const g = readFileSync(join(ROOT, 'build.gradle'), 'utf8');
  return {
    name: (g.match(/versionName\s+"([^"]+)"/) || [])[1],
    code: (g.match(/versionCode\s+(\d+)/) || [])[1],
  };
}

/** 从 r2-secrets.txt 读 R2 凭据（已 gitignore） */
function loadR2Secrets() {
  if (process.env.R2_ACCESS_KEY_ID) return;
  const p = join(ROOT, 'r2-secrets.txt');
  if (!existsSync(p)) return;
  for (const line of readFileSync(p, 'utf8').split('\n')) {
    const m = line.match(/^(R2_[A-Z_]+)=(.+)$/);
    if (m && !process.env[m[1]]) process.env[m[1]] = m[2].trim();
  }
}

// ---------------------------------------------------------------- R2 (S3 SigV4)
async function s3(method, path, { query = '', body = null, contentType = null } = {}) {
  const A = process.env.R2_ACCOUNT_ID;
  const AK = process.env.R2_ACCESS_KEY_ID;
  const SK = process.env.R2_SECRET_ACCESS_KEY;
  const host = `${A}.r2.cloudflarestorage.com`;
  const buf = body ? Buffer.from(body) : Buffer.alloc(0);
  const payloadHash = sha256hex(buf);
  const now = new Date();
  const full = now.toISOString().replace(/[-:]/g, '').replace(/\.\d{3}/, '');
  const short = full.slice(0, 8);

  const headers = { host, 'x-amz-content-sha256': payloadHash, 'x-amz-date': full };
  if (contentType) headers['content-type'] = contentType;
  const keys = Object.keys(headers).sort();
  const canonicalHeaders = keys.map((k) => `${k}:${headers[k]}\n`).join('');
  const signedHeaders = keys.join(';');
  const canonicalRequest = [method, path, query, canonicalHeaders, signedHeaders, payloadHash].join('\n');
  const scope = `${short}/auto/s3/aws4_request`;
  const stringToSign = ['AWS4-HMAC-SHA256', full, scope, sha256hex(Buffer.from(canonicalRequest))].join('\n');

  let k = hmac('AWS4' + SK, short);
  k = hmac(k, 'auto');
  k = hmac(k, 's3');
  k = hmac(k, 'aws4_request');
  const sig = createHmac('sha256', k).update(stringToSign).digest('hex');

  const url = `https://${host}${path}${query ? '?' + query : ''}`;
  const res = await fetch(url, {
    method,
    headers: {
      ...headers,
      ...(contentType ? { 'Content-Type': contentType } : {}),
      Authorization: `AWS4-HMAC-SHA256 Credential=${AK}/${scope}, SignedHeaders=${signedHeaders}, Signature=${sig}`,
    },
    body: body ? buf : undefined,
  });
  return { status: res.status, text: await res.text() };
}

async function r2List() {
  const B = process.env.R2_BUCKET;
  const r = await s3('GET', `/${B}`, { query: 'list-type=2' });
  if (r.status !== 200) die(`R2 列举失败 HTTP ${r.status}: ${r.text.slice(0, 200)}`);
  // 同时取 LastModified，便于按时间排序
  const out = [];
  const re = /<Contents>([\s\S]*?)<\/Contents>/g;
  let m;
  while ((m = re.exec(r.text))) {
    const block = m[1];
    const key = (block.match(/<Key>([^<]*)<\/Key>/) || [])[1];
    const size = parseInt((block.match(/<Size>(\d+)<\/Size>/) || [])[1] || '0', 10);
    const lm = (block.match(/<LastModified>([^<]*)<\/LastModified>/) || [])[1];
    if (key) out.push({ key, size, lastModified: lm });
  }
  return out;
}

async function r2Put(key, buf, ct) {
  const B = process.env.R2_BUCKET;
  const r = await s3('PUT', `/${B}/${key}`, { body: buf, contentType: ct });
  if (r.status !== 200) die(`R2 上传 ${key} 失败 HTTP ${r.status}: ${r.text.slice(0, 200)}`);
}

async function r2Delete(key) {
  const B = process.env.R2_BUCKET;
  const r = await s3('DELETE', `/${B}/${key}`);
  if (r.status !== 204 && r.status !== 200) die(`R2 删除 ${key} 失败 HTTP ${r.status}`);
}

/**
 * 裁剪 R2：只保留最近 KEEP 个版本目录（如 2.9.4/）。
 * latest.apk / update.json 是固定入口，永不删。
 */
async function pruneR2(versionName, dry) {
  const objs = await r2List();
  // 收集形如 "x.y.z/..." 的版本目录
  const byVer = new Map();
  for (const o of objs) {
    const m = o.key.match(/^(\d+\.\d+\.\d+)\//);
    if (!m) continue;
    const v = m[1];
    if (!byVer.has(v)) byVer.set(v, { ver: v, files: [], newest: '' });
    const e = byVer.get(v);
    e.files.push(o.key);
    if (o.lastModified > e.newest) e.newest = o.lastModified;
  }
  const versions = [...byVer.values()].sort((a, b) => (a.newest < b.newest ? 1 : -1));
  info(`R2 现有 ${versions.length} 个版本目录: ${versions.map((v) => v.ver).join(', ') || '（无）'}`);

  if (versions.length <= KEEP) {
    info(`未超过 ${KEEP} 个，无需裁剪`);
    return;
  }

  // 注意：刚上传的那个版本一定最新，不会被裁掉
  const drop = versions.slice(KEEP);
  for (const v of drop) {
    if (v.ver === versionName) {
      info(`跳过删除当前版本 ${v.ver}（异常保护）`);
      continue;
    }
    for (const f of v.files) {
      if (dry) info(`[dry-run] 将删除 R2: ${f}`);
      else {
        await r2Delete(f);
        info(`已删除 R2: ${f}`);
      }
    }
  }
}

// ---------------------------------------------------------------- GitHub
function ghHeaders() {
  const tok = readFileSync(TOKEN_FILE, 'utf8').trim();
  return {
    Authorization: `Bearer ${tok}`,
    Accept: 'application/vnd.github+json',
    'User-Agent': 'ark-release',
    'X-GitHub-Api-Version': '2022-11-28',
  };
}

async function gh(method, path, body) {
  const res = await fetch(`https://api.github.com${path}`, {
    method,
    headers: { ...ghHeaders(), ...(body ? { 'Content-Type': 'application/json' } : {}) },
    body: body ? JSON.stringify(body) : undefined,
  });
  const text = await res.text();
  let json = null;
  try {
    json = text ? JSON.parse(text) : null;
  } catch {
    /* 非 JSON */
  }
  return { status: res.status, json, text };
}

/**
 * 推送本地 HEAD 到 GitHub（Git Data API）。
 * 本机 github.com:443 不通，git push 走不通，只能走 api.github.com。
 */
async function pushViaApi(dry) {
  const headSha = git('rev-parse', 'HEAD').toString().trim();
  const files = git('ls-files').toString().split('\n').map((s) => s.trim()).filter(Boolean);
  info(`本地 HEAD ${headSha.slice(0, 8)}，${files.length} 个文件`);

  if (dry) {
    info('[dry-run] 将推送以上文件');
    return headSha;
  }

  // 取远端当前头
  let parentSha = null;
  const ref = await gh('GET', `/repos/${REPO}/git/ref/heads/${BRANCH}`);
  if (ref.status === 200) parentSha = ref.json.object.sha;
  else if (ref.status !== 404 && ref.status !== 409) die(`读取远端分支失败 ${ref.status}`);

  if (parentSha === headSha) {
    info('远端已是最新，跳过推送');
    return headSha;
  }

  // 逐个上传 blob
  const tree = [];
  for (let i = 0; i < files.length; i++) {
    const f = files[i];
    const buf = git('cat-file', 'blob', `HEAD:${f}`);
    const r = await gh('POST', `/repos/${REPO}/git/blobs`, {
      content: buf.toString('base64'),
      encoding: 'base64',
    });
    if (r.status !== 201) die(`上传 blob ${f} 失败 ${r.status}: ${r.text.slice(0, 200)}`);
    tree.push({ path: f, mode: '100644', type: 'blob', sha: r.json.sha });
  }
  info(`已上传 ${tree.length} 个 blob`);

  const treeRes = await gh('POST', `/repos/${REPO}/git/trees`, {
    tree,
    ...(parentSha ? { base_tree: parentSha } : {}),
  });
  if (treeRes.status !== 201) die(`建 tree 失败 ${treeRes.status}`);

  const commitRes = await gh('POST', `/repos/${REPO}/git/commits`, {
    message: git('log', '-1', '--pretty=%B').toString().trim(),
    tree: treeRes.json.sha,
    parents: parentSha ? [parentSha] : [],
  });
  if (commitRes.status !== 201) die(`建 commit 失败 ${commitRes.status}`);

  const upd =
    parentSha
      ? await gh('PATCH', `/repos/${REPO}/git/refs/heads/${BRANCH}`, {
          sha: commitRes.json.sha,
          force: false,
        })
      : await gh('POST', `/repos/${REPO}/git/refs`, {
          ref: `refs/heads/${BRANCH}`,
          sha: commitRes.json.sha,
        });
  if (upd.status !== 200 && upd.status !== 201) die(`更新分支失败 ${upd.status}: ${upd.text.slice(0, 200)}`);

  info(`已推送 commit ${commitRes.json.sha.slice(0, 8)}`);
  return commitRes.json.sha;
}

async function ensureRelease(versionName, versionCode, apkBuf, apkSha, dry) {
  const tag = `v${versionName}`;
  // 已存在就复用
  let rel = await gh('GET', `/repos/${REPO}/releases/tags/${tag}`);
  if (rel.status === 200) {
    info(`Release ${tag} 已存在（id=${rel.json.id}），将补充/覆盖资产`);
  } else if (rel.status === 404) {
    const notes =
      (NOTES ? NOTES + '\n\n' : '') +
      `### 下载\n\n` +
      `**国内推荐**：<${process.env.R2_PUBLIC_BASE}/latest.apk>\n\n` +
      `签名一致时可直接覆盖升级，不用先卸载。\n\n` +
      `### 校验\n\n\`\`\`\nSHA256: ${apkSha}\n\`\`\`\n\n` +
      `系统要求：Android 8.0+，arm64。无 INTERNET 权限。`;
    if (dry) {
      info(`[dry-run] 将创建 Release ${tag}`);
      return null;
    }
    const r = await gh('POST', `/repos/${REPO}/releases`, {
      tag_name: tag,
      target_commitish: BRANCH,
      name: `${versionName} · v${versionCode}`,
      body: notes,
      draft: false,
      prerelease: false,
    });
    if (r.status !== 201) die(`创建 Release 失败 ${r.status}: ${r.text.slice(0, 300)}`);
    rel = { json: r.json };
    info(`已创建 Release ${tag}`);
  } else {
    die(`查询 Release 失败 ${rel.status}`);
  }

  // 同名资产已存在则先删（幂等）
  const existing = rel.json.assets?.find((a) => a.name === `ArknightsRecruit-v${versionName}.apk`);
  if (existing) {
    if (dry) info(`[dry-run] 将删除旧资产 ${existing.name}`);
    else {
      await gh('DELETE', `/repos/${REPO}/releases/assets/${existing.id}`);
      info(`已删除旧资产 ${existing.name}`);
    }
  }

  if (dry) {
    info(`[dry-run] 将上传 ${(apkBuf.length / 1e6).toFixed(2)} MB 到 Release`);
    return rel.json;
  }

  // 上传（uploads.github.com，不走 api.github.com）
  const tok = readFileSync(TOKEN_FILE, 'utf8').trim();
  const url = `https://uploads.github.com/repos/${REPO}/releases/${rel.json.id}/assets?name=ArknightsRecruit-v${versionName}.apk`;
  const up = await fetch(url, {
    method: 'POST',
    headers: {
      Authorization: `Bearer ${tok}`,
      'User-Agent': 'ark-release',
      Accept: 'application/vnd.github+json',
      'Content-Type': 'application/vnd.android.package-archive',
    },
    body: apkBuf,
  });
  if (!up.ok) die(`上传 APK 失败 ${up.status}: ${(await up.text()).slice(0, 300)}`);
  info(`已上传 APK 到 Release`);
  return rel.json;
}

/** 裁剪 GitHub：只保留最近 KEEP 个 Release（按创建时间倒序） */
async function pruneGitHub(versionName, dry) {
  const r = await gh('GET', `/repos/${REPO}/releases?per_page=100`);
  if (r.status !== 200) die(`列举 Release 失败 ${r.status}`);

  const rels = r.json
    .filter((x) => !x.draft)
    .sort((a, b) => new Date(b.created_at) - new Date(a.created_at));

  info(`GitHub 现有 ${rels.length} 个 Release: ${rels.map((x) => x.tag_name).join(', ') || '（无）'}`);
  if (rels.length <= KEEP) {
    info(`未超过 ${KEEP} 个，无需裁剪`);
    return;
  }

  const drop = rels.slice(KEEP);
  for (const rel of drop) {
    if (rel.tag_name === `v${versionName}`) {
      info(`跳过删除当前版本 ${rel.tag_name}（异常保护）`);
      continue;
    }
    if (dry) {
      info(`[dry-run] 将删除 Release ${rel.tag_name}（${rel.assets.length} 个资产）`);
      continue;
    }
    // 删 Release（资产随之删除），再删 tag
    const d = await gh('DELETE', `/repos/${REPO}/releases/${rel.id}`);
    if (d.status !== 204) die(`删除 Release ${rel.tag_name} 失败 ${d.status}`);
    const t = await gh('DELETE', `/repos/${REPO}/git/refs/tags/${rel.tag_name}`);
    info(`已删除 Release ${rel.tag_name}${t.status === 204 ? ' + tag' : '（tag 删除失败，可忽略）'}`);
  }
}

// ---------------------------------------------------------------- 主流程
async function main() {
  if (!existsSync(APK)) die(`找不到 APK，先跑 gradlew.bat assembleRelease\n  ${APK}`);

  const { name: versionName, code: versionCode } = readVersion();
  if (!versionName || !versionCode) die('无法从 build.gradle 读出版本号');

  const apkBuf = readFileSync(APK);
  const apkSha = sha256hex(apkBuf);

  console.log('');
  console.log('  ┌─────────────────────────────────────────────');
  console.log(`  │  发版 v${versionName} (versionCode ${versionCode})`);
  console.log(`  │  APK ${(apkBuf.length / 1e6).toFixed(2)} MB   sha256 ${apkSha.slice(0, 16)}…`);
  console.log(`  │  保留最近 ${KEEP} 个版本${DRY ? '   [DRY-RUN]' : ''}`);
  console.log('  └─────────────────────────────────────────────');

  // 工作区必须干净，否则推送的可能是半成品
  const dirty = git('status', '--porcelain').toString().trim();
  if (dirty && !DRY) {
    die(`工作区有未提交改动，先 commit：\n${dirty}`);
  }

  loadR2Secrets();
  const doR2 = !ONLY || ONLY === 'r2';
  const doGh = !ONLY || ONLY === 'github';

  // ---- R2 ----
  if (doR2) {
    step('上传到 Cloudflare R2');
    if (!process.env.R2_ACCESS_KEY_ID) die('缺少 R2 凭据（环境变量或 r2-secrets.txt）');

    const versionedKey = `${versionName}/ArknightsRecruit-${versionName}.apk`;
    const apkType = 'application/vnd.android.package-archive';

    if (DRY) {
      info(`[dry-run] 将上传 ${versionedKey}`);
      info(`[dry-run] 将更新 latest.apk`);
      info(`[dry-run] 将更新 update.json`);
    } else {
      await r2Put(versionedKey, apkBuf, apkType);
      info(`已上传 ${versionedKey}`);
      await r2Put('latest.apk', apkBuf, apkType);
      info('已更新 latest.apk');
      const update = {
        versionName,
        versionCode: Number(versionCode),
        apk: 'latest.apk',
        size: apkBuf.length,
        sha256: apkSha,
        publishedAt: new Date().toISOString(),
        notes: `https://github.com/${REPO}/releases/latest`,
      };
      await r2Put('update.json', Buffer.from(JSON.stringify(update, null, 2)), 'application/json; charset=utf-8');
      info('已更新 update.json');
    }

    step(`裁剪 R2（保留最近 ${KEEP} 个）`);
    await pruneR2(versionName, DRY);
  }

  // ---- GitHub ----
  if (doGh) {
    step('推送代码到 GitHub');
    await pushViaApi(DRY);

    step('创建 / 更新 GitHub Release');
    await ensureRelease(versionName, versionCode, apkBuf, apkSha, DRY);

    step(`裁剪 GitHub Release（保留最近 ${KEEP} 个）`);
    await pruneGitHub(versionName, DRY);
  }

  // ---- 收尾 ----
  console.log('');
  if (DRY) {
    console.log('  [dry-run] 未做任何修改。去掉 --dry-run 执行。');
  } else {
    console.log('  ✓ 发版完成');
    if (process.env.R2_PUBLIC_BASE) console.log(`    R2    : ${process.env.R2_PUBLIC_BASE}/latest.apk`);
    console.log(`    GitHub: https://github.com/${REPO}/releases/tag/v${versionName}`);
  }
  console.log('');
}

main().catch((e) => die(e.message));
