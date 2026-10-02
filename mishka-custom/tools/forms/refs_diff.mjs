#!/usr/bin/env node
// ConfigRefs.kt（删除前的引用检查）/ RenameSync.kt（改名同步）与参考实现 JS 的对拍。
//
// 用法：node tools/forms/refs_diff.mjs [--ref <mihomo_box 的 webroot/ui/js 目录>] [--jar /tmp/refs.jar] [--corpus 目录]
//   --ref 缺省用 tools/forms/ref/（从参考实现原样复制的 config-references.js + vendor/js-yaml.min.js，见 ref/SOURCE.txt）
//
// 对每个语料文件：
//   1. 用参考实现自带的 js-yaml 读成对象，对 proxies / proxy-groups / proxy-providers / rule-providers 里的每个名字
//      （外加几个不存在的名字）跑参考实现 deletionState()，与 `java -jar refs.jar refs` 的输出逐行比对；
//   2. 对每个名字做一次改名：JS 侧按参考实现各编辑器的同步片段（照抄）改对象；Kotlin 侧 `rename` 产出 YAML，
//      再用 js-yaml 读回，深比较两边对象（Kotlin 拒绝的——引用落在别名 / 锚点里——单独计数，不算失败）。
// 退出码：0 全一致；1 有差异。
import fs from 'node:fs';
import path from 'node:path';
import { createRequire } from 'node:module';
import { execFileSync } from 'node:child_process';
import { fileURLToPath, pathToFileURL } from 'node:url';

const args = process.argv.slice(2);
const opt = (k, d) => { const i = args.indexOf(k); return i >= 0 ? args[i + 1] : d; };
const here = path.dirname(fileURLToPath(import.meta.url));
const refDir = opt('--ref', path.join(here, 'ref'));
const jar = opt('--jar', '/tmp/refs.jar');
const corpusDir = opt('--corpus', path.join(here, 'corpus'));
if (!fs.existsSync(path.join(refDir, 'config-references.js'))) { console.error(`参考实现目录里没有 config-references.js：${refDir}`); process.exit(2); }

const require = createRequire(import.meta.url);
const jsyaml = require(path.join(refDir, 'vendor', 'js-yaml.min.js'));
const { deletionState } = await import(pathToFileURL(path.join(refDir, 'config-references.js')).href);

const kinds = ['proxies', 'proxy-groups', 'proxy-providers', 'rule-providers'];
const namesOf = (cfg, kind) => {
  const v = cfg[kind];
  if (Array.isArray(v)) return [...new Set(v.map((x) => x && x.name).filter((x) => typeof x === 'string'))];
  if (v && typeof v === 'object') return Object.keys(v);
  return [];
};
function kt(mode, file, ...rest) {
  // 中文参数 / 输出都走 UTF-8（JVM 默认编码跟随 locale，容器里常常不是 UTF-8）
  return execFileSync('java', ['-Dfile.encoding=UTF-8', '-Dstdout.encoding=UTF-8', '-Dsun.jnu.encoding=UTF-8', '-jar', jar, mode, file, ...rest],
    { encoding: 'utf8', maxBuffer: 1 << 26, env: { ...process.env, LC_ALL: 'C.UTF-8', LANG: 'C.UTF-8' } });
}
function jsRefs(cfg, kind, name) {
  const d = deletionState(cfg, kind, name);
  const out = [];
  if (d.error) out.push('ERR ' + d.error);
  for (const r of d.references || []) out.push('REF ' + r);
  for (const r of d.issues || []) out.push('ISSUE ' + r);
  return out;
}
// 参考实现各编辑器里的改名同步片段（pages-flow.js editProxySheet / editGroupSheet / editSubSheet / editEpSheet）
function jsRename(cfg, kind, oldName, n) {
  let renamed = 0;
  if (kind === 'proxies') {
    const idx = cfg.proxies.findIndex((p) => p && p.name === oldName);
    cfg.proxies[idx].name = n;
    (cfg['proxy-groups'] || []).forEach((g) => { if (g && Array.isArray(g.proxies)) g.proxies = g.proxies.map((x) => x === oldName ? (renamed++, n) : x); });
  } else if (kind === 'proxy-groups') {
    const arr = cfg['proxy-groups'];
    const idx = arr.findIndex((g) => g && g.name === oldName);
    arr[idx].name = n;
    arr.forEach((g2, gi) => { if (gi !== idx && g2 && Array.isArray(g2.proxies)) g2.proxies = g2.proxies.map((x) => x === oldName ? (renamed++, n) : x); });
    const fixRule = (r) => {
      const parts = String(r).split(',');
      if (parts.length < 2) return r;
      let pi = parts.length - 1;
      if (parts[pi].trim() === 'no-resolve') pi -= 1;
      if (pi >= 1 && parts[pi].trim() === oldName) { renamed++; parts[pi] = n; return parts.join(','); }
      return r;
    };
    if (Array.isArray(cfg.rules)) cfg.rules = cfg.rules.map(fixRule);
    const sr = cfg['sub-rules'];
    if (sr && typeof sr === 'object' && !Array.isArray(sr)) Object.keys(sr).forEach((k) => { if (Array.isArray(sr[k])) sr[k] = sr[k].map(fixRule); });
  } else if (kind === 'proxy-providers') {
    (cfg['proxy-groups'] || []).forEach((g) => { if (g && Array.isArray(g.use)) g.use = g.use.map((u) => u === oldName ? (renamed++, n) : u); });
    const pp2 = cfg['proxy-providers'];
    const out = {};
    Object.keys(pp2).forEach((k) => { out[k === oldName ? n : k] = pp2[k]; });
    cfg['proxy-providers'] = out;
  } else if (kind === 'rule-providers') {
    if (Array.isArray(cfg.rules)) cfg.rules = cfg.rules.map((r) => {
      const parts = String(r).split(',');
      if (parts.length >= 3 && parts[0].trim() === 'RULE-SET' && parts[1].trim() === oldName) { renamed++; parts[1] = n; return parts.join(','); }
      return r;
    });
    const rp = cfg['rule-providers'];
    const out2 = {};
    Object.keys(rp).forEach((k) => { out2[k === oldName ? n : k] = rp[k]; });
    cfg['rule-providers'] = out2;
  }
  return renamed;
}
const canon = (v) => JSON.stringify(v, (k, x) => (x && typeof x === 'object' && !Array.isArray(x)) ? Object.fromEntries(Object.entries(x).sort()) : x);

