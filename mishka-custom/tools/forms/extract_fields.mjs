#!/usr/bin/env node
// 从 mihomo_box 参考实现里提取「配置表单」的字段表 → tools/forms/fields.json
//
// 参考实现把字段表写成顶层字面量（const XXX_SECTIONS = [...]），少数条目的 options 由函数算出来
// （policyOptions() / proxyTargetOptions() 之类）。这些函数依赖运行时的代理列表，脚本里无法求值，
// 提取时统一标成 __dynamic__ 并在 JSON 里记录来源，Kotlin 侧运行时从 YAML 模型里现取。
//
// 用法：node extract_fields.mjs <参考实现 js 目录> <输出 json 路径>
import fs from 'node:fs';
import path from 'node:path';
import vm from 'node:vm';

const [srcDir, outPath] = process.argv.slice(2);
if (!srcDir || !outPath) {
  console.error('用法：node extract_fields.mjs <js 目录> <输出 json>');
  process.exit(2);
}

const FILES = ['pages-config.js', 'pages-flow.js', 'fields.js'];

/** 逐个字符扫描，抓 `const NAME = <字面量>;` 里最外层 [ ] 或 { } 的源码范围 */
function extractDeclarations(text) {
  const out = [];
  const re = /\b(?:export\s+)?const\s+([A-Z][A-Z0-9_]*)\s*=\s*/g;
  let m;
  while ((m = re.exec(text))) {
    const name = m[1];
    const start = re.lastIndex;
    const open = text[start];
    if (open !== '[' && open !== '{' && open !== '(') continue;
    // 整条语句捕获：从 `=` 后扫到深度 0 的分号，这样 `() => ({...})` 会作为一个函数表达式保留，
    // 供后面的字段表以 EBPF_TPL() 之类的方式调用。
    let depth = 0, i = start, inStr = null, inLine = false, inBlock = false, sawSemi = false;
    for (; i < text.length; i++) {
      const c = text[i], n = text[i + 1];
      if (inLine) { if (c === '\n') inLine = false; continue; }
      if (inBlock) { if (c === '*' && n === '/') { inBlock = false; i++; } continue; }
      if (inStr) {
        if (c === '\\') { i++; continue; }
        if (c === inStr) inStr = null;
        continue;
      }
      if (c === '/' && n === '/') { inLine = true; i++; continue; }
      if (c === '/' && n === '*') { inBlock = true; i++; continue; }
      if (c === '`') { inStr = '`'; continue; }
      if (c === '"' || c === "'") { inStr = c; continue; }
      if (c === '(' || c === '[' || c === '{') depth++;
      else if (c === ')' || c === ']' || c === '}') depth--;
      else if (c === ';' && depth === 0) { sawSemi = true; i++; break; }
      else if (c === '\n' && depth === 0 && /[\]}'"`0-9]/.test(text[i - 1] || '')) { sawSemi = true; break; }
    }
    if (!sawSemi) continue;
    out.push({ name, src: text.slice(start, i).replace(/;\s*$/, ''), line: text.slice(0, start).split('\n').length });
    re.lastIndex = i;
  }
  return out;
}

/** 求值上下文：把参考实现里的运行时函数替换成标记 */
const DYNAMIC = '__dynamic__';
function makeContext() {
  const dyn = (label) => () => ({ [DYNAMIC]: label });
  const sandbox = {
    BUILTIN_POLICIES: [['DIRECT', '直连'], ['REJECT', '拒绝'], ['PASS', 'PASS']],
    Object, Array, Math, JSON, String, Number, Boolean,
    policyNames: () => ({ [DYNAMIC]: 'policyNames' }),
    policyOptions: () => ({ [DYNAMIC]: 'policyOptions' }),
    proxyTargetOptions: () => ({ [DYNAMIC]: 'proxyTargetOptions' }),
    proxyNames: () => ({ [DYNAMIC]: 'proxyNames' }),
  };
  return vm.createContext(sandbox);
}

const result = { source: srcDir, pages: {}, dynamicNotes: [] };
for (const f of FILES) {
  const abs = path.join(srcDir, f);
  if (!fs.existsSync(abs)) { console.warn('跳过（不存在）：' + abs); continue; }
  const text = fs.readFileSync(abs, 'utf8');
  const decls = extractDeclarations(text);
  const ctx = makeContext();
  // 全量求值：不满足名字过滤的常量（EBPF_TPL / OVPN_CIPHERS / FP_OPTS…）也要先算出来，
  // 否则引用它们的字段表会一直失败。只有匹配名字过滤的才写进结果。
  const want = (n) => /SECTION|FIELDS|TEMPLATE|PORTS|EXTRA|LIST|OPTIONS|DESC|TPL|PROTO|PER_TYPE|RULES|PRESETS|LAB/.test(n);
  let pending = decls.slice();
  const failed = new Map();
  for (let pass = 0; pass < 4 && pending.length; pass++) {
    const still = [];
    for (const d of pending) {
      let value;
      try {
        value = vm.runInContext('(' + d.src + ')', ctx, { timeout: 2000 });
      } catch (e) {
        failed.set(d.name, e.message);
        still.push(d);
        continue;
      }
      if (value !== undefined && value !== null) {
        ctx[d.name] = value;                 // 注入，供后面的声明引用
        if (want(d.name)) result.pages[d.name] = { file: f, line: d.line, value };
        failed.delete(d.name);
      }
    }
    pending = still;
  }
  for (const [name, msg] of failed) console.warn(`求值失败 ${f} ${name} — ${String(msg).slice(0, 90)}`);
}

// 统计
let sections = 0, fields = 0, dyn = 0;
const types = new Map();
const walk = (v) => {
  if (Array.isArray(v)) return v.forEach(walk);
  if (v && typeof v === 'object') {
    if (v[DYNAMIC]) { dyn++; return; }
    if (typeof v.path === 'string' && typeof v.label === 'string') {
      fields++;
      const t = v.type || '(无)';
      types.set(t, (types.get(t) || 0) + 1);
    }
    if (typeof v.title === 'string' && Array.isArray(v.fields)) sections++;
    Object.values(v).forEach(walk);
  }
};
Object.values(result.pages).forEach(p => walk(p.value));
result.stats = { declarations: Object.keys(result.pages).length, sections, fields, dynamicEntries: dyn,
                 types: Object.fromEntries([...types].sort((a, b) => b[1] - a[1])) };

fs.mkdirSync(path.dirname(outPath), { recursive: true });
fs.writeFileSync(outPath, JSON.stringify(result, null, 1));
console.log(`声明 ${result.stats.declarations} 个，小节 ${sections} 个，字段 ${fields} 条，动态项 ${dyn} 条`);
console.log('类型分布：', JSON.stringify(result.stats.types));
console.log('已写 ' + outPath);
