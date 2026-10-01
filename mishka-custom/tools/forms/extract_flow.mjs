#!/usr/bin/env node
// 从 mihomo_box 参考实现里提取 P2 流程页（出站代理 / 代理集合 / 代理组 / 路由规则 / 规则集合 / 子规则 / 流量隧道）
// 的字段表与页面常量 → tools/forms/fields_p2.json
//
// 与 extract_fields.mjs 的区别：这些表大多不是顶层 `const XXX = [...]`，而是写在 render*/edit* 函数体内
// （const basic = [...]、匿名数组直接 forEach(fieldRow)、fieldRow({...}) 单个字面量）。这里按「锚点文本 → 括号配对」
// 把字面量原文抠出来，再在一个只含常量 / 占位函数的沙箱里求值；依赖运行时配置的候选（outboundOptions /
// policyOptions / 当前组成员）统一标成 optionsDynamic，Kotlin 侧从 YAML 模型现取。
//
// 用法：node extract_flow.mjs <参考实现 js 目录> <输出 json 路径> [--commit <sha>]
import fs from 'node:fs';
import path from 'node:path';
import vm from 'node:vm';

const args = process.argv.slice(2);
const srcDir = args[0], outPath = args[1];
const commit = args.includes('--commit') ? args[args.indexOf('--commit') + 1] : '';
if (!srcDir || !outPath) {
  console.error('用法：node extract_flow.mjs <js 目录> <输出 json> [--commit <sha>]');
  process.exit(2);
}

const files = {};
for (const f of ['pages-flow.js', 'pages-config.js', 'fields.js']) {
  files[f] = fs.readFileSync(path.join(srcDir, f), 'utf8');
}
const lineOf = (text, idx) => text.slice(0, idx).split('\n').length;

/** 从 idx（必须指向 [ { ( 之一）开始做括号配对，返回字面量原文 */
function balanced(text, idx) {
  const open = text[idx];
  const close = { '[': ']', '{': '}', '(': ')' }[open];
  if (!close) throw new Error(`位置 ${idx} 不是括号开头：${JSON.stringify(text.slice(idx, idx + 30))}`);
  let depth = 0, inStr = null, inLine = false, inBlock = false;
  for (let i = idx; i < text.length; i++) {
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
    if (c === '`' || c === '"' || c === "'") { inStr = c; continue; }
    if (c === '(' || c === '[' || c === '{') depth++;
    else if (c === ')' || c === ']' || c === '}') { depth--; if (depth === 0) return text.slice(idx, i + 1); }
  }
  throw new Error('括号不配对');
}

/** `const NAME = <字面量>`（可在函数体内）：取字面量；new Set([...]) 取里面的数组 */
function grabConst(file, name) {
  const text = files[file];
  const re = new RegExp(`(?:const|let)\\s+${name}\\s*=\\s*`);
  const m = re.exec(text);
  if (!m) throw new Error(`${file} 里找不到 const ${name}`);
  let i = m.index + m[0].length;
  if (text.startsWith('new Set(', i)) i += 'new Set('.length;
  return { src: balanced(text, i), line: lineOf(text, m.index), file };
}

/** 锚点正则：匹配位置就是字面量的开括号（用 (?=...) 让锚点落在括号上） */
function grabAt(file, regex, what) {
  const text = files[file];
  const m = regex.exec(text);
  if (!m) throw new Error(`${file} 里找不到 ${what}`);
  const idx = m.index + (m[1] ? m[1].length : 0);
  return { src: balanced(text, idx), line: lineOf(text, idx), file };
}

