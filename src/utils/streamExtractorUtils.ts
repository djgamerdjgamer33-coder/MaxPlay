/**
 * Pure, testable stream extraction helpers shared across Server 1 (VOE),
 * Server 2 (Byse / Filemoon), and Server 3 (Streamtape).
 */

export const DEFAULT_BYSE_FAST_ORIGINS = [
  'https://filemoon.sx',
  'https://filemoon.to',
  'https://byseraguci.com',
];

export const DEFAULT_VOE_ACTIVE_ORIGINS = [
  'https://arianathankspecial.com',
  'https://susanexpectcare.com',
];

/**
 * Orders candidate Byse/Filemoon API origins so fast, zero-Cloudflare-timeout
 * origins (and any previously working cached origin) are attempted first.
 */
export function buildByseCandidateOrigins(
  sourceOrigin: string,
  cachedOrigin?: string,
  extraFastOrigins: string[] = DEFAULT_BYSE_FAST_ORIGINS
): string[] {
  const clean = (u?: string) => (u || '').trim().replace(/\/+$/, '');
  const list: string[] = [];
  const pushUnique = (origin: string) => {
    const c = clean(origin);
    if (!c || !/^https?:\/\//i.test(c)) return;
    if (!list.includes(c)) list.push(c);
  };

  // 1. Previously verified working origin for this source host goes first
  if (cachedOrigin) pushUnique(cachedOrigin);

  // 2. Direct Byse/Filemoon non-Cloudflare origins (consistently ~250-350ms)
  for (const fastOrigin of extraFastOrigins) {
    if (fastOrigin.includes('filemoon.sx') || fastOrigin.includes('filemoon.to')) {
      pushUnique(fastOrigin);
    }
  }

  // 3. Original URL origin
  if (sourceOrigin) pushUnique(sourceOrigin);

  // 4. Remaining fallback mirrors (e.g. byseraguci.com)
  for (const fastOrigin of extraFastOrigins) {
    pushUnique(fastOrigin);
  }

  return list;
}

/**
 * Extracts the alphanumeric video ID from a VOE or VOE-mirror URL.
 * Supports `/e/{code}`, `/{code}`, `/d/{code}`, and trailing slugs.
 */
export function extractVoeVideoCode(rawUrl: string): string {
  if (!rawUrl || typeof rawUrl !== 'string') return '';
  try {
    const parsed = new URL(rawUrl.trim());
    const segs = parsed.pathname.split('/').filter(Boolean);
    if (segs.length === 0) return '';
    if (segs.length >= 2 && ['e', 'd', 'f', 'v'].includes(segs[0].toLowerCase())) {
      const candidate = segs[1];
      return /^[a-z0-9]{6,24}$/i.test(candidate) ? candidate : '';
    }
    const first = segs[0];
    if (/^[a-z0-9]{6,24}$/i.test(first) && !first.includes('.')) {
      return first;
    }
    return '';
  } catch {
    return '';
  }
}

/**
 * Builds ordered VOE embed URLs to fetch. When the source URL points to `voe.sx`
 * (which frequently serves DDoS-Guard 403 to datacenter IPs), active mirror
 * origins are prioritized ahead of raw `voe.sx` so extraction completes in 1 hop.
 */
export function buildVoeCandidateUrls(
  rawUrl: string,
  activeOrigin?: string,
  fallbackOrigins: string[] = DEFAULT_VOE_ACTIVE_ORIGINS
): string[] {
  const trimmed = (rawUrl || '').trim();
  if (!trimmed) return [];

  const code = extractVoeVideoCode(trimmed);
  let normalizedOriginal = trimmed;
  let isMainVoeSx = false;
  try {
    const u = new URL(trimmed);
    const host = u.hostname.toLowerCase();
    isMainVoeSx = host === 'voe.sx' || host.endsWith('.voe.sx');
    if (code) {
      normalizedOriginal = `${u.protocol}//${u.host}/e/${code}`;
    }
  } catch {
    return [trimmed];
  }

  if (!code) return [normalizedOriginal];

  const urls: string[] = [];
  const pushUnique = (candidate: string) => {
    if (candidate && !urls.includes(candidate)) urls.push(candidate);
  };

  // If source is already a non-voe.sx mirror, try it first; otherwise prioritize active mirror origins
  if (!isMainVoeSx) {
    pushUnique(normalizedOriginal);
  }
  if (activeOrigin) {
    const cleanActive = activeOrigin.trim().replace(/\/+$/, '');
    if (/^https?:\/\//i.test(cleanActive)) {
      pushUnique(`${cleanActive}/e/${code}`);
    }
  }
  for (const fb of fallbackOrigins) {
    const cleanFb = fb.trim().replace(/\/+$/, '');
    if (/^https?:\/\//i.test(cleanFb)) {
      pushUnique(`${cleanFb}/e/${code}`);
    }
  }
  pushUnique(normalizedOriginal);

  return urls;
}

/**
 * Recursively evaluates Streamtape obfuscated JS string expressions such as:
 *   "//streamtape.com/get_" + ('xyzvideo?id=123&token=abc').substring(3)
 *   (('abc' + 'def').substring(1)).slice(0, 4)
 */
export function evaluateStreamtapeJsExpr(rawExpr: string): string {
  if (!rawExpr || typeof rawExpr !== 'string') return '';

  const splitTopLevelPlus = (expr: string): string[] => {
    const parts: string[] = [];
    let current = '';
    let depth = 0;
    let quote: string | null = null;
    let escaped = false;
    for (let i = 0; i < expr.length; i++) {
      const ch = expr[i];
      if (escaped) {
        current += ch;
        escaped = false;
        continue;
      }
      if (quote) {
        current += ch;
        if (ch === '\\') escaped = true;
        else if (ch === quote) quote = null;
        continue;
      }
      if (ch === "'" || ch === '"') {
        quote = ch;
        current += ch;
      } else if (ch === '(') {
        depth++;
        current += ch;
      } else if (ch === ')') {
        if (depth > 0) depth--;
        current += ch;
      } else if (ch === '+' && depth === 0) {
        parts.push(current.trim());
        current = '';
      } else {
        current += ch;
      }
    }
    if (current.trim()) parts.push(current.trim());
    return parts;
  };

  const hasMatchingOuterParens = (s: string): boolean => {
    if (!s.startsWith('(') || !s.endsWith(')')) return false;
    let depth = 0;
    let quote: string | null = null;
    let escaped = false;
    for (let i = 0; i < s.length; i++) {
      const ch = s[i];
      if (escaped) {
        escaped = false;
        continue;
      }
      if (quote) {
        if (ch === '\\') escaped = true;
        else if (ch === quote) quote = null;
        continue;
      }
      if (ch === "'" || ch === '"') quote = ch;
      else if (ch === '(') depth++;
      else if (ch === ')') {
        depth--;
        if (depth === 0 && i < s.length - 1) return false;
      }
    }
    return depth === 0;
  };

  const evalTerm = (term: string, recursionDepth: number = 0): string => {
    if (recursionDepth > 20) return '';
    let s = term.trim();
    if (!s) return '';

    const methodChain: Array<{ name: string; start: number; end?: number }> = [];
    while (true) {
      const m = s.match(/^(.*)\.(substring|substr|slice)\(\s*(\d+)\s*(?:,\s*(\d+)\s*)?\)\s*$/s);
      if (!m) break;
      s = m[1].trim();
      methodChain.unshift({
        name: m[2],
        start: parseInt(m[3], 10),
        end: m[4] !== undefined ? parseInt(m[4], 10) : undefined,
      });
    }

    let baseVal = '';
    if (hasMatchingOuterParens(s)) {
      baseVal = evaluateStreamtapeJsExpr(s.slice(1, -1));
    } else if (
      (s.startsWith("'") && s.endsWith("'")) ||
      (s.startsWith('"') && s.endsWith('"'))
    ) {
      baseVal = s
        .slice(1, -1)
        .replace(/\\\//g, '/')
        .replace(/\\'/g, "'")
        .replace(/\\"/g, '"')
        .replace(/\\x([0-9a-fA-F]{2})/g, (_, h) => String.fromCharCode(parseInt(h, 16)))
        .replace(/\\u([0-9a-fA-F]{4})/g, (_, h) => String.fromCharCode(parseInt(h, 16)));
    } else if (splitTopLevelPlus(s).length > 1) {
      baseVal = evaluateStreamtapeJsExpr(s);
    } else {
      return '';
    }

    for (const op of methodChain) {
      if (op.name === 'substr') {
        baseVal =
          op.end !== undefined ? baseVal.substr(op.start, op.end) : baseVal.substr(op.start);
      } else if (op.name === 'slice') {
        baseVal =
          op.end !== undefined ? baseVal.slice(op.start, op.end) : baseVal.slice(op.start);
      } else {
        baseVal =
          op.end !== undefined
            ? baseVal.substring(op.start, op.end)
            : baseVal.substring(op.start);
      }
    }
    return baseVal;
  };

  const topParts = splitTopLevelPlus(rawExpr.trim());
  if (topParts.length > 1) {
    return topParts.map((p) => evalTerm(p, 1)).join('');
  }
  return evalTerm(topParts[0] || rawExpr, 1);
}

/**
 * Normalizes an extracted Streamtape `get_video` string into a valid HTTPS URL with `stream=1`.
 */
export function normalizeStreamtapeDirectUrl(rawCandidate: string): string {
  let url = (rawCandidate || '').trim().replace(/^['"]|['"]$/g, '');
  if (!url || !url.includes('get_video')) return '';
  if (url.startsWith('//')) url = 'https:' + url;
  else if (url.startsWith('/streamtape.com')) url = 'https:/' + url;
  else if (
    url.startsWith('streamtape.com') ||
    url.startsWith('strtape.') ||
    url.startsWith('streamta.pe')
  ) {
    url = 'https://' + url;
  } else if (url.startsWith('/get_video')) {
    url = 'https://streamtape.com' + url;
  } else if (!url.startsWith('http')) {
    const idx = url.indexOf('streamtape.');
    if (idx >= 0) url = 'https://' + url.slice(idx);
    else if (url.includes('/get_video')) {
      url = 'https://streamtape.com/get_video' + url.split('/get_video')[1];
    }
  }
  if (!url.startsWith('http')) return '';
  if (!url.includes('stream=1')) {
    url += (url.includes('?') ? '&' : '?') + 'stream=1';
  }
  return url;
}
