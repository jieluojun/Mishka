function bareMask(line) {
  const mask = new Array(line.length).fill(true);
  let q = null;
  for (let i = 0; i < line.length; i++) {
    const c = line[i];
    if (q) {
      mask[i] = false;
      if (c === q) { if (line[i + 1] === q) { mask[i + 1] = false; i++; } else q = null; }
    } else if (c === '"' || c === "'") { mask[i] = false; q = c; }
    else if (c === '#') { for (let j = i; j < line.length; j++) mask[j] = false; break; }
  }
  return mask;
}

function anchorsOnLine(line) {
  const mask = bareMask(line); const defs = []; const refs = [];
  for (const m of line.matchAll(/[&]([^\s,[\]{}#]+)/g)) if (mask[m.index]) defs.push(m[1]);
  for (const m of line.matchAll(/[*]([^\s,[\]{}#]+)/g)) if (mask[m.index]) refs.push(m[1]);
  return { defs, refs };
}

function scanAnchorGraph(text) {
  const lines = String(text || '').split('\n');
  const ctxOf = (i) => {
    const stack = [];
    let want = lines[i].match(/^\s*/)[0].length;
    for (let j = i; j >= 0 && stack.length < 4; j--) {
      const l = lines[j];
      if (!l.trim() || l.trim().startsWith('#')) continue;
      const ind = l.match(/^\s*/)[0].length;
      const nm = l.match(/^\s*(?:-\s+)?(?:name|"name"|'name')\s*:\s*([^\s,}]+)/);
      if (ind >= want && j !== i && nm) { stack.unshift(nm[1].replace(/^["']|["'],?$/g, '')); want = ind; continue; }
      const km = l.match(/^\s*(?:-\s+)?(?:&\S+\s+)?([^:#]+?)\s*:(\s|$)/);
      if (km && (ind < want || j === i)) { stack.unshift(km[1].trim().replace(/^["']|["']$/g, '')); want = Math.min(want, ind); if (ind === 0 && j !== i) break; if (ind === 0 && j === i) break; }
    }
    return stack.join(' → ') || '(顶层)';
  };
  const defs = new Map(); const danglers = [];
  const refsBy = new Map();
  lines.forEach((l, i) => {
    const a = anchorsOnLine(l);
    a.defs.forEach(n => { if (!defs.has(n)) defs.set(n, []); defs.get(n).push({ line: i + 1, path: ctxOf(i) }); });
    const isMerge = /^\s*<<\s*:/.test(l);
    a.refs.forEach(n => {
      if (!refsBy.has(n)) refsBy.set(n, []);
      refsBy.get(n).push({ line: i + 1, path: ctxOf(i), merge: isMerge });
    });
  });
  const anchors = [...defs.keys()].map(n => {
    const rs = refsBy.get(n) || [];
    return { name: n, defs: defs.get(n), refs: rs, mergeCnt: rs.filter(r => r.merge).length, aliasCnt: rs.filter(r => !r.merge).length };
  });
  [...refsBy.keys()].filter(n => !defs.has(n)).forEach(n => danglers.push({ name: n, refs: refsBy.get(n) }));
  return { anchors, danglers };
}


// ---- 驱动器：读取目录下所有 *.yaml，输出 { 文件名: 扫描结果 } 的 JSON ----
import { readFileSync, readdirSync } from 'node:fs';
const dir = process.argv[2];
const files = readdirSync(dir).filter((f) => f.endsWith('.yaml')).sort();
const out = {};
for (const f of files) out[f] = scanAnchorGraph(readFileSync(`${dir}/${f}`, 'utf8'));
process.stdout.write(JSON.stringify(out, null, 1));