/** 沙箱求值 */
const DYN = '__dynamic__';
/** 运行时候选的占位：像数组一样可被 .map/.filter/.concat 链式调用，但始终带着 __dynamic__ 标记 */
class Dyn extends Array {
  constructor(label) { super(); if (typeof label === 'string') this[DYN] = label; }
  map() { return this; } filter() { return this; } concat() { return this; } slice() { return this; }
}
const dyn = (label) => new Dyn(label);
function makeContext(extra = {}) {
  const sandbox = {
    Object, Array, Math, JSON, String, Number, Boolean, Set,
    outboundOptions: () => dyn('outbound'),
    policyOptions: () => dyn('policies'),
    policyNames: () => dyn('policies'),
    get: () => undefined,
    target: {}, cur: {},
    // Object.keys(state.cfg['proxy-providers']) 这类「从配置现取」的候选：给一个只含标记键的对象，norm() 里识别
    state: { cfg: { 'proxy-providers': { [DYN + ':providers']: true }, 'rule-providers': { [DYN + ':rule-providers']: true }, 'sub-rules': { [DYN + ':sub-rules']: true } } },
    ...extra,
  };
  return vm.createContext(sandbox);
}
function evalIn(ctx, src) {
  return vm.runInContext(`(${src})`, ctx);
}

const provenance = {};
function table(name, grabbed, ctx) {
  provenance[name] = { file: grabbed.file, line: grabbed.line };
  return evalIn(ctx, grabbed.src);
}

// ---------------------------------------------------------------- 顶层 / 函数内常量
const ctx0 = makeContext();
const OVPN_CIPHERS = table('OVPN_CIPHERS', grabConst('pages-flow.js', 'OVPN_CIPHERS'), ctx0);
const OVPN_AUTHS = table('OVPN_AUTHS', grabConst('pages-flow.js', 'OVPN_AUTHS'), ctx0);
const FP_OPTS = table('FP_OPTS', grabConst('pages-flow.js', 'FP_OPTS'), ctx0);
const EXCLUDE_TYPE_OPTIONS = table('EXCLUDE_TYPE_OPTIONS', grabConst('pages-flow.js', 'EXCLUDE_TYPE_OPTIONS'), ctx0);
const BUILTIN_POLICIES = table('BUILTIN_POLICIES', grabConst('pages-config.js', 'BUILTIN_POLICIES'), ctx0);
const ctx = makeContext({ OVPN_CIPHERS, OVPN_AUTHS, FP_OPTS, EXCLUDE_TYPE_OPTIONS, BUILTIN_POLICIES });

