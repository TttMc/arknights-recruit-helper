#!/usr/bin/env node
/**
 * 一条命令完成一次发版：
 *   改版本号 → 编译 → 更新二维码 → 提交 → 上传 R2 → 裁剪 R2 →
 *   推送代码到 GitHub → 建 Release 并上传 APK → 裁剪 GitHub 旧 Release
 *
 * 为什么需要它：每次发版要动 7 个地方，手工做会漏。特别是"只保留最近 5 个版本"
 * 这条，GitHub 和 R2 两边都要裁，漏一边就会悄悄攒到几十个 GB。
 *
 * 用法（在 ark-android 目录下）：
 *   node tools/release.mjs --bump patch --notes "修了什么"   # 最常用：升版本+编译+发版
 *   node tools/release.mjs --notes "..." --dry-run          # 只打印计划
 *   node tools/release.mjs --only r2                        # 只更新 R2
 *   node tools/release.mjs --only github                    # 只更新 GitHub
 *   node tools/release.mjs --bump patch --no-build          # 跳过编译（复用已有 APK）
 *   node tools/release.mjs --no-qr --no-commit              # 不动二维码/不自动提交
 *
 * --bump 的取值：patch(2.9.4→2.9.5) / minor(2.9.4→2.10.0) / major(2.9.4→3.0.0)
 * versionCode 一律 +1（升级判断只看它，必须单调递增）。
 *
 * 凭据来源：
 *   R2:     环境变量 R2_ACCOUNT_ID / R2_BUCKET / R2_ACCESS_KEY_ID / R2_SECRET_ACCESS_KEY / R2_PUBLIC_BASE
 *           （或读项目根的 r2-secrets.txt，已 gitignore）
 *   GitHub: --token-file 指定的文件，默认 ../.github-token.txt
 *
 * 安全：本脚本只读 git 索引决定推哪些文件，不会误传 .gitignore 里的东西。
 */
import { createHash, createHmac } from 'node:crypto';
import { readFileSync, writeFileSync, existsSync } from 'node:fs';
import { execFileSync } from 'node:child_process';
import { join, resolve } from 'node:path';

// ---------------------------------------------------------------- 参数
const argv = process.argv.slice(2);
const opt = (n, d) => {
  const i = argv.indexOf(n);
  return i >= 0 ? argv[i + 1] : d;
};
const has = (n) => argv.includes(n);

const ROOT = resolve(process.argv[1], '..', '..');
const APK = join(ROOT, 'build', 'outputs', 'apk', 'release', 'ArknightsRecruit-release.apk');
const GRADLE_FILE = join(ROOT, 'build.gradle');
const KEEP = parseInt(opt('--keep', '5'), 10);
const ONLY = opt('--only', null);
const DRY = has('--dry-run');
const NOTES = opt('--notes', '');
const BUMP = opt('--bump', null); // patch | minor | major
const NO_BUILD = has('--no-build');
const NO_QR = has('--no-qr');
const NO_COMMIT = has('--no-commit');
const TOKEN_FILE = opt('--token-file', join(ROOT, '..', '.github-token.txt'));
const REPO = opt('--repo', 'TttMc/arknights-recruit-helper');
const BRANCH = opt('--branch', 'main');

const die = (m) => {
  console.error('[release] ✗ ' + m);
  process.exit(1);
};
const step = (m) => console.log('\n[release] ' + m);
const info = (m) => console.log('          ' + m);
const warn = (m) => console.log('          ⚠ ' + m);

// ---------------------------------------------------------------- 工具
const sha256hex = (b) => createHash('sha256').update(b).digest('hex');
const hmac = (k, d) => createHmac('sha256', k).update(d).digest();
const git = (...a) => execFileSync('git', a, { cwd: ROOT, maxBuffer: 256 * 1024 * 1024 });

function readVersion() {
  const g = readFileSync(GRADLE_FILE, 'utf8');
  return {
    name: (g.match(/versionName\s+"([^"]+)"/) || [])[1],
    code: (g.match(/versionCode\s+(\d+)/) || [])[1],
  };
}

/**
 * 改 build.gradle 里的 versionName / versionCode。
 * 只动这两行，其余原样保留 —— 用正则替换而不是重新序列化，避免格式漂移。
 */
