// 由 mihomo_box 模块 webroot/ui/js/core.js 抽取的「字段整理」参考实现（逐行原样；仅去掉 export 前缀 + 末尾统一导出）
const OFFICIAL_TOP_ORDER = [
  // 1. General & Ports & Inbound Access
  'mixed-port', 'port', 'socks-port', 'redir-port', 'tproxy-port', 'shadowsocks-port',
  'mode', 'log-level', 'ipv6', 'allow-lan', 'bind-address',
  'lan-allowed-ips', 'lan-disallowed-ips', 'authentication', 'skip-auth-prefixes',
  'unified-delay', 'tcp-concurrent', 'interface-name', 'routing-mark', 'inbound-tproxy-mark',
  'external-controller', 'external-ui', 'secret', 'external-controller-tls', 'external-controller-cors',
  'external-ui-url', 'external-ui-name',
  // 2. Geo & Models
  'geodata-mode', 'geodata-loader', 'geo-auto-update', 'geo-update-interval', 'geox-url', 'geo-custom-url',
  'lgbm-auto-update', 'lgbm-update-interval', 'lgbm-url',
  // 3. Profile & Global
  'find-process-mode', 'global-client-fingerprint', 'global-ua',
  'keep-alive-idle', 'keep-alive-interval', 'disable-keep-alive', 'profile',
  // 4. Sniffer
  'sniffer',
  // 5. Inbounds & Experimental
  'tun', 'ebpf', 'iptables', 'listeners', 'experimental',
  // 6. DNS & NTP
  'dns', 'ntp',
  // 7. Hosts & TLS
  'hosts', 'tls',
  // 8. Proxies, Groups & Providers
  'proxies', 'proxy-groups', 'proxy-providers',
  // 9. Rules & Providers
  'rule-providers', 'sub-rules', 'rules'
];

const OFFICIAL_INNER_ORDER = {
  'dns': [
    'enable', 'prefer-h3', 'listen', 'ipv6', 'use-hosts', 'use-system-hosts',
    'respect-rules', 'enhanced-mode', 'fake-ip-range', 'fake-ip-filter',
    'fake-ip-filter-mode', 'default-nameserver', 'nameserver', 'fallback',
    'fallback-filter', 'nameserver-policy', 'proxy-server-nameserver',
    'direct-nameserver', 'direct-nameserver-follow-policy'
  ],
  'fallback-filter': [
    'geoip', 'geoip-code', 'geosite', 'ipcidr', 'domain'
  ],
  'tun': [
    'enable', 'stack', 'device', 'auto-route', 'auto-redirect',
    'auto-detect-interface', 'dns-hijack', 'strict-route', 'mtu', 'gso',
    'gso-max-size', 'endpoint-independent-nat', 'udp-timeout', 'file-descriptor'
  ],
  'sniffer': [
    'enable', 'force-dns-mapping', 'parse-pure-ip', 'override-destination',
    'sniff', 'force-domain', 'skip-domain', 'sniff-dns-mapping', 'port-whitelist'
  ],
  'ebpf': [
    'redirect-to-tun', 'auto-redir', 'local', 'shared'
  ],
  'ntp': [
    'enable', 'server', 'port', 'interval', 'lookup-interface'
  ],
  'experimental': [
    'clash-core', 'v2ray-api', 'quic-go-disable-ecn', 'quic-go-disable-gso'
  ],
  'profile': [
    'store-selected', 'store-fake-ip'
  ],
  'proxy-providers': [
    'type', 'url', 'interval', 'proxy', 'path', 'header', 'format',
    'health-check', 'override', 'filter', 'exclude-filter', 'exclude-type'
  ],
  'proxy-providers-item': [
    'type', 'url', 'interval', 'proxy', 'path', 'header', 'format',
    'health-check', 'override', 'filter', 'exclude-filter', 'exclude-type'
  ],
  'health-check': [
    'enable', 'url', 'interval', 'timeout', 'lazy', 'expected-status'
  ],
  'proxy-groups': [
    'name', 'type', 'proxies', 'use', 'url', 'interval', 'timeout', 'tolerance',
    'lazy', 'expected-status', 'max-failed-times', 'hidden', 'icon',
    'uselightgbm', 'prefer-asn', 'include-all-providers', 'include-all-proxies', 'empty-fallback',
    'filter', 'exclude-filter', 'exclude-type', 'strategy', 'disable-udp',
    'interface-name', 'routing-mark'
  ],
  'proxy-groups-item': [
    'name', 'type', 'proxies', 'use', 'url', 'interval', 'timeout', 'tolerance',
    'lazy', 'expected-status', 'max-failed-times', 'hidden', 'icon',
    'uselightgbm', 'prefer-asn', 'include-all-providers', 'include-all-proxies', 'empty-fallback',
    'filter', 'exclude-filter', 'exclude-type', 'strategy', 'disable-udp',
    'interface-name', 'routing-mark'
  ],
  'rule-providers': [
    'type', 'behavior', 'url', 'path', 'interval', 'proxy', 'header', 'format'
  ],
  'rule-providers-item': [
    'type', 'behavior', 'url', 'path', 'interval', 'proxy', 'header', 'format'
  ],
  'proxies': [
    'name', 'type', 'server', 'port', 'password', 'uuid', 'cipher', 'alterId',
    'udp', 'tls', 'skip-cert-verify', 'servername', 'network',
    'ws-opts', 'grpc-opts', 'h2-opts', 'http-opts', 'reality-opts'
  ],
  'proxies-item': [
    'name', 'type', 'server', 'port', 'password', 'uuid', 'cipher', 'alterId',
    'udp', 'tls', 'skip-cert-verify', 'servername', 'network',
    'ws-opts', 'grpc-opts', 'h2-opts', 'http-opts', 'reality-opts'
  ]
};
function extractKeyFromLine(line) {
  const m = line.match(/^\s*(?:-\s+)?(?:(<<)|"([^"]+)"|'([^']+)'|([^:#\s]+))\s*:/);
  if (!m) return null;
  return m[1] || m[2] || m[3] || m[4] || null;
}