let cases = 0, fails = 0, refused = 0;
for (const f of fs.readdirSync(corpusDir).filter((x) => x.endsWith('.yaml')).sort()) {
  const file = path.join(corpusDir, f);
  const text = fs.readFileSync(file, 'utf8');
  let cfg;
  try { cfg = jsyaml.load(text); } catch (e) { console.log(`skip ${f}: js-yaml 读不了（${e.message.split('\n')[0]}）`); continue; }
  if (!cfg || typeof cfg !== 'object' || Array.isArray(cfg)) { console.log(`skip ${f}: 顶层不是映射`); continue; }
  // 1. 展开视图一致（别名 / 合并键）
  const plain = JSON.parse(kt('plain', file));
  cases++;
  if (canon(plain) !== canon(cfg)) { fails++; console.log(`FAIL ${f} plain 展开不一致`); console.log('  js:', canon(cfg).slice(0, 400)); console.log('  kt:', canon(plain).slice(0, 400)); }
  // 2. 引用检查
  for (const kind of kinds) {
    const names = [...namesOf(cfg, kind), '不存在的名字'];
    for (const name of names) {
      cases++;
      const want = jsRefs(cfg, kind, name);
      const got = kt('refs', file, kind, name).split('\n').filter(Boolean);
      if (want.join('\n') !== got.join('\n')) {
        fails++;
        console.log(`FAIL ${f} refs ${kind} ${name}`);
        console.log('  js:\n    ' + want.join('\n    '));
        console.log('  kt:\n    ' + got.join('\n    '));
      }
    }
  }
  // 3. 改名同步
  for (const kind of kinds) {
    for (const name of namesOf(cfg, kind)) {
      const dup = Array.isArray(cfg[kind]) && cfg[kind].filter((x) => x && x.name === name).length > 1;
      if (dup) continue;   // 同名多条：编辑器按下标改，这里不定义
      cases++;
      const out = kt('rename', file, kind, name, name + '_new');
      if (out.startsWith('REFUSED')) { refused++; continue; }
      const m = /^SYNCED (\d+)\n/.exec(out);
      const want = jsyaml.load(text);
      const renamed = jsRename(want, kind, name, name + '_new');
      const got = jsyaml.load(out.slice(m[0].length));
      if (Number(m[1]) !== renamed || canon(got) !== canon(want)) {
        fails++;
        console.log(`FAIL ${f} rename ${kind} ${name}: synced kt=${m[1]} js=${renamed}`);
        if (canon(got) !== canon(want)) { console.log('  js:', canon(want).slice(0, 600)); console.log('  kt:', canon(got).slice(0, 600)); }
      }
    }
  }
}
console.log(`refs_diff：${cases} 例，${fails} 处差异，${refused} 次改名被 Kotlin 拒绝（引用在别名 / 锚点里）`);
process.exit(fails ? 1 : 0);