function bumpVersion(kind) {
  const g = readFileSync(GRADLE_FILE, 'utf8');
  const cur = readVersion();
  if (!cur.name || !cur.code) die('无法从 build.gradle 读出版本号');

  const parts = cur.name.split('.').map((x) => parseInt(x, 10));
  if (parts.length !== 3 || parts.some((n) => isNaN(n))) {
    die(`版本号格式不是 x.y.z，无法自动升版：${cur.name}`);
  }
  let [maj, min, pat] = parts;
  if (kind === 'major') {
    maj += 1;
    min = 0;
    pat = 0;
  } else if (kind === 'minor') {
    min += 1;
    pat = 0;
  } else if (kind === 'patch') {
    pat += 1;
  } else {
    die(`--bump 只支持 patch / minor / major，收到：${kind}`);
  }
  const nextName = `${maj}.${min}.${pat}`;
  const nextCode = String(parseInt(cur.code, 10) + 1);

  // 原地替换这两行（保留各自的前导缩进）
  let out = g.replace(
    /(versionCode\s+)\d+/,
    (_, p1) => p1 + nextCode,
  );
  out = out.replace(
    /(versionName\s+)"[^"]*"/,
    (_, p1) => `${p1}"${nextName}"`,
  );
  if (out === g) die('版本号替换失败：正则没匹配上，请检查 build.gradle 格式');

  if (!DRY) writeFileSync(GRADLE_FILE, out, 'utf8');
  info(`版本号 ${cur.name} (${cur.code}) → ${nextName} (${nextCode})${DRY ? '  [dry-run 未写入]' : ''}`);
  return { name: nextName, code: nextCode };
}

/**
 * 确保编译环境可用。
 *
 * 本机（以及很多 Windows 机器）JAVA_HOME / ANDROID_HOME 只是临时设过的环境变量，
 * 新开的终端里没有 —— 那样 gradlew 会报 "SDK location not found"。
 * 这里主动探测常见安装路径并注入子进程环境，同时把值写到 local.properties
 * 交给 Gradle（双保险，因为 Gradle 对子进程 env 的继承偶尔不可靠）。
 */
function ensureBuildEnv() {
  const env = { ...process.env };

  // ---- JAVA_HOME ----
  if (!env.JAVA_HOME || !existsSync(join(env.JAVA_HOME, 'bin', 'java.exe'))) {
    const jdkCands = [
      'C:\\Program Files\\Eclipse Adoptium\\jdk-17.0.19.10-hotspot',
      'C:\\Program Files\\Java\\jdk-17',
      'C:\\Program Files\\Android\\Android Studio\\jbr',
      '/usr/lib/jvm/java-17-openjdk-amd64',
    ];
    for (const p of jdkCands) {
      if (existsSync(join(p, 'bin', 'java.exe')) || existsSync(join(p, 'bin', 'java'))) {
        env.JAVA_HOME = p;
        info(`JAVA_HOME 未设置，自动使用 ${p}`);
        break;
      }
    }
    if (!env.JAVA_HOME) warn('JAVA_HOME 未设置且没找到 JDK 17，编译可能失败');
  }

  // ---- ANDROID_HOME ----
  //
  // 关键：不能盲信已有的 ANDROID_HOME。本机的 ANDROID_HOME 曾被设成
  // F:\workspace\zcode\tools\Android\Sdk（目录根本不存在），Gradle 直接报
  // "SDK location not found"。所以必须**验证目录里真的有 platforms/** 才采用，
  // 否则继续往下探测。
  const sdkOk = (p) => p && existsSync(join(p, 'platforms'));
  let sdk = sdkOk(env.ANDROID_HOME) ? env.ANDROID_HOME
    : sdkOk(env.ANDROID_SDK_ROOT) ? env.ANDROID_SDK_ROOT
    : null;
  if (!sdk && (env.ANDROID_HOME || env.ANDROID_SDK_ROOT)) {
    warn(`ANDROID_HOME=${env.ANDROID_HOME || env.ANDROID_SDK_ROOT} 无效（没有 platforms/），改自动探测`);
  }
  const sdkCands = [
    'F:\\AndroidSdk',
    'C:\\Android\\Sdk',
    'C:\\Users\\' + (env.USERNAME || '') + '\\AppData\\Local\\Android\\Sdk',
    'C:\\Program Files (x86)\\Android\\android-sdk',
    '/usr/local/lib/android/sdk',
    '/opt/android-sdk',
  ];
  if (!sdk) {
    for (const p of sdkCands) {
      if (sdkOk(p)) {
        sdk = p;
        info(`自动探测到 Android SDK: ${p}`);
        break;
      }
    }
  }
  if (sdk) {
    env.ANDROID_HOME = sdk;
    env.ANDROID_SDK_ROOT = sdk;
    // 写 local.properties（已在 .gitignore），比依赖 env 继承更稳
    try {
      const lp = join(ROOT, 'local.properties');
      // local.properties 是 Java Properties 格式，反斜杠要转义
      const dir = sdk.replace(/\\/g, '\\\\');
      writeFileSync(lp, `sdk.dir=${dir}\n`, 'utf8');
      info(`已写 local.properties: sdk.dir=${sdk}`);
    } catch {
      warn('写 local.properties 失败（不影响 env 方式）');
    }
  } else {
    warn('没找到含 platforms/ 的 Android SDK（ANDROID_HOME 无效且猜不到路径），编译会失败');
  }

  return env;
}