function trimLines(lines) {
  let s = 0;
  while (s < lines.length && !lines[s].trim()) s++;
  let e = lines.length - 1;
  while (e >= s && !lines[e].trim()) e--;
  return lines.slice(s, e + 1);
}
const MERGE_AFTER_NAME_RANK = 0.5;
const entryRankOf = (key, order, mergeAfterName) =>
  key === '<<' ? (mergeAfterName ? MERGE_AFTER_NAME_RANK : -1) : (order && order.indexOf(key) >= 0 ? order.indexOf(key) : 999);
function reorderFlowObject(flowStr, order, mergeAfterName) {
  const m = String(flowStr).match(/^(\s*(?:[^:#\r\n]+:\s*)?(?:-\s+)?(?:&[^\s]+\s+)?)\{(.*)\}(\s*(?:#.*)?)$/);
  if (!m) return flowStr;
  const prefix = m[1];
  const inner = m[2];
  const suffix = m[3];

  const segs = splitTopLevel(inner);
  const items = segs.map(seg => {
    const km = seg.match(/^\s*(<<|"?([A-Za-z0-9_.\-]+)"?)\s*:/);
    const key = km ? (km[1] === '<<' ? '<<' : (km[2] || km[1]).replace(/^"|"$/g, '')) : '';
    let rank = entryRankOf(key, order, mergeAfterName);

    let formattedSeg = seg;
    if (key && OFFICIAL_INNER_ORDER[key] && /:\s*\{.*\}$/.test(seg)) {
      formattedSeg = reorderFlowObject(seg, OFFICIAL_INNER_ORDER[key]);
    }
    return { key, seg: formattedSeg, rank };
  });

  items.sort((a, b) => a.rank - b.rank);
  const newInner = items.map(x => x.seg).join(', ');
  return prefix + '{' + (newInner ? ' ' + newInner + ' ' : '') + '}' + suffix;
}

function splitMappingEntries(lines, expectedIndent) {
  const entries = [];
  let curComments = [];
  let curKey = null;
  let curLines = [];

  for (let i = 0; i < lines.length; i++) {
    const l = lines[i];
    const trimmed = l.trim();
    if (!trimmed) {
      if (curKey) curLines.push(l);
      else curComments.push(l);
      continue;
    }

    const indent = l.match(/^(\s*)/)[1].length;
    if (trimmed.startsWith('#')) {
      if (curKey && indent > expectedIndent) {
        curLines.push(l);
      } else {
        curComments.push(l);
      }
      continue;
    }

    if (indent === expectedIndent) {
      const k = extractKeyFromLine(l);
      if (k !== null) {
        if (curKey) {
          entries.push({ key: curKey, lines: trimLines(curLines) });
        }
        curKey = k;
        curLines = [...curComments, l];
        curComments = [];
        continue;
      }
    }

    if (curKey) {
      curLines.push(l);
    } else {
      curComments.push(l);
    }
  }

  if (curKey) {
    entries.push({ key: curKey, lines: trimLines(curLines) });
  }

  return { entries, trailing: curComments };
}

function reorderSeqItem(lines, seqIndent, order, mergeAfterName) {
  let dashLineIdx = -1;
  for (let i = 0; i < lines.length; i++) {
    if (lines[i].trim() && !lines[i].trim().startsWith('#')) {
      dashLineIdx = i;
      break;
    }
  }
  if (dashLineIdx === -1) return lines;

  const itemComments = lines.slice(0, dashLineIdx);
  const itemBodyLines = lines.slice(dashLineIdx);

  const firstNonComment = itemBodyLines[0];
  if (/^\s*-\s+(?:&[^\s]+\s+)?\{.*\}\s*(?:#.*)?$/.test(firstNonComment)) {
    return itemComments.concat(itemBodyLines.map(l => {
      if (/^\s*-\s+(?:&[^\s]+\s+)?\{.*\}\s*(?:#.*)?$/.test(l)) {
        return reorderFlowObject(l, order, mergeAfterName);
      }
      return l;
    }));
  }

  const dashMatch = firstNonComment.match(/^(\s*-\s*(?:&[^\s]+\s*)?)(.*)$/);
  if (!dashMatch) return lines;

  const dashPrefix = dashMatch[1];
  const dashRest = dashMatch[2].trim();

  if (!dashRest) {
    const headerLine = firstNonComment;
    const bodyLines = itemBodyLines.slice(1);
    const firstField = bodyLines.find(l => l.trim() && !l.trim().startsWith('#'));
    if (!firstField) return lines;
    const fieldIndent = firstField.match(/^(\s*)/)[1].length;
    const { entries, trailing } = splitMappingEntries(bodyLines, fieldIndent);
    entries.sort((a, b) => entryRankOf(a.key, order, mergeAfterName) - entryRankOf(b.key, order, mergeAfterName));
    const newBody = entries.flatMap(e => e.lines).concat(trailing);
    return itemComments.concat([headerLine], newBody);
  }

  let spacesIndent = dashPrefix.replace(/-/g, ' ').length;
  if (itemBodyLines.length > 1) {
    const secondLine = itemBodyLines.slice(1).find(l => l.trim() && !l.trim().startsWith('#'));
    if (secondLine) {
      spacesIndent = secondLine.match(/^(\s*)/)[1].length;
    }
  }
  const spacesPrefix = ' '.repeat(spacesIndent);

  const convertedLines = [spacesPrefix + dashRest].concat(itemBodyLines.slice(1));
  const { entries, trailing } = splitMappingEntries(convertedLines, spacesIndent);
  if (entries.length === 0) return lines;

  entries.sort((a, b) => entryRankOf(a.key, order, mergeAfterName) - entryRankOf(b.key, order, mergeAfterName));

  const result = [];
  let isFirstEntry = true;
  for (let i = 0; i < entries.length; i++) {
    const entry = entries[i];
    if (isFirstEntry) {
      let keyLineFound = false;
      for (let j = 0; j < entry.lines.length; j++) {
        const l = entry.lines[j];
        if (!keyLineFound && l.startsWith(spacesPrefix)) {
          result.push(dashPrefix + l.slice(spacesPrefix.length));
          keyLineFound = true;
        } else {
          result.push(l);
        }
      }
      isFirstEntry = false;
    } else {
      result.push(...entry.lines);
    }
  }
  result.push(...trailing);
  return itemComments.concat(result);
}

function splitSequenceItems(lines, seqIndent) {
  const items = [];
  let curComments = [];
  let curItemLines = null;

  for (let i = 0; i < lines.length; i++) {
    const l = lines[i];
    const trimmed = l.trim();
    if (!trimmed) {
      if (curItemLines) curItemLines.push(l);
      else curComments.push(l);
      continue;
    }

    const indent = l.match(/^(\s*)/)[1].length;
    if (trimmed.startsWith('#')) {
      if (curItemLines && indent > seqIndent) {
        curItemLines.push(l);
      } else {
        curComments.push(l);
      }
      continue;
    }

    const isSeqStart = (indent === seqIndent && /^-\s*(?:.*)?$/.test(l.slice(indent)));
    if (isSeqStart) {
      if (curItemLines) {
        items.push(trimLines(curItemLines));
      }
      curItemLines = [...curComments, l];
      curComments = [];
      continue;
    }

    if (curItemLines) {
      curItemLines.push(l);
    } else {
      curComments.push(l);
    }
  }

  if (curItemLines) {
    items.push(trimLines(curItemLines));
  }

  return { items, trailing: curComments };
}

function reorderMappingBlock(lines, expectedIndent, order) {
  const { entries, trailing } = splitMappingEntries(lines, expectedIndent);
  if (entries.length === 0) return lines;

  // 映射型条目（规则集/代理集等）：`<<` 维持置顶（仅 proxy-groups 序列条目排到 name 下方）
  entries.sort((a, b) => entryRankOf(a.key, order) - entryRankOf(b.key, order));

  entries.forEach(entry => {
    if (OFFICIAL_INNER_ORDER[entry.key]) {
      const subOrder = OFFICIAL_INNER_ORDER[entry.key];
      let klIdx = -1;
      for (let i = 0; i < entry.lines.length; i++) {
        if (!entry.lines[i].trim().startsWith('#') && extractKeyFromLine(entry.lines[i]) === entry.key) {
          klIdx = i;
          break;
        }
      }
      if (klIdx >= 0) {
        const kl = entry.lines[klIdx];
        if (/:\s*\{.*\}\s*(?:#.*)?$/.test(kl)) {
          entry.lines[klIdx] = reorderFlowObject(kl, subOrder);
        } else {
          const subLines = entry.lines.slice(klIdx + 1);
          const firstSub = subLines.find(l => l.trim() && !l.trim().startsWith('#'));
          if (firstSub) {
            const subIndent = firstSub.match(/^(\s*)/)[1].length;
            if (subIndent > expectedIndent) {
              const reorderedSub = reorderMappingBlock(subLines, subIndent, subOrder);
              entry.lines = entry.lines.slice(0, klIdx + 1).concat(reorderedSub);
            }
          }
        }
      }
    }
  });

  return entries.flatMap(e => e.lines).concat(trailing);
}

function reorderSectionLines(key, lines) {
  if (lines.length === 0) return lines;
  let klIdx = -1;
  for (let i = 0; i < lines.length; i++) {
    if (!lines[i].trim().startsWith('#') && extractKeyFromLine(lines[i]) === key) {
      klIdx = i;
      break;
    }
  }
  if (klIdx === -1) return lines;

  const headerLines = lines.slice(0, klIdx + 1);
  const keyLine = lines[klIdx];

  if (/:\s*\{.*\}\s*(?:#.*)?$/.test(keyLine) && OFFICIAL_INNER_ORDER[key]) {
    headerLines[headerLines.length - 1] = reorderFlowObject(keyLine, OFFICIAL_INNER_ORDER[key]);
    return headerLines.concat(lines.slice(klIdx + 1));
  }

  const bodyLines = lines.slice(klIdx + 1);
  if (bodyLines.length === 0) return lines;

  const firstContent = bodyLines.find(l => l.trim() && !l.trim().startsWith('#'));
  if (!firstContent) return lines;
  const childIndent = firstContent.match(/^(\s*)/)[1].length;

  if (key === 'proxy-groups' || key === 'proxies') {
    const itemOrder = key === 'proxy-groups' ? OFFICIAL_INNER_ORDER['proxy-groups-item'] : OFFICIAL_INNER_ORDER['proxies-item'];
    const { items, trailing } = splitSequenceItems(bodyLines, childIndent);
    // 仅代理组：条目内 `<<: *锚点` 排在 name 正下方；proxies 等其他段维持置顶
    const mergeAfterName = key === 'proxy-groups';
    const reorderedItems = items.map(it => reorderSeqItem(it, childIndent, itemOrder, mergeAfterName));
    return headerLines.concat(reorderedItems.flatMap(it => it)).concat(trailing);
  }

  if (key === 'proxy-providers' || key === 'rule-providers') {
    const itemOrder = key === 'proxy-providers' ? OFFICIAL_INNER_ORDER['proxy-providers-item'] : OFFICIAL_INNER_ORDER['rule-providers-item'];
    const { entries, trailing } = splitMappingEntries(bodyLines, childIndent);
    entries.forEach(entry => {
      let eKlIdx = -1;
      for (let i = 0; i < entry.lines.length; i++) {
        if (!entry.lines[i].trim().startsWith('#') && extractKeyFromLine(entry.lines[i]) === entry.key) {
          eKlIdx = i;
          break;
        }
      }
      if (eKlIdx >= 0) {
        const kl = entry.lines[eKlIdx];
        if (/:\s*\{.*\}\s*(?:#.*)?$/.test(kl)) {
          entry.lines[eKlIdx] = reorderFlowObject(kl, itemOrder);
        } else {
          const subLines = entry.lines.slice(eKlIdx + 1);
          const firstSub = subLines.find(l => l.trim() && !l.trim().startsWith('#'));
          if (firstSub) {
            const subIndent = firstSub.match(/^(\s*)/)[1].length;
            const reorderedSub = reorderMappingBlock(subLines, subIndent, itemOrder);
            entry.lines = entry.lines.slice(0, eKlIdx + 1).concat(reorderedSub);
          }
        }
      }
    });
    return headerLines.concat(entries.flatMap(e => e.lines)).concat(trailing);
  }

  if (OFFICIAL_INNER_ORDER[key]) {
    const reorderedBody = reorderMappingBlock(bodyLines, childIndent, OFFICIAL_INNER_ORDER[key]);
    return headerLines.concat(reorderedBody);
  }

  return lines;
}

function splitLeadingComments(rawComments) {
  const comments = trimLines(rawComments);
  if (comments.length === 0) return { header: [], keyComments: [] };

  let splitIdx = -1;
  for (let i = 0; i < comments.length; i++) {
    if (!comments[i].trim()) splitIdx = i;
  }
  if (splitIdx >= 0) {
    const header = trimLines(comments.slice(0, splitIdx));
    const keyComments = trimLines(comments.slice(splitIdx + 1));
    if (header.length > 0 && keyComments.length > 0) {
      return { header, keyComments };
    }
  }
  return { header: [], keyComments: comments };
}

function tidyMihomoConfig(text) {
  const lines = String(text || '').split('\n');
  const topBlocks = [];
  let fileHeader = [];
  let curComments = [];
  let curKey = null;
  let curLines = [];

  for (let i = 0; i < lines.length; i++) {
    const l = lines[i];
    const trimmed = l.trim();
    if (!trimmed) {
      if (curKey) curLines.push(l);
      else curComments.push(l);
      continue;
    }

    const indent = l.match(/^(\s*)/)[1].length;
    if (trimmed.startsWith('#')) {
      if (curKey && indent > 0) {
        curLines.push(l);
      } else {
        curComments.push(l);
      }
      continue;
    }

    if (indent === 0) {
      const k = extractKeyFromLine(l);
      if (k !== null) {
        if (curKey) {
          topBlocks.push({ key: curKey, lines: trimLines(curLines) });
          curLines = [...curComments, l];
          curComments = [];
        } else {
          const split = splitLeadingComments(curComments);
          fileHeader = split.header;
          curLines = [...split.keyComments, l];
          curComments = [];
        }
        curKey = k;
        continue;
      }
    }

    if (curKey) {
      curLines.push(l);
    } else {
      curComments.push(l);
    }
  }

  if (curKey) {
    topBlocks.push({ key: curKey, lines: trimLines(curLines) });
  }

  let fileFooter = trimLines(curComments);

  topBlocks.forEach(b => {
    b.lines = reorderSectionLines(b.key, b.lines);
  });

  // 是否「锚点定义块」：顶层自定义键且键行带 &锚点（官方顶层键不算）
  topBlocks.forEach(b => {
    const keyLine = b.lines.find(l => !l.trim().startsWith('#') && extractKeyFromLine(l) === b.key) || b.lines[0];
    b.isAnchor = /&[^\s]/.test(keyLine) && OFFICIAL_TOP_ORDER.indexOf(b.key) === -1;
  });

  // Sort topBlocks: anchors stay before usages or in place
  topBlocks.sort((a, b) => {
    const isAnchorA = a.isAnchor, isAnchorB = b.isAnchor;
    if (isAnchorA && !isAnchorB) return -1;
    if (!isAnchorA && isAnchorB) return 1;

    let idxA = OFFICIAL_TOP_ORDER.indexOf(a.key);
    let idxB = OFFICIAL_TOP_ORDER.indexOf(b.key);
    let rankA = idxA >= 0 ? idxA : 999;
    let rankB = idxB >= 0 ? idxB : 999;
    return rankA - rankB;
  });

  const outLines = [];
  if (fileHeader.length > 0) {
    outLines.push(...fileHeader, '');
  }

  let prevWasMulti = false;
  for (let i = 0; i < topBlocks.length; i++) {
    const b = topBlocks[i];
    const isMulti = b.lines.length > 1;
    // 锚点区与其后的普通配置之间固定空一行：锚点常写成单行流式，没有注释时
    // 整块都是单行，不单独判断就会和下面的 mixed-port 等连成一片。
    const leavingAnchors = i > 0 && topBlocks[i - 1].isAnchor && !b.isAnchor;
    if (i > 0 && (isMulti || prevWasMulti || leavingAnchors)) {
      outLines.push('');
    }
    outLines.push(...b.lines);
    prevWasMulti = isMulti;
  }

  if (fileFooter.length > 0) {
    outLines.push('', ...fileFooter);
  }
  // 保留原文件是否有结尾换行的习惯，不额外多加空行（用户要求：整理后底部不加空行）
  const hasTrailingNl = /\n$/.test(text);
  return outLines.join('\n') + (hasTrailingNl ? '\n' : '');
}
function splitTopLevel(body) {
  const segs = []; let depth = 0; let q = null; let cur = '';
  for (let i = 0; i < body.length; i++) {
    const c = body[i];
    if (q) { cur += c; if (c === q) { if (body[i + 1] === q) { cur += body[++i]; } else q = null; } continue; }
    if (c === '"' || c === "'") { q = c; cur += c; continue; }
    if (c === '{' || c === '[') depth++;
    if (c === '}' || c === ']') depth--;
    if (c === ',' && depth === 0) { segs.push(cur); cur = ''; continue; }
    cur += c;
  }
  if (cur.trim()) segs.push(cur);
  return segs.map(s => s.trim()).filter(Boolean);
}
export { tidyMihomoConfig, OFFICIAL_TOP_ORDER, OFFICIAL_INNER_ORDER };