const NO_SERVER = table('NO_SERVER', grabConst('pages-flow.js', 'NO_SERVER'), ctx);
const SHOW_NET = table('SHOW_NET', grabConst('pages-flow.js', 'SHOW_NET'), ctx);
const SHOW_SMUX = table('SHOW_SMUX', grabConst('pages-flow.js', 'SHOW_SMUX'), ctx);
const SHOW_TLS = table('SHOW_TLS', grabConst('pages-flow.js', 'SHOW_TLS'), ctx);
const SHOW_HYTLS = table('SHOW_HYTLS', grabConst('pages-flow.js', 'SHOW_HYTLS'), ctx);
const NET_TYPES = table('NET_TYPES', grabConst('pages-flow.js', 'NET_TYPES'), ctx);
const NET_OPTS_KEYS = table('NET_OPTS_KEYS', grabConst('pages-flow.js', 'NET_OPTS_KEYS'), ctx);
const PROXY_TEMPLATES = table('PROXY_TEMPLATES', grabConst('pages-flow.js', 'PROXY_TEMPLATES'), ctx);
const PROXY_BASE_FIELDS = table('PROXY_BASE_FIELDS',
  grabAt('pages-flow.js', /(\s)\[\n\s*\{ path: 'server', label: '服务器地址'/, '节点基础字段 server/port'), ctx);
const FILE_HIDDEN_KEYS = table('FILE_HIDDEN_KEYS', grabConst('pages-flow.js', 'FILE_HIDDEN_KEYS'), ctx);
const GROUP_TYPES = table('GROUP_TYPES', grabConst('pages-flow.js', 'GROUP_TYPES'), ctx);
const RULE_TYPES = table('RULE_TYPES', grabConst('pages-flow.js', 'RULE_TYPES'), ctx);
const HC_DEFAULTS = table('HC_DEFAULTS', grabConst('pages-flow.js', 'HC_DEFAULTS'), ctx);
const HC_SUB = table('HC_SUB', grabConst('pages-flow.js', 'HC_SUB'), ctx);
const EXPR_PRESETS = table('EXPR_PRESETS', grabConst('pages-flow.js', 'EXPR_PRESETS'), ctx);

// ---- 代理集合编辑器（editSubSheet）
const SUB_DEFAULT = table('PROVIDER_DEFAULT', grabAt('pages-flow.js', /(const cur = cfg \|\| )\{ type: 'http', url: '', interval: 86400, path: '' \}/, '新建订阅默认值'), ctx);
const SUB_BASIC = table('SUB_BASIC', grabConst('pages-flow.js', 'basic'), ctx);
const SUB_HTTP_ONLY_IDX = table('SUB_HTTP_ONLY_IDX', grabConst('pages-flow.js', 'httpOnlyIdx'), ctx);
const SUB_FILTER = ['filter', 'exclude-filter', 'exclude-type'].map((p) => {
  const g = grabAt('pages-flow.js', new RegExp(`(fieldRow\\()\\{ path: '${p}', label: '[^']*', type: '(?:text|list)'`), `订阅 ${p} 字段`);
  provenance['SUB_' + p] = { file: g.file, line: g.line };
  return evalIn(ctx, g.src);
});
const SUB_OVERRIDE = table('SUB_OVERRIDE', grabAt('pages-flow.js', /(\s)\[\n\s*\{ path: 'override\.additional-prefix'/, '订阅 override 字段'), ctx);

// ---- 代理组编辑器（editGroupSheet）
const GROUP_TEMPLATE = table('GROUP_TEMPLATE', grabAt('pages-flow.js', /(const g = )\{\n\s*name: '手动选择'/, '新建代理组模板'), makeContext({ t: 'select' }));
const GROUP_SMART = table('GROUP_SMART', grabAt('pages-flow.js', /(\s)\[\n\s*\{ path: 'uselightgbm'/, 'Smart 字段'), ctx);
const GROUP_POLICY_PRIORITY = table('GROUP_POLICY_PRIORITY', grabAt('pages-flow.js', /(fieldRow\()\{ path: 'policy-priority'/, 'policy-priority'), ctx);
const GROUP_COMMON = table('GROUP_COMMON', grabAt('pages-flow.js', /(\s)\[\n\s*\{ path: 'include-all'/, '代理组通用字段'), ctx);
const GROUP_HEALTH = table('GROUP_HEALTH', grabConst('pages-flow.js', 'healthFields'), ctx);
const GROUP_TOLERANCE = table('GROUP_TOLERANCE', grabAt('pages-flow.js', /(healthFields\.splice\(4, 0, )\{ path: 'tolerance', label: '容差\(ms\)', type: 'number', optional: true, placeholder/, 'tolerance'), ctx);
const GROUP_STRATEGY = table('GROUP_STRATEGY', grabAt('pages-flow.js', /(fieldRow\()\{ path: 'strategy'/, 'strategy'), ctx);
const GROUP_DEFAULT_SELECTED = table('GROUP_DEFAULT_SELECTED', grabAt('pages-flow.js', /(fieldRow\()\{ path: 'default-selected'/, 'default-selected'),
  makeContext({ dsOpts: dyn('group-members') }));
const GROUP_EMPTY_FALLBACK = table('GROUP_EMPTY_FALLBACK', grabAt('pages-flow.js', /(fieldRow\()\{ path: 'empty-fallback'/, 'empty-fallback'),
  makeContext({ efOpts: BUILTIN_POLICIES.map(([v, d]) => [v, `${v}（${d}）`]) }));
const GROUP_OTHERS = table('GROUP_OTHERS', grabAt('pages-flow.js', /(\s)\[\n\s*\{ path: 'disable-udp'/, '代理组其他字段'), ctx);
const GROUP_PROXIES = table('GROUP_PROXIES', grabConst('pages-flow.js', 'proxiesSpec'), makeContext({ poolForProxies: dyn('policies') }));
const GROUP_USE = table('GROUP_USE', grabConst('pages-flow.js', 'useSpec'), ctx);
// 类型条件（editGroupSheet.buildCommon 里的 isHealth / isLoadBalance / isUrlTestLike）
const GROUP_HEALTH_TYPES = table('GROUP_HEALTH_TYPES', grabAt('pages-flow.js', /(const isHealth = )\['url-test','fallback','load-balance','smart'\]/, 'isHealth'), ctx);

// ---- 路由规则（editRuleSheet）
const RULE_HINTS = table('RULE_HINTS', grabAt('pages-flow.js', /(const hintFor = \(tp\) => \()\{/, 'hintFor'), ctx);
const RULE_MULTI_TYPES = table('RULE_MULTI_TYPES', grabAt('pages-flow.js', /(const isMulti = )\['AND','OR','NOT','SUB-RULE'\]/, 'isMulti'), ctx);

// ---- 规则集合（editEpSheet）
const EP_DEFAULT = table('RULE_PROVIDER_DEFAULT', grabAt('pages-flow.js', /(const cur = cfg \|\| )\{ type: 'http', behavior: 'domain', format: 'yaml', url: '', interval: 86400 \}/, '新建规则集默认值'), ctx);
const EP_FIELDS = table('EP_FIELDS', grabAt('pages-flow.js', /(const epFieldSpecs = \(\) => )\[/, 'epFieldSpecs'), ctx);
const EP_HTTP_ONLY_IDX = table('EP_HTTP_ONLY_IDX', grabConst('pages-flow.js', 'epHttpOnlyIdx'), ctx);

// ---- 流量隧道（pages-config.js renderTunnels）
const TUNNEL_FIELDS = table('TUNNEL_FIELDS', grabAt('pages-config.js', /(\s)\[\n\s*\{ path: 'network', label: '协议 network'/, '隧道字段'), ctx);
const TUNNEL_TEMPLATE = table('TUNNEL_TEMPLATE', grabAt('pages-config.js', /(editTunSheet\(-1, )\{ network: 'tcp', address: '', target: '' \}/, '新建隧道默认值'), ctx);

// ---------------------------------------------------------------- 规范化
/** 参考字段 → 我们的字段表项：datalist 函数求值、join 保留、list+datalist → picklist */
function norm(f, extra = {}) {
  const o = { ...f, ...extra };
  if (o.datalist !== undefined) {
    const v = typeof o.datalist === 'function' ? o.datalist() : o.datalist;
    if (v && v[DYN]) o.optionsDynamic = v[DYN];
    else if (v.length === 1 && typeof v[0] === 'string' && v[0].startsWith(DYN + ':')) o.optionsDynamic = v[0].slice(DYN.length + 1);
    else o.options = v.map((x) => (Array.isArray(x) ? x : [x, x]));
    delete o.datalist;
    if (o.type === 'list') o.type = 'picklist';
  }
  if (o.options && o.options[DYN]) { o.optionsDynamic = o.options[DYN]; delete o.options; }
  delete o.pickTitle; delete o.emptyText; delete o.tagCls;
  // 参考实现的流程页编辑器全部用 boolAsPick()：布尔字段是「默认（不覆写）/ 开 / 关」三态弹窗，默认 = 删键
  if (o.type === 'bool') o.boolAs = 'pick';
  return o;
}
const only = (types) => ({ only: types });
const notFile = ['http', 'inline'];   // 参考实现：t === 'file' 时隐藏

const PROXY_PROVIDER_SECTIONS = [
  { title: '基础', fields: [
    ...SUB_BASIC.map((f, i) => norm(f, SUB_HTTP_ONLY_IDX.includes(i) ? only(notFile) : {})),
    { custom: 'provider-payload' },   // inline 类型的内联节点（参考实现是 YAML 文本框；这里复用出站代理页）
  ] },
  { title: '请求 / 过滤', fields: [
    { path: 'header', label: '请求头 header', type: 'headers', optional: true, arrayValues: true, desc: '如 UA / Authorization，每行一条', only: notFile },
    ...SUB_FILTER.map((f) => norm(f)),
  ] },
  { title: '健康检查', fields: [
    { custom: 'provider-health-enable' },   // 三态：默认（删整块）/ 开（补 url + interval 默认值）/ 关
    ...HC_SUB.map((f) => norm(f)),
  ] },
  { title: '覆写 override（对该订阅全部节点生效）', fields: [
    ...SUB_OVERRIDE.map((f) => norm(f)),
    { path: 'override.proxy-name', label: 'proxy-name 批量重命名', type: 'maplist', optional: true, desc: '正则 pattern→target，支持 $1 引用' },
  ] },
  { title: 'override-expr（按表达式批量修改节点 · 新版）', fields: [
    { custom: 'override-expr' },
  ] },
];

const healthOnly = only(GROUP_HEALTH_TYPES);
const GROUP_SECTIONS = [
  { title: '成员', fields: [norm(GROUP_PROXIES), norm(GROUP_USE)] },
  { title: '通用参数（按类型自动显示）', fields: [
    ...GROUP_COMMON.map((f) => norm(f)),
    ...GROUP_HEALTH.slice(0, 4).map((f) => norm(f, healthOnly)),
    norm(GROUP_TOLERANCE, only(['url-test', 'smart'])),
    ...GROUP_HEALTH.slice(4).map((f) => norm(f, healthOnly)),
    norm(GROUP_STRATEGY, only(['load-balance'])),
    norm(GROUP_DEFAULT_SELECTED, only(['select'])),
    norm(GROUP_EMPTY_FALLBACK),
    ...GROUP_OTHERS.map((f) => norm(f)),
  ] },
  { title: 'Smart 专属参数', fields: [
    ...GROUP_SMART.map((f) => norm(f, only(['smart']))),
    norm(GROUP_POLICY_PRIORITY, only(['smart'])),
  ] },
];

const RULE_PROVIDER_SECTIONS = [
  { title: '基础', fields: [
    ...EP_FIELDS.map((f, i) => {
      const o = norm(f, EP_HTTP_ONLY_IDX.includes(i) ? only(notFile) : {});
      if (o.path === 'type') { delete o.allowEmpty; delete o.emptyLabel; delete o.desc; }   // 「不覆写」是锚点继承语义，这里不做
      return o;
    }),
    { custom: 'rule-provider-payload' },   // inline 类型的内联规则（参考实现没有编辑入口；这里复用规则列表页）
  ] },
];

const TUNNEL_SECTIONS = [
  { title: '隧道', fields: TUNNEL_FIELDS.map((f) => {
    const o = norm(f);
    // 参考实现把 network 当字符串（tcp / udp / tcp/udp）保存；内核映射写法要求 []string，这里按列表写
    if (o.path === 'network') return { path: 'network', label: o.label, type: 'picklist', options: [['tcp', 'tcp'], ['udp', 'udp']], desc: '映射写法里是列表（内核 Tunnel.network []string）' };
    return o;
  }) },
];

// 节点详情页各段标题（editProxySheet 的 group-title 文本）：从源码里核对一遍，改了会在这里报错
const sectionTitle = (text) => {
  if (!files['pages-flow.js'].includes(`{ class: 'group-title', text: '${text}' }`)) throw new Error(`pages-flow.js 里找不到小节标题 ${text}`);
  return text;
};
const PROXY_SECTION_TITLES = {
  net: sectionTitle('传输层'), tls: sectionTitle('TLS / Reality'), smux: sectionTitle('多路复用 smux'),
  tail: sectionTitle('通用链式 / 拨号'), other: sectionTitle('其他参数（YAML，可选）'),
};

// 协议特性：参考实现的五个集合 → 每种协议显示哪些小节
const types = Object.keys(PROXY_TEMPLATES);
const PROXY_FEATURES = {};
for (const t of types) {
  const s = [];
  if (!NO_SERVER.includes(t)) s.push('server');
  if (SHOW_NET.includes(t)) s.push('net');
  if (SHOW_TLS.includes(t)) s.push('tls', 'tlsbool');
  else if (SHOW_HYTLS.includes(t)) s.push('tls');
  if (SHOW_SMUX.includes(t)) s.push('smux');
  s.push('tail');
  PROXY_FEATURES[t] = s;
}

// 规则类型：清单 / 提示语来自参考实现；multi（多行输入）= isMulti；noPayload = MATCH；ipRule = isIpRule（no-resolve 仅对 IP 类有效）。
// targetKind 是我们的附加项：RULE-SET 的载荷是规则集合名、SUB-RULE 的目标是子规则名，Kotlin 侧据此给出候选。
const isIpRule = (t) => t.startsWith('IP-') || t.startsWith('SRC-IP-') || ['GEOIP', 'SRC-GEOIP', 'IP-ASN', 'SRC-IP-ASN', 'RULE-SET'].includes(t);
const RULE_TYPE_SPECS = RULE_TYPES.map((v) => {
  const r = { value: v, label: v, placeholder: RULE_HINTS[v] || '匹配值' };
  if (RULE_MULTI_TYPES.includes(v)) r.multi = true;
  if (v === 'MATCH') r.noPayload = true;
  if (isIpRule(v)) r.ipRule = true;
  if (v === 'RULE-SET') r.targetKind = 'rule-providers';
  if (v === 'SUB-RULE') r.targetKind = 'sub-rules';
  return r;
});

const out = {
  source: {
    repo: 'jieluojun/mihomo_box',
    commit,
    dir: srcDir,
    note: '由 tools/forms/extract_flow.mjs 从参考实现求值提取；provenance 里是每张表在源码里的位置（文件:行）。',
  },
  provenance,
  PROXY_TYPE_ORDER: types,
  PROXY_SETS: { NO_SERVER, SHOW_NET, SHOW_SMUX, SHOW_TLS, SHOW_HYTLS },
  PROXY_FEATURES,
  PROXY_BASE_FIELDS: PROXY_BASE_FIELDS.map((f) => norm(f)),
  PROXY_NETWORKS: NET_TYPES,
  NET_OPTS_KEYS,
  PROXY_SECTION_TITLES,
  BUILTIN_POLICIES,
  GROUP_TYPES,
  GROUP_TEMPLATE,
  GROUP_HEALTH_TYPES,
  GROUP_SECTIONS,
  PROVIDER_TYPES: SUB_BASIC.find((f) => f.path === 'type').options,
  RULE_PROVIDER_TYPES: EP_FIELDS.find((f) => f.path === 'type').options,
  PROVIDER_DEFAULT: SUB_DEFAULT,
  RULE_PROVIDER_DEFAULT: EP_DEFAULT,
  FILE_HIDDEN_KEYS,
  HC_DEFAULTS,
  EXPR_PRESETS,
  PROXY_PROVIDER_SECTIONS,
  RULE_PROVIDER_SECTIONS,
  RULE_TYPES: RULE_TYPE_SPECS,
  TUNNEL_TEMPLATE,
  TUNNEL_SECTIONS,
  OVPN: { ciphers: OVPN_CIPHERS, auths: OVPN_AUTHS },
};
fs.writeFileSync(outPath, JSON.stringify(out, null, 1) + '\n');
console.log(`已写入 ${outPath}：${Object.keys(provenance).length} 张表，协议 ${types.length} 种，规则类型 ${RULE_TYPES.length} 种`);
