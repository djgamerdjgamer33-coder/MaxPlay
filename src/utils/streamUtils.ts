/**
 * Pure stream utility functions for URL sanitization, proxy unwrapping,
 * Byse/Filemoon PoW & AES key assembly, and HLS quality/language normalization.
 */
import * as crypto from 'crypto';

/**
 * Unwraps any known local or legacy proxy URL (`/proxy/m3u8?url=...`, `/proxy/ts?url=...`,
 * `/api/proxy?url=...`, `/api/proxy-m3u8?url=...`, `/api/proxy-ts?url=...`) into its raw target URL.
 */
export function unwrapStreamProxyUrl(url: string): string {
  if (!url || typeof url !== 'string') return '';
  let current = url.trim();

  for (let depth = 0; depth < 3; depth++) {
    const proxyMatch = current.match(
      /(?:\/api\/proxy(?:-m3u8|-ts)?|\/proxy\/(?:m3u8|ts)|\/proxy)\?(?:[^#]*&)?url=([^&#]+)/i
    );
    if (proxyMatch && proxyMatch[1]) {
      try {
        const decoded = decodeURIComponent(proxyMatch[1]);
        if (decoded && decoded !== current) {
          current = decoded.trim();
          continue;
        }
      } catch {
        break;
      }
    }
    break;
  }
  return current;
}

/**
 * Sanitizes stream URLs stored in Firestore or passed to the player/downloader.
 * Automatically unwraps dead HuggingFace / old Cloud Run proxy wrappers so the underlying
 * hoster or CDN stream URL can be resolved cleanly.
 */
export function sanitizeStreamUrl(url: string): string {
  if (!url || typeof url !== 'string') return '';
  let clean = url.trim();

  // Unwrap dead or external proxy wrappers (e.g., nawazkhan001-mw-hub-master.hf.space or run.app)
  if (
    clean.includes('/proxy/m3u8?url=') ||
    clean.includes('/proxy/ts?url=') ||
    clean.includes('/api/proxy?url=') ||
    clean.includes('nawazkhan001-mw-hub-master.hf.space')
  ) {
    const unwrapped = unwrapStreamProxyUrl(clean);
    if (unwrapped && unwrapped.startsWith('http')) {
      clean = unwrapped;
    }
  }

  try {
    const parsed = new URL(clean);
    parsed.searchParams.delete('autoPlay');
    parsed.searchParams.delete('autoplay');
    parsed.searchParams.delete('mute');
    parsed.searchParams.delete('muted');
    return parsed.toString();
  } catch {
    return clean
      .replace(/([?&])(autoPlay|autoplay|mute|muted)=[^&]*(&|$)/gi, '$1')
      .replace(/[?&]$/, '');
  }
}

/**
 * Checks whether a hostname or URL belongs to Byse / Filemoon SprintCDN edges.
 */
export function isSprintCdnUrl(urlOrHost: string): boolean {
  if (!urlOrHost) return false;
  const lower = urlOrHost.toLowerCase();
  return (
    lower.includes('sprintcdn') ||
    lower.includes('owphbf') ||
    lower.includes('r66nv9ed')
  );
}

/**
 * Checks whether a URL belongs to the Byse / Filemoon family of embed hosts.
 */
export function isByseFilemoonHost(urlOrHost: string): boolean {
  if (!urlOrHost) return false;
  const lower = urlOrHost.toLowerCase();
  return (
    lower.includes('filemoon.') ||
    lower.includes('byse') ||
    lower.includes('f16px.') ||
    lower.includes('n1mwq.')
  );
}

/**
 * Extracts the normalized Byse/Filemoon video code from an embed or watch URL.
 */
export function extractByseVideoCode(url: string): string | null {
  if (!url || typeof url !== 'string') return null;
  const match = url.match(/\/(?:e|d|v|epb|30s|download)\/([a-zA-Z0-9_-]+)/i);
  if (match && match[1]) return match[1];
  try {
    const parsed = new URL(url);
    const seg = parsed.pathname.split('/').filter(Boolean).pop();
    if (seg && /^[a-zA-Z0-9_-]{8,20}$/.test(seg)) return seg;
  } catch {
    // ignore
  }
  return null;
}

/**
 * Solves the SHA-256 Proof-of-Work challenge required by Byse `/api/videos/access/challenge`.
 */
export function solveBysePowNode(challengeBase64: string, difficulty: number): number {
  const fullBytes = Math.floor(difficulty / 8);
  const remBits = difficulty % 8;
  const mask = remBits > 0 ? (0xff << (8 - remBits)) & 0xff : 0;

  for (let nonce = 0; nonce < 50_000_000; nonce++) {
    const input = `${challengeBase64}${nonce}`;
    const hash = crypto.createHash('sha256').update(input).digest();
    let ok = true;
    for (let i = 0; i < fullBytes; i++) {
      if (hash[i] !== 0) {
        ok = false;
        break;
      }
    }
    if (ok && remBits > 0) {
      if ((hash[fullBytes] & mask) !== 0) ok = false;
    }
    if (ok) return nonce;
  }
  return 0;
}

/**
 * Computes file size in MB and estimated duration in seconds from Byse stream metadata.
 * Filters out the 0xFFFFFFFF (4294967295) uint32 placeholder returned by some Byse edges.
 */
export function computeByseStreamMetrics(
  sizeBytesRaw: number | undefined | null,
  bitrateKbpsRaw: number | undefined | null,
  requestedDurationSec = 0
): { sizeBytes: number; sizeMb: number; durationSec: number } {
  const validSizeBytes =
    typeof sizeBytesRaw === 'number' &&
    Number.isFinite(sizeBytesRaw) &&
    sizeBytesRaw > 0 &&
    sizeBytesRaw < 4294967295
      ? sizeBytesRaw
      : 0;

  const bitrateKbps =
    typeof bitrateKbpsRaw === 'number' && Number.isFinite(bitrateKbpsRaw) && bitrateKbpsRaw > 0
      ? bitrateKbpsRaw
      : 0;

  let durationSec = requestedDurationSec > 0 ? requestedDurationSec : 0;
  if (durationSec <= 0 && validSizeBytes > 0 && bitrateKbps > 0) {
    durationSec = Math.round((validSizeBytes * 8) / (bitrateKbps * 1000));
  }

  let sizeMb = 0;
  if (validSizeBytes > 0) {
    sizeMb = Math.max(1, Math.round(validSizeBytes / (1024 * 1024)));
  } else if (bitrateKbps > 0 && durationSec > 0) {
    sizeMb = Math.max(1, Math.round(((bitrateKbps * 1000 / 8) * durationSec) / (1024 * 1024)));
  }

  return { sizeBytes: validSizeBytes, sizeMb, durationSec };
}