/** 跑 gradlew assembleRelease */
function runBuild() {
  const isWin = process.platform === 'win32';
  const gradlew = join(ROOT, isWin ? 'gradlew.bat' : 'gradlew');
  if (!existsSync(gradlew)) die(`找不到 ${isWin ? 'gradlew.bat' : 'gradlew'}`);

  const env = ensureBuildEnv();

  info('开始编译（gradlew assembleRelease）…');
  try {
    execFileSync(gradlew, ['assembleRelease'], {
      cwd: ROOT,
      stdio: 'inherit',
      env,
      shell: isWin, // Windows 下 execFile 直接跑 .bat 需要 shell
    });
  } catch {
    die('编译失败，已中止发版');
  }
  info('编译完成');
}

/** 找可用的 python（需要 qrcode + pillow） */
function findPython() {
  if (process.env.PYTHON && existsSync(process.env.PYTHON)) return process.env.PYTHON;
  const cands = [
    // DSH 自带 python。ROOT 是 ark-android，往上是 dsh_work → DSH，
    // 而 DSH_desktop 与 DSH 平级，所以是 ROOT/../../.. 再加 DSH_desktop。
    join(ROOT, '..', '..', '..', 'DSH_desktop', 'resources', 'runtime',
      'primary-runtime', 'dependencies', 'python', 'python.exe'),
    'C:\\Python312\\python.exe',
    'C:\\Python311\\python.exe',
    'C:\\Python310\\python.exe',
    '/usr/bin/python3',
    '/usr/local/bin/python3',
  ];
  for (const p of cands) if (p && existsSync(p)) return p;
  return null; // 交给 PATH
}

/**
 * 重新生成二维码卡片（卡片上印着版本号，升版后必须重刷）。
 *
 * 注意：make-qr.py 里禁止对二维码做 resize（非整数倍插值会让扫码器解不出），
 * 这个坑已经踩过，改脚本时别改回去。
 */
function regenQr(versionName) {
  const py = findPython();
  if (!py) {
    warn('找不到 python，跳过二维码更新（手动跑 python tools/make-qr.py 即可）');
    return false;
  }
  const script = join(ROOT, 'tools', 'make-qr.py');
  if (!existsSync(script)) {
    warn('tools/make-qr.py 不存在，跳过二维码更新');
    return false;
  }
  try {
    execFileSync(py, [script, '--version', `v${versionName}`], { cwd: ROOT, stdio: 'inherit' });
  } catch {
    warn('二维码生成失败（可能缺 qrcode/pillow），跳过');
    return false;
  }
  // 同步一份到 design/（README 引用的是这个）
  const src = join(ROOT, 'tools', 'qr-release.png');
  const dst = join(ROOT, 'design', 'qr-release.png');
  if (existsSync(src)) {
    try {
      writeFileSync(dst, readFileSync(src));
      info('二维码已更新（tools/ + design/）');
    } catch {
      warn('二维码同步到 design/ 失败');
    }
  }
  return true;
}

/** 有改动就自动提交（只提交已被 git 跟踪或明确新增的项目文件） */
function autoCommit(msg) {
  const dirty = git('status', '--porcelain').toString().trim();
  if (!dirty) {
    info('无改动，跳过提交');
    return;
  }
  info('待提交：');
  dirty.split('\n').forEach((l) => info('  ' + l));
  git('add', '-A');
  try {
    git('commit', '-m', msg);
    const head = git('rev-parse', 'HEAD').toString().trim().slice(0, 8);
    info(`已提交 ${head}`);
  } catch {
    die('提交失败');
  }
}

/**
 * 读 R2 凭据：环境变量优先，缺失的从 r2-secrets.txt 补齐。
 *
 * ★ 注意别写成「环境变量有就整体跳过文件」★
 * 调试时很容易在 shell 里 export 过某个错误的值，那样脚本会一直用错的那个，
 * 报出来的却是 SignatureDoesNotMatch，非常难查（本次实际踩过）。
 * 所以逐项判断，并且对 secret 做长度校验（AWS 风格 secret 都是 64 位十六进制）。
 */
function loadR2Secrets() {
  const p = join(ROOT, 'r2-secrets.txt');
  const fromFile = {};
  if (existsSync(p)) {
    for (const line of readFileSync(p, 'utf8').split('\n')) {
      const m = line.match(/^(R2_[A-Z_]+)=(.+)$/);
      if (m) fromFile[m[1]] = m[2].trim();
    }
  }
  for (const k of ['R2_ACCOUNT_ID', 'R2_BUCKET', 'R2_ACCESS_KEY_ID', 'R2_SECRET_ACCESS_KEY', 'R2_PUBLIC_BASE']) {
    if (!process.env[k] && fromFile[k]) process.env[k] = fromFile[k];
  }

  // secret 合法性检查：长度不对八成是被截断或打错
  const sk = process.env.R2_SECRET_ACCESS_KEY || '';
  if (sk && sk.length !== 64) {
    warn(`R2_SECRET_ACCESS_KEY 长度 ${sk.length}，正常应为 64 —— 可能是复制时多/少了字符`);
    if (fromFile.R2_SECRET_ACCESS_KEY && fromFile.R2_SECRET_ACCESS_KEY.length === 64) {
      process.env.R2_SECRET_ACCESS_KEY = fromFile.R2_SECRET_ACCESS_KEY;
      info('已改用 r2-secrets.txt 里的值（长度正确）');
    }
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

  // ★ 签名用的小写头必须原样发出去 ★
  //
  // canonicalHeaders 里写的是 `content-type:...`（小写），而 build 时如果在这里
  // 又加一个 `Content-Type`，实际请求会带**两个不同大小写的同名头**：
  // 一个是我们签进去的、一个是 fetch 自己补的（因为我们传了对象键）。
  // 服务端按收到的头重算签名就对不上了 —— 这就是本次 SignatureDoesNotMatch 的原因。
  // 正确做法：所有头都用小写键传，一个不落地来自 headers 本身。
  const sendHeaders = { ...headers };
  if (contentType) sendHeaders['content-type'] = contentType;
  sendHeaders['authorization'] =
    `AWS4-HMAC-SHA256 Credential=${AK}/${scope}, SignedHeaders=${signedHeaders}, Signature=${sig}`;

  const url = `https://${host}${path}${query ? '?' + query : ''}`;
  const res = await fetch(url, {
    method,
    headers: sendHeaders,
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

  // ★ 幂等保护 ★
  //
  // Git Data API 只能整树重放，没法「只推变化的文件」。所以每次调用都会新建一个
  // commit —— 如果远端内容其实和本地一样（比如上一次已经推过、这次只是重传
  // Release 资产），就会白造一个内容相同的重复提交。
  //
  // 这里先比对远端 HEAD 的 tree 与本地 HEAD 的 tree：一致就说明内容相同、只是
  // commit sha 不同（SHA 里含 author/时间戳），直接跳过。
  const localTree = git('rev-parse', 'HEAD^{tree}').toString().trim();
  const remoteCommit = await gh('GET', `/repos/${REPO}/git/commits/${parentSha}`);
  if (remoteCommit.status === 200 && remoteCommit.json.tree?.sha === localTree) {
    info('远端内容与本地一致（tree 相同），跳过推送');
    return parentSha;
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
  // 升版后的目标版本。dry-run 时不写文件，所以必须靠这个变量往下传，
  // 否则后面 readVersion() 读回来还是旧值（这bug dry-run 时暴露过）。
  let targetVersion = null;

  // ---- 阶段 0：升版本号（可选）----
  if (BUMP) {
    if (ONLY) die('--bump 不能和 --only 一起用（改版本号必然是完整发版）');
    step(`升版本号（${BUMP}）`);
    const before = readVersion();
    const next = bumpVersion(BUMP);
    targetVersion = next;
    console.log('');
    console.log('  ┌─────────────────────────────────────────────');
    console.log(`  │  ${before.name} (${before.code})  →  ${next.name} (${next.code})`);
    console.log('  └─────────────────────────────────────────────');
  }

  // ---- 阶段 1：编译（可选）----
  if (!NO_BUILD) {
    if (DRY) {
      step('编译');
      info('[dry-run] 将执行 gradlew assembleRelease');
    } else {
      // 编译前必须没有未提交的源码改动，否则编出来的东西和仓库对不上。
      // build.gradle 已在阶段 0 被改过，属于预期内，放行。
      const preDirty = git('status', '--porcelain').toString().trim();
      const onlyVersion = preDirty
        .split('\n')
        .filter(Boolean)
        .every((l) => l.includes('build.gradle'));
      if (preDirty && !onlyVersion) {
        die(`工作区有未提交改动，先 commit 或 stash：\n${preDirty}`);
      }
      step('编译');
      runBuild();
    }
  } else {
    step('编译');
    info('已指定 --no-build，复用现有 APK');
  }

  if (!existsSync(APK)) {
    die(`找不到 APK，先跑 gradlew.bat assembleRelease\n  ${APK}`);
  }

  // 版本号：有 bump 就用目标值，否则读文件
  let versionName, versionCode;
  if (targetVersion) {
    versionName = targetVersion.name;
    versionCode = targetVersion.code;
  } else {
    const v = readVersion();
    versionName = v.name;
    versionCode = v.code;
  }
  if (!versionName || !versionCode) die('无法确定版本号');

  // dry-run + bump 时 APK 还是旧版本编译出来的，哈希对不上是正常的，要说清楚
  if (DRY && BUMP) {
    info(`[dry-run] 注意：APK 仍是旧版本产物，实际发版会先编译出新包`);
  }

  const apkBuf = readFileSync(APK);
  const apkSha = sha256hex(apkBuf);

  // ---- 阶段 2：二维码 ----
  if (!NO_QR && !ONLY) {
    step('更新二维码');
    if (DRY) {
      info(`[dry-run] 将重新生成二维码，印上 v${versionName}`);
    } else {
      regenQr(versionName);
    }
  }

  // ---- 阶段 3：提交 ----
  if (!NO_COMMIT && !ONLY) {
    step('提交改动');
    if (DRY) {
      info('[dry-run] 将提交 build.gradle + 二维码');
    } else {
      autoCommit(`release: v${versionName} (${versionCode})${NOTES ? '\n\n' + NOTES : ''}`);
    }
  }

  console.log('');
  console.log('  ┌─────────────────────────────────────────────');
  console.log(`  │  发版 v${versionName} (versionCode ${versionCode})`);
  console.log(`  │  APK ${(apkBuf.length / 1e6).toFixed(2)} MB   sha256 ${apkSha.slice(0, 16)}…`);
  console.log(`  │  保留最近 ${KEEP} 个版本${DRY ? '   [DRY-RUN]' : ''}`);
  console.log('  └─────────────────────────────────────────────');

  // 走到这里工作区应该是干净的（自动提交已处理）
  const dirty = git('status', '--porcelain').toString().trim();
  if (dirty && !DRY) {
    die(`工作区仍有未提交改动，先处理：\n${dirty}`);
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
