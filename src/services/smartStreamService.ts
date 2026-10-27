/**
 * MaxPlay Smart Stream Service
 * 
 * Handles client-side resolution and caching for dynamically extracted HLS streams.
 * Prevents token expiration (3-hour expiry limit) by managing automated extraction
 * and local caching without exposing secret master credentials in the browser.
 */

import { NativeBridgeService } from "./nativeBridgeService";

export interface CachedStreamMetadata {
  qualities?: string[];
  qualityLinks?: Record<string, string>;
  rawM3u8Url?: string;
  proxiedM3u8Url?: string;
  downloadQualities?: {
    id: string;
    label: string;
    sizeMb: number;
    url?: string;
    bandwidth?: number;
  }[];
  audioTracks?: string[];
  duration?: number;
  headers?: Record<string, string>;
  referer?: string;
}

interface ClientCachedStream {
  m3u8Url: string;
  expiresAt: number;
  cachedAt: number;
  meta?: CachedStreamMetadata;
}

const CACHE_PREFIX = "maxplay_hls_stream_v2_";

export class SmartStreamService {
  /**
   * Detects if a URL is an extractable source (e.g. VOE or external streaming host)
   * vs a direct mp4/m3u8 link.
   */
  public static isExtractableSource(url?: string, isSmartHls?: boolean): boolean {
    if (!url || typeof url !== "string") return false;
    const lower = url.toLowerCase().trim();

    // Already a direct HLS master/media stream, MP4, blob, or MaxPlay backend proxy
    if (
      lower.includes("/proxy/m3u8") ||
      lower.includes(".m3u8") ||
      lower.includes(".mp4") ||
      lower.startsWith("blob:")
    ) {
      return false;
    }

    // Recognised video hosters that require extraction (Voe, Filemoon, Byse, StreamWish, VidHide, etc.)
    if (
      lower.includes("voe.sx") ||
      lower.includes("voe.") ||
      lower.includes("voe-network") ||
      lower.includes("cloudwindow-route") ||
      lower.includes("reputationsickler") ||
      lower.includes("turtledoveconsequent") ||
      lower.includes("launchreliantcleaverriver") ||
      lower.includes("fraudclatterflyingcar") ||
      lower.includes("321naturelikefur") ||
      lower.includes("ralphysuccessfull") ||
      lower.includes("node=h0ke") ||
      lower.includes("filemoon.") ||
      lower.includes("moonmov.") ||
      lower.includes("kerapoxy.") ||
      lower.includes("bysedikamoum.") ||
      lower.includes("byseraguci.") ||
      lower.includes("byse") ||
      lower.includes("n1mwq.") ||
      lower.includes("f16px.") ||
      lower.includes("1azayf9w.") ||
      lower.includes("8mhlloqo.") ||
      lower.includes("96ar.com") ||
      lower.includes("xcoic.com") ||
      lower.includes("l1afav.net") ||
      lower.includes("streamwish.") ||
      lower.includes("hlswish.") ||
      lower.includes("playerwish.") ||
      lower.includes("vidhide.") ||
      lower.includes("dood.") ||
      lower.includes("streamtape.") ||
      /https?:\/\/[^\/]+\/(?:e|d|bkg)\/[a-z0-9_-]{6,}/i.test(lower)
    ) {
      return true;
    }

    if (isSmartHls && !lower.includes(".m3u8") && !lower.includes(".mp4")) {
      return true;
    }

    return false;
  }

  /**
   * Helper to retrieve iframe embed URL for external hosters like VOE / Filemoon / Byse.
   */
  public static getEmbedUrl(sourceUrl?: string): string | null {
    if (!sourceUrl || typeof sourceUrl !== "string") return null;
    const trimmed = sourceUrl.trim();
    const lower = trimmed.toLowerCase();

    if (
      lower.includes("filemoon.") ||
      lower.includes("moonmov.") ||
      lower.includes("kerapoxy.") ||
      lower.includes("bysedikamoum.") ||
      lower.includes("byseraguci.") ||
      lower.includes("byse") ||
      lower.includes("n1mwq.") ||
      lower.includes("f16px.") ||
      lower.includes("1azayf9w.") ||
      lower.includes("8mhlloqo.") ||
      lower.includes("96ar.com") ||
      lower.includes("xcoic.com") ||
      lower.includes("l1afav.net")
    ) {
      return trimmed.replace(/\/d\//i, "/e/");
    }

    if (
      lower.includes("voe.sx") ||
      lower.includes("voe.") ||
      lower.includes("voe-network") ||
      lower.includes("cloudwindow-route") ||
      lower.includes("reputationsickler") ||
      lower.includes("turtledoveconsequent") ||
      lower.includes("launchreliantcleaverriver") ||
      lower.includes("fraudclatterflyingcar") ||
      lower.includes("321naturelikefur") ||
      lower.includes("ralphysuccessfull")
    ) {
      if (trimmed.includes("/e/")) return trimmed;
      const match = trimmed.match(/https?:\/\/[^\/]+\/([a-zA-Z0-9_-]+)/);
      if (match && match[1]) {
        const host = trimmed.split("/")[2];
        return `https://${host}/e/${match[1]}`;
      }
      return trimmed;
    }

    if (lower.includes("youtube.com/watch?v=")) {
      const v = new URL(trimmed).searchParams.get("v");
      return `https://www.youtube.com/embed/${v}?autoplay=1&rel=0`;
    }
    if (lower.includes("youtu.be/")) {
      const v = trimmed.split("youtu.be/")[1].split("?")[0];
      return `https://www.youtube.com/embed/${v}?autoplay=1&rel=0`;
    }
    if (lower.includes("drive.google.com/file/d/")) {
      return trimmed.replace("/view", "/preview");
    }
    if (lower.includes("vimeo.com/")) {
      const v = trimmed.split("vimeo.com/")[1].split("?")[0];
      if (!isNaN(Number(v))) {
        return `https://player.vimeo.com/video/${v}?autoplay=1`;
      }
    }
    if (lower.includes("dailymotion.com/video/")) {
      const v = trimmed.split("video/")[1].split("?")[0];
      return `https://www.dailymotion.com/embed/video/${v}?autoplay=1`;
    }
    if (lower.includes("/embed/") || lower.includes("/iframe/")) {
      return trimmed;
    }

    return null;
  }

  /**
   * Sanitizes a raw stream URL, stripping accidental labels like " 1080p" or "1080p: "
   * while preserving valid URLs, blob:, filesystem:, and relative API paths.
   */
  public static sanitizeStreamUrl(rawUrl?: string | null): string {
    if (!rawUrl || typeof rawUrl !== "string") return "";
    let trimmed = rawUrl.trim();
    if (!trimmed) return "";

    // Unwrap legacy external proxy URLs (e.g. nawazkhan001-mw-hub-master.hf.space or /proxy/ts?url=...)
    // Only unwrap /proxy/m3u8?url= inside the native Kotlin app where WebView intercepts headers directly;
    // in web browsers, /proxy/m3u8?url= MUST remain wrapped so SprintCDN/Byse requests include Referer & server ASN!
    const isKotlinApp = NativeBridgeService.isRunningInKotlinApp();
    for (let depth = 0; depth < 3; depth++) {
      if (
        trimmed.includes("nawazkhan001-mw-hub-master.hf.space") ||
        trimmed.includes("/proxy/ts?url=") ||
        (isKotlinApp && trimmed.includes("/proxy/m3u8?url="))
      ) {
        const m = trimmed.match(/[?&]url=([^&#]+)/i);
        if (m && m[1]) {
          try {
            const dec = decodeURIComponent(m[1]).trim();
            if (dec && dec !== trimmed) {
              trimmed = dec;
              continue;
            }
          } catch {}
        }
      }
      break;
    }

    // In web browsers, normalize any http(s)://.../proxy/m3u8?url=... (except legacy hf.space) to relative /proxy/m3u8?url=...
    // so it never triggers Mixed Content (http:// on https://) or localhost:3000 connection errors!
    if (
      !isKotlinApp &&
      trimmed.includes("/proxy/m3u8?url=") &&
      !trimmed.includes("nawazkhan001-mw-hub-master.hf.space") &&
      !trimmed.startsWith("/")
    ) {
      const idx = trimmed.indexOf("/proxy/m3u8?url=");
      if (idx !== -1) {
        return trimmed.slice(idx).split(/\s+/)[0];
      }
    }

    if (
      trimmed.startsWith("blob:") ||
      trimmed.startsWith("filesystem:") ||
      trimmed.startsWith("data:") ||
      trimmed.startsWith("/")
    ) {
      return trimmed;
    }
    const httpMatch = trimmed.match(/https?:\/\/[^\s"'<>]+/i);
    if (httpMatch && httpMatch[0]) {
      return httpMatch[0].trim();
    }
    return trimmed.split(/\s+/)[0];
  }

  /**
   * Generates a collision-free cache key for a source URL (including hash + tail of URL).
   */
  private static getCacheKey(sourceUrl: string): string {
    const clean = this.sanitizeStreamUrl(sourceUrl) || sourceUrl.trim();
    let h1 = 0x811c9dc5;
    let h2 = 0x01000193;
    for (let i = 0; i < clean.length; i++) {
      const c = clean.charCodeAt(i);
      h1 ^= c;
      h1 = Math.imul(h1, 0x01000193) >>> 0;
      h2 ^= c;
      h2 = Math.imul(h2, 0x85ebca6b) >>> 0;
    }
    const tail = clean.replace(/[^a-zA-Z0-9]/g, "").slice(-24);
    return `${CACHE_PREFIX}v2_${h1.toString(36)}_${h2.toString(36)}_${tail}`;
  }

  /**
   * Retrieves client-side cached stream URL if still valid.
   */
  private static getClientCache(sourceUrl: string): ClientCachedStream | null {
    try {
      const cleanUrl = this.sanitizeStreamUrl(sourceUrl);
      if (!cleanUrl) return null;
      const key = this.getCacheKey(cleanUrl);
      const raw = sessionStorage.getItem(key) || localStorage.getItem(key);
      if (!raw) return null;

      const data: ClientCachedStream = JSON.parse(raw);
      const now = Date.now();

      // In Kotlin native app, never reuse old server-proxied (/proxy/m3u8) URLs; always use direct .m3u8
      if (
        NativeBridgeService.isRunningInKotlinApp() &&
        data?.m3u8Url &&
        data.m3u8Url.includes("/proxy/m3u8")
      ) {
        this.clearClientCache(cleanUrl);
        return null;
      }

      // Ensure at least 5 minutes buffer before expiry
      if (data && data.m3u8Url && data.expiresAt > now + 5 * 60 * 1000) {
        return data;
      }
      // Stale or expired
      this.clearClientCache(cleanUrl);
      return null;
    } catch {
      return null;
    }
  }

  private static streamHostRefererMap = new Map<string, string>();

  /**
   * Registers an explicit Referer for a resolved stream URL and its CDN host/root domain.
   */
  public static registerStreamHostReferer(streamUrl: string, refererUrl: string): void {
    if (!streamUrl || !refererUrl) return;
    try {
      const u = new URL(streamUrl);
      const host = u.hostname.toLowerCase();
      if (!host) return;
      this.streamHostRefererMap.set(host, refererUrl);
      const parts = host.split(".");
      if (parts.length >= 2) {
        const root = parts.slice(-2).join(".");
        this.streamHostRefererMap.set(root, refererUrl);
      }
    } catch {}
  }

  /**
   * Stores stream URL and optional extracted metadata in client session & local storage.
   */
  private static setClientCache(
    sourceUrl: string,
    m3u8Url: string,
    expiresAt: number,
    meta?: CachedStreamMetadata
  ): void {
    try {
      const cleanUrl = this.sanitizeStreamUrl(sourceUrl);
      if (!cleanUrl) return;
      const key = this.getCacheKey(cleanUrl);
      const existing = this.getClientCache(cleanUrl);
      const mergedMeta = meta || existing?.meta;
      const ref =
        mergedMeta?.referer ||
        mergedMeta?.headers?.Referer ||
        mergedMeta?.headers?.referer ||
        "";
      if (ref && m3u8Url) {
        this.registerStreamHostReferer(m3u8Url, ref);
      }
      const data: ClientCachedStream = {
        m3u8Url,
        expiresAt,
        cachedAt: Date.now(),
        meta: mergedMeta,
      };
      sessionStorage.setItem(key, JSON.stringify(data));
      localStorage.setItem(key, JSON.stringify(data));
    } catch (e) {
      console.warn("[SmartStreamService] Failed to write cache:", e);
    }
  }

  /**
   * Public synchronous lookup for already-resolved cached stream URL (0ms for Download Modal).
   */
  public static getCachedStreamUrl(sourceUrl: string): string | null {
    const cleanUrl = this.sanitizeStreamUrl(sourceUrl);
    if (!cleanUrl) return null;
    const cached = this.getClientCache(cleanUrl);
    return cached?.m3u8Url || null;
  }

  /**
   * Public synchronous lookup for already-parsed stream metadata (qualities, audioTracks, downloadQualities).
   */
  public static getCachedStreamMeta(sourceUrl: string): CachedStreamMetadata | null {
    const cleanUrl = this.sanitizeStreamUrl(sourceUrl);
    if (!cleanUrl) return null;
    const cached = this.getClientCache(cleanUrl);
    return cached?.meta || null;
  }

  /**
   * Resolves the required embed Referer header for Server 1 / Server 2 (Filemoon / Byse / Voe) segment & playlist fetches.
   */
  public static resolveEmbedReferer(sourceOrStreamUrl?: string): string {
    if (!sourceOrStreamUrl || typeof sourceOrStreamUrl !== "string") return "";
    const trimmed = sourceOrStreamUrl.trim();
    try {
      if (trimmed.includes("referer=")) {
        const u = new URL(trimmed, typeof window !== "undefined" ? window.location.origin : "https://maxplay.app");
        const refParam = u.searchParams.get("referer");
        if (refParam) return decodeURIComponent(refParam);
      }
    } catch {}

    const cachedMeta = this.getCachedStreamMeta(trimmed);
    if (cachedMeta?.referer) return cachedMeta.referer;
    if (cachedMeta?.headers?.Referer || cachedMeta?.headers?.referer) {
      return cachedMeta.headers.Referer || cachedMeta.headers.referer || "";
    }

    try {
      const parsedHost = new URL(trimmed).hostname.toLowerCase();
      if (parsedHost) {
        const directHostRef = this.streamHostRefererMap.get(parsedHost);
        if (directHostRef) return directHostRef;
        const parts = parsedHost.split(".");
        if (parts.length >= 2) {
          const root = parts.slice(-2).join(".");
          const rootRef = this.streamHostRefererMap.get(root);
          if (rootRef) return rootRef;
        }
      }
    } catch {}

    const lower = trimmed.toLowerCase();
    if (
      lower.includes("sprintcdn") ||
      lower.includes("owphbf") ||
      lower.includes("r66nv9ed") ||
      lower.includes("n1mwq") ||
      lower.includes("bysedikamoum") ||
      lower.includes("byseraguci") ||
      lower.includes("byse") ||
      lower.includes("filemoon")
    ) {
      return "https://byseraguci.com/";
    }
    if (
      lower.includes("f16px.") ||
      lower.includes("moonmov") ||
      lower.includes("kerapoxy") ||
      lower.includes("1azayf9w") ||
      lower.includes("8mhlloqo") ||
      lower.includes("96ar.com") ||
      lower.includes("xcoic.com") ||
      lower.includes("l1afav.net")
    ) {
      return "https://f16px.com/";
    }
    if (
      lower.includes("voe.") ||
      lower.includes("voe-network") ||
      lower.includes("reputationsickler") ||
      lower.includes("turtledoveconsequent") ||
      lower.includes("launchreliantcleaverriver") ||
      lower.includes("fraudclatterflyingcar") ||
      lower.includes("321naturelikefur") ||
      lower.includes("ralphysuccessfull")
    ) {
      return "https://voe.sx/";
    }
    try {
      if (/^https?:\/\//i.test(trimmed) && !lower.includes(".m3u8") && !lower.includes(".ts")) {
        const parsed = new URL(trimmed);
        return `${parsed.protocol}//${parsed.host}/`;
      }
    } catch {}
    return "";
  }

  /**
   * Updates cached stream metadata for a source URL (or direct m3u8 URL).
   */
  public static setCachedStreamMeta(sourceUrl: string, meta: CachedStreamMetadata): void {
    const cleanUrl = this.sanitizeStreamUrl(sourceUrl);
    if (!cleanUrl) return;
    const existing = this.getClientCache(cleanUrl);
    const m3u8Url = existing?.m3u8Url || cleanUrl;
    const expiresAt = existing?.expiresAt || Date.now() + 170 * 60 * 1000;
    this.setClientCache(cleanUrl, m3u8Url, expiresAt, {
      ...(existing?.meta || {}),
      ...meta,
    });
  }

  /**
   * Invalidate cached token for a source URL (e.g. when playback error happens).
   */
  public static clearClientCache(sourceUrl: string): void {
    try {
      const cleanUrl = this.sanitizeStreamUrl(sourceUrl);
      if (!cleanUrl) return;
      const key = this.getCacheKey(cleanUrl);
      sessionStorage.removeItem(key);
      localStorage.removeItem(key);
    } catch {
      // ignore
    }
  }

  private static inFlightMap = new Map<string, Promise<{
    streamUrl: string;
    isExtracted: boolean;
    cached: boolean;
    isEmbed?: boolean;
    expiresAt?: number;
  }>>();

  /**
   * Non-blocking background stream pre-warmer for instant playback and server switching.
   */
  public static prefetchStream(sourceUrl?: string): void {
    if (!sourceUrl || typeof sourceUrl !== "string") return;
    const trimmed = this.sanitizeStreamUrl(sourceUrl);
    if (!trimmed || !this.isExtractableSource(trimmed)) return;
    if (this.getClientCache(trimmed) || this.inFlightMap.has(trimmed)) return;
    this.resolveStream(trimmed, { forceRefresh: false }).catch(() => {});
  }

  /**
   * Resolves a video source URL into a playable Master m3u8 URL.
   * If the URL is already an m3u8 or mp4, it returns it directly.
   * If it is an extractable source (e.g. VOE / Filemoon), it uses the on-device scraper or backend proxy.
   */
  public static async resolveStream(
    sourceUrl: string,
    options: { forceRefresh?: boolean } = {}
  ): Promise<{
    streamUrl: string;
    isExtracted: boolean;
    cached: boolean;
    isEmbed?: boolean;
    expiresAt?: number;
  }> {
    const trimmed = this.sanitizeStreamUrl(sourceUrl);
    if (!trimmed) {
      throw new Error("Missing video source URL");
    }

    // Check if it's already a direct HLS master or MP4
    if (!this.isExtractableSource(trimmed)) {
      return {
        streamUrl: trimmed,
        isExtracted: false,
        cached: false,
        isEmbed: false
      };
    }

    // 1. Check client-side cache first
    if (!options.forceRefresh) {
      const cached = this.getClientCache(trimmed);
      if (cached) {
        return {
          streamUrl: cached.m3u8Url,
          isExtracted: true,
          cached: true,
          isEmbed: false,
          expiresAt: cached.expiresAt
        };
      }

      const existingInFlight = this.inFlightMap.get(trimmed);
      if (existingInFlight) {
        return existingInFlight;
      }
    }

    const taskPromise = this.resolveStreamInternal(trimmed, options);
    this.inFlightMap.set(trimmed, taskPromise);
    try {
      return await taskPromise;
    } finally {
      this.inFlightMap.delete(trimmed);
    }
  }

  private static byseClientAttestCache = new Map<string, { fingerprint: any; expiresAt: number }>();

  /**
   * Client-side WebCrypto Byse / Filemoon Direct API Extractor.
   * Calls `byseraguci.com` / `f16px.com` directly from the browser/WebView (CORS supported)
   * so that the decrypted SprintCDN `.m3u8` token is bound to the user's own IP & ASN.
   */
  public static async extractByseClientSide(
    sourceUrl: string,
    forcedQuality: string = "",
    requestedDurationSec: number = 0
  ): Promise<{
    ok: boolean;
    m3u8Url?: string;
    meta?: CachedStreamMetadata;
  }> {
    try {
      if (typeof window === "undefined" || !window.crypto || !window.crypto.subtle) {
        return { ok: false };
      }
      const lower = sourceUrl.toLowerCase();
      if (
        !lower.includes("byse") &&
        !lower.includes("filemoon") &&
        !lower.includes("f16px") &&
        !lower.includes("n1mwq") &&
        !lower.includes("moonmov") &&
        !lower.includes("kerapoxy")
      ) {
        return { ok: false };
      }

      const parsed = new URL(sourceUrl.trim());
      const origin = `${parsed.protocol}//${parsed.host}`;
      const pathParts = parsed.pathname.split("/").filter(Boolean);
      const code =
        pathParts.length >= 2 && ["d", "e", "g", "bkg", "wz28", "30s"].includes(pathParts[0].toLowerCase())
          ? pathParts[1]
          : pathParts[pathParts.length - 1];
      if (!code || code.length < 6) return { ok: false };

      const toB64Url = (bytes: Uint8Array): string => {
        let bin = "";
        for (let i = 0; i < bytes.length; i++) bin += String.fromCharCode(bytes[i]);
        return btoa(bin).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/g, "");
      };
      const fromB64Url = (s: string): Uint8Array => {
        let b64 = s.replace(/-/g, "+").replace(/_/g, "/");
        while (b64.length % 4 !== 0) b64 += "=";
        const bin = atob(b64);
        const out = new Uint8Array(bin.length);
        for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
        return out;
      };
      const sha256B64Url = async (str: string): Promise<string> => {
        const buf = await window.crypto.subtle.digest("SHA-256", new TextEncoder().encode(str));
        return toB64Url(new Uint8Array(buf));
      };
      const solveBysePow = (nonce: string, difficulty: number): string => {
        if (difficulty <= 0) return "0";
        const prefix = nonce + ":";
        let p0 = 1779033703, p1 = 3144134277, p2 = 1013904242, p3 = 2773480762;
        for (let i = 0; i < prefix.length; i++) {
          p0 = (p0 + (prefix.charCodeAt(i) & 255)) >>> 0;
          p0 = ((p0 << 7) | (p0 >>> 25)) >>> 0;
          p0 = (p0 + p1) >>> 0; p3 = (((p3 ^ p0) << 16) | ((p3 ^ p0) >>> 16)) >>> 0;
          p2 = (p2 + p3) >>> 0; p1 = (((p1 ^ p2) << 12) | ((p1 ^ p2) >>> 20)) >>> 0;
          p0 = (p0 + p1) >>> 0; p3 = (((p3 ^ p0) << 8) | ((p3 ^ p0) >>> 24)) >>> 0;
          p2 = (p2 + p3) >>> 0; p1 = (((p1 ^ p2) << 7) | ((p1 ^ p2) >>> 25)) >>> 0;
        }
        const r = new Uint32Array(512);
        const digits = new Uint8Array(12);
        for (let s = 0; s < 5000000; s++) {
          let e0 = p0, e1 = p1, e2 = p2, e3 = p3;
          let temp = s;
          let len = 0;
          if (temp === 0) {
            digits[0] = 48;
            len = 1;
          } else {
            while (temp > 0) {
              digits[len++] = 48 + (temp % 10);
              temp = (temp / 10) | 0;
            }
          }
          for (let i = len - 1; i >= 0; i--) {
            e0 = (e0 + digits[i]) >>> 0;
            e0 = ((e0 << 7) | (e0 >>> 25)) >>> 0;
            e0 = (e0 + e1) >>> 0; e3 = (((e3 ^ e0) << 16) | ((e3 ^ e0) >>> 16)) >>> 0;
            e2 = (e2 + e3) >>> 0; e1 = (((e1 ^ e2) << 12) | ((e1 ^ e2) >>> 20)) >>> 0;
            e0 = (e0 + e1) >>> 0; e3 = (((e3 ^ e0) << 8) | ((e3 ^ e0) >>> 24)) >>> 0;
            e2 = (e2 + e3) >>> 0; e1 = (((e1 ^ e2) << 7) | ((e1 ^ e2) >>> 25)) >>> 0;
          }
          for (let i = 0; i < 8; i++) {
            e0 = (e0 + e1) >>> 0; e3 = (((e3 ^ e0) << 16) | ((e3 ^ e0) >>> 16)) >>> 0;
            e2 = (e2 + e3) >>> 0; e1 = (((e1 ^ e2) << 12) | ((e1 ^ e2) >>> 20)) >>> 0;
            e0 = (e0 + e1) >>> 0; e3 = (((e3 ^ e0) << 8) | ((e3 ^ e0) >>> 24)) >>> 0;
            e2 = (e2 + e3) >>> 0; e1 = (((e1 ^ e2) << 7) | ((e1 ^ e2) >>> 25)) >>> 0;
          }
          for (let i = 0; i < 512; i++) {
            e0 = (e0 + e1) >>> 0; e3 = (((e3 ^ e0) << 16) | ((e3 ^ e0) >>> 16)) >>> 0;
            e2 = (e2 + e3) >>> 0; e1 = (((e1 ^ e2) << 12) | ((e1 ^ e2) >>> 20)) >>> 0;
            e0 = (e0 + e1) >>> 0; e3 = (((e3 ^ e0) << 8) | ((e3 ^ e0) >>> 24)) >>> 0;
            e2 = (e2 + e3) >>> 0; e1 = (((e1 ^ e2) << 7) | ((e1 ^ e2) >>> 25)) >>> 0;
            r[i] = (e0 ^ e2) >>> 0;
          }
          for (let i = 0; i < 2; i++) {
            for (let k = 0; k < 512; k++) {
              const a = r[k] & 511;
              let c = (r[k] + r[a]) >>> 0;
              c = ((c << 13) | (c >>> 19)) >>> 0;
              c = (c ^ (Math.imul(r[(k + 1) & 511], 2654435761) >>> 0)) >>> 0;
              r[k] = c;
              e0 = (e0 ^ c) >>> 0;
              e0 = (e0 + e1) >>> 0; e3 = (((e3 ^ e0) << 16) | ((e3 ^ e0) >>> 16)) >>> 0;
              e2 = (e2 + e3) >>> 0; e1 = (((e1 ^ e2) << 12) | ((e1 ^ e2) >>> 20)) >>> 0;
              e0 = (e0 + e1) >>> 0; e3 = (((e3 ^ e0) << 8) | ((e3 ^ e0) >>> 24)) >>> 0;
              e2 = (e2 + e3) >>> 0; e1 = (((e1 ^ e2) << 7) | ((e1 ^ e2) >>> 25)) >>> 0;
            }
          }
          e0 = (e0 + e1) >>> 0; e3 = (((e3 ^ e0) << 16) | ((e3 ^ e0) >>> 16)) >>> 0;
          e2 = (e2 + e3) >>> 0; e1 = (((e1 ^ e2) << 12) | ((e1 ^ e2) >>> 20)) >>> 0;
          e0 = (e0 + e1) >>> 0; e3 = (((e3 ^ e0) << 8) | ((e3 ^ e0) >>> 24)) >>> 0;
          e2 = (e2 + e3) >>> 0; e1 = (((e1 ^ e2) << 7) | ((e1 ^ e2) >>> 25)) >>> 0;
          let sWord = e0;
          for (let c = 0; c < 64; c++) {
            const d = r[c];
            sWord = (sWord + d) >>> 0;
            sWord = ((sWord << 5) | (sWord >>> 27)) >>> 0;
            sWord = (sWord ^ (Math.imul(d, 2246822519) >>> 0)) >>> 0;
          }
          if (Math.clz32((sWord ^ e2) >>> 0) >= difficulty) {
            return String(s);
          }
        }
        return "0";
      };

      let videoTitle = "";
      let defaultLangCode = "";
      try {
        const [detRes, setRes] = await Promise.all([
          fetch(`${origin}/api/videos/${code}/embed/details`).catch(() => null),
          fetch(`${origin}/api/videos/${code}/settings`).catch(() => null),
        ]);
        if (detRes && detRes.ok) {
          const det = await detRes.json().catch(() => ({}));
          videoTitle = det?.title || "";
        }
        if (setRes && setRes.ok) {
          const st = await setRes.json().catch(() => ({}));
          defaultLangCode = st?.default_language || "";
        }
      } catch {}

      const candidateOrigins = Array.from(new Set([origin, "https://byseraguci.com", "https://f16px.com"]));
      for (const apiOrigin of candidateOrigins) {
        try {
          let fingerprint = this.byseClientAttestCache.get(apiOrigin)?.fingerprint;
          if (!fingerprint || (this.byseClientAttestCache.get(apiOrigin)?.expiresAt || 0) <= Date.now()) {
            const keyPair = await window.crypto.subtle.generateKey(
              { name: "ECDSA", namedCurve: "P-256" },
              true,
              ["sign", "verify"]
            );
            const jwk = await window.crypto.subtle.exportKey("jwk", keyPair.publicKey);
            const publicJwk = { kty: "EC", crv: "P-256", x: jwk.x, y: jwk.y };

            const chRes = await fetch(`${apiOrigin}/api/videos/access/challenge`, { method: "POST" });
            if (!chRes.ok) continue;
            const chData = await chRes.json();
            const nonceStr = chData.nonce || chData.challenge || "";
            const sigBuf = await window.crypto.subtle.sign(
              { name: "ECDSA", hash: { name: "SHA-256" } },
              keyPair.privateKey,
              new TextEncoder().encode(nonceStr)
            );
            const signatureB64Url = toB64Url(new Uint8Array(sigBuf));
            const ua = navigator.userAgent;

            const client = {
              user_agent: ua,
              architecture: "x86",
              bitness: "64",
              platform: "Windows",
              platform_version: "15.0.0",
              model: "",
              ua_full_version: "124.0.6367.118",
              brand_full_versions: [
                { brand: "Chromium", version: "124.0.6367.118" },
                { brand: "Google Chrome", version: "124.0.6367.118" },
                { brand: "Not-A.Brand", version: "99.0.0.0" },
              ],
              pixel_ratio: window.devicePixelRatio || 1,
              screen_width: window.screen?.width || 1920,
              screen_height: window.screen?.height || 1080,
              color_depth: 24,
              languages: ["en-US", "en"],
              timezone: "Asia/Kolkata",
              hardware_concurrency: navigator.hardwareConcurrency || 8,
              device_memory: (navigator as any).deviceMemory || 8,
              touch_points: navigator.maxTouchPoints || 0,
              webgl_vendor: "Google Inc. (NVIDIA)",
              webgl_renderer: "ANGLE (NVIDIA, NVIDIA GeForce RTX 3060 Direct3D11 vs_5_0 ps_5_0, D3D11)",
              canvas_hash: await sha256B64Url("canvas-byse-access"),
              audio_hash: await sha256B64Url("audio-byse-access"),
              webgl_params_hash: await sha256B64Url("webgl-params"),
              fonts_hash: await sha256B64Url("Arial,Calibri,Consolas,Segoe UI,Tahoma,Times New Roman,Verdana"),
              codecs_hash: await sha256B64Url("codecs-v1"),
              media_devices: "ai1ao2vi1",
              pointer_type: "fine,hover",
              extra: { vendor: "Google Inc.", appVersion: ua.replace("Mozilla/", "") },
            };

            const attRes = await fetch(`${apiOrigin}/api/videos/access/attest`, {
              method: "POST",
              headers: { "Content-Type": "application/json" },
              body: JSON.stringify({
                viewer_id: "",
                device_id: "",
                challenge_id: chData.challenge_id,
                nonce: nonceStr,
                signature: signatureB64Url,
                public_key: publicJwk,
                client,
                storage: {},
                attributes: { entropy: "high" },
              }),
            });
            if (!attRes.ok) continue;
            const attData = await attRes.json();
            fingerprint = {
              token: attData.token || "",
              viewer_id: attData.viewer_id || "",
              device_id: attData.device_id || "",
              confidence: attData.confidence ?? 0.9,
            };
            this.byseClientAttestCache.set(apiOrigin, {
              fingerprint,
              expiresAt: Date.now() + 9 * 60 * 1000,
            });
          }

          let pbData: any = null;
          const capChRes = await fetch(`${apiOrigin}/api/videos/${code}/embed/captcha`, {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({ fingerprint }),
          }).catch(() => null);

          if (capChRes && capChRes.ok) {
            const capCh = await capChRes.json();
            const powNonce = capCh.pow_nonce || capCh.nonce;
            const powDiff = capCh.pow_difficulty || capCh.difficulty || 16;
            const powToken = capCh.pow_token || capCh.challenge_id;
            const solutionStr = solveBysePow(powNonce, powDiff);

            const capVerRes = await fetch(`${apiOrigin}/api/videos/${code}/embed/captcha/verify`, {
              method: "POST",
              headers: { "Content-Type": "application/json" },
              body: JSON.stringify({
                pow_token: powToken,
                solution: solutionStr,
                fingerprint,
              }),
            }).catch(() => null);

            if (capVerRes && capVerRes.ok) {
              const capVer = await capVerRes.json();
              const captchaToken = capVer.token || capVer.captcha_token || "";
              const pbRes = await fetch(`${apiOrigin}/api/videos/${code}/embed/playback`, {
                method: "POST",
                headers: {
                  "Content-Type": "application/json",
                  "X-Captcha-Token": captchaToken,
                },
                body: JSON.stringify({ fingerprint }),
              }).catch(() => null);
              if (pbRes && pbRes.ok) {
                pbData = await pbRes.json().catch(() => ({}));
              }
            }
          }

          if (pbData?.playback) {
            const pb = pbData.playback;
            const decryptAesGcm = async (
              payloadB64: string,
              ivB64: string,
              kParts: string[],
              version?: string | number
            ) => {
              try {
                const verNum = parseInt(String(version ?? "").trim(), 10);
                let selectedParts = kParts.slice(0, 2);
                if (verNum >= 1 && verNum <= 20 && kParts.length >= 31 - verNum) {
                  selectedParts = [kParts[verNum - 1], kParts[31 - verNum - 1]];
                }
                const k0 = fromB64Url(selectedParts[0]);
                const k1 = fromB64Url(selectedParts[1]);
                const keyBytes = new Uint8Array(k0.length + k1.length);
                keyBytes.set(k0, 0);
                keyBytes.set(k1, k0.length);
                const ivBytes = fromB64Url(ivB64);
                const cipherBytes = fromB64Url(payloadB64);
                const cryptoKey = await window.crypto.subtle.importKey(
                  "raw",
                  keyBytes,
                  { name: "AES-GCM" },
                  false,
                  ["decrypt"]
                );
                const decBuf = await window.crypto.subtle.decrypt(
                  { name: "AES-GCM", iv: ivBytes },
                  cryptoKey,
                  cipherBytes
                );
                return JSON.parse(new TextDecoder().decode(decBuf));
              } catch {
                return null;
              }
            };

            const sourcesList: string[] = [];
            let bestHeight = 0;
            let bestBitrateKbps = 0;
            let bestSizeBytes = 0;
            const collectSources = (decObj: any) => {
              if (Array.isArray(decObj?.sources)) {
                for (const s of decObj.sources) {
                  if (s?.url && typeof s.url === "string") {
                    sourcesList.push(s.url);
                    const h = Number(s.height || 0);
                    const br = Number(s.bitrate_kbps || 0);
                    const sz = Number(s.size_bytes || 0);
                    if (h > bestHeight) bestHeight = h;
                    if (br > bestBitrateKbps) bestBitrateKbps = br;
                    if (sz > bestSizeBytes && sz < 4294967295) bestSizeBytes = sz;
                  }
                }
              }
            };

            if (pb.payload && pb.iv && Array.isArray(pb.key_parts) && pb.key_parts.length >= 2) {
              collectSources(await decryptAesGcm(pb.payload, pb.iv, pb.key_parts, pb.version));
            }
            if (pb.payload2 && pb.iv2 && Array.isArray(pb.key_parts2) && pb.key_parts2.length >= 2) {
              collectSources(await decryptAesGcm(pb.payload2, pb.iv2, pb.key_parts2, pb.version2 || pb.version));
            }

            const bestM3u8 =
              sourcesList.find((u) => !u.includes("byse") && u.includes(".m3u8")) ||
              sourcesList.find((u) => u.includes(".m3u8")) ||
              sourcesList[0];

            if (bestM3u8) {
              const detectedAudios: string[] = [];
              const combinedMeta = `${defaultLangCode} ${videoTitle}`.toLowerCase();
              const addLang = (l: string) => {
                if (l && !detectedAudios.includes(l)) detectedAudios.push(l);
              };
              if (combinedMeta.includes("hin") || /\bhi\b/.test(combinedMeta)) addLang("Hindi");
              if (combinedMeta.includes("eng") || /\ben\b/.test(combinedMeta)) addLang("English");
              if (combinedMeta.includes("tam") || /\bta\b/.test(combinedMeta)) addLang("Tamil");
              if (combinedMeta.includes("tel") || /\bte\b/.test(combinedMeta)) addLang("Telugu");
              if (combinedMeta.includes("mal") || /\bml\b/.test(combinedMeta)) addLang("Malayalam");
              if (combinedMeta.includes("kan") || /\bkn\b/.test(combinedMeta)) addLang("Kannada");
              if (combinedMeta.includes("jpn") || combinedMeta.includes("jap")) addLang("Japanese");
              if (combinedMeta.includes("kor")) addLang("Korean");

              const qLabel =
                forcedQuality ||
                (bestHeight >= 1000 ? "1080p" : bestHeight >= 700 ? "720p" : bestHeight >= 450 ? "480p" : "1080p");
              const bw = bestBitrateKbps > 0 ? bestBitrateKbps * 1000 : 2200000;
              let dur = requestedDurationSec > 0 ? requestedDurationSec : 0;
              if (dur <= 0 && bestSizeBytes > 0 && bestBitrateKbps > 0) {
                dur = Math.round((bestSizeBytes * 8) / (bestBitrateKbps * 1000));
              }
              if (dur <= 0) dur = 1440;
              const estMb =
                bestSizeBytes > 0
                  ? Math.max(1, Math.round(bestSizeBytes / (1024 * 1024)))
                  : Math.max(15, Math.round((bw * dur) / (8 * 1024 * 1024)));

              const refUrl = `${apiOrigin}/`;
              this.registerStreamHostReferer(bestM3u8, refUrl);

              return {
                ok: true,
                m3u8Url: bestM3u8,
                meta: {
                  qualities: [qLabel],
                  qualityLinks: { [qLabel]: sourceUrl },
                  rawM3u8Url: bestM3u8,
                  downloadQualities: [
                    {
                      id: qLabel,
                      label: `${qLabel} ${qLabel.includes("1080") ? "Full HD" : qLabel.includes("720") ? "HD" : "Standard"}`,
                      sizeMb: estMb,
                      url: sourceUrl,
                      bandwidth: bw,
                    },
                  ],
                  audioTracks: detectedAudios,
                  duration: dur,
                  headers: { Referer: refUrl, Origin: apiOrigin },
                  referer: refUrl,
                },
              };
            }
          }
        } catch {}
      }
    } catch {}
    return { ok: false };
  }

  private static async resolveStreamInternal(
    trimmed: string,
    options: { forceRefresh?: boolean } = {}
  ): Promise<{
    streamUrl: string;
    isExtracted: boolean;
    cached: boolean;
    isEmbed?: boolean;
    expiresAt?: number;
  }> {
    const isKotlinApp = NativeBridgeService.isRunningInKotlinApp();

    // 1.5 Priority Tier (Android APK): Inbuilt Native Kotlin Scraper & Headless Sniffer (Zero CORS)
    if (isKotlinApp) {
      try {
        const nativeRes = await NativeBridgeService.extractStreamNative(trimmed);
        if (nativeRes && nativeRes.ok && nativeRes.m3u8Url) {
          const ttlMs = 170 * 60 * 1000;
          const expiresAt = Date.now() + ttlMs;
          const refHeader = nativeRes.headers?.Referer || nativeRes.headers?.referer || this.resolveEmbedReferer(trimmed);
          const metaPayload: CachedStreamMetadata = {
            qualities: nativeRes.qualities,
            qualityLinks: nativeRes.qualityLinks,
            rawM3u8Url: nativeRes.m3u8Url,
            downloadQualities: nativeRes.downloadQualities,
            audioTracks: nativeRes.audioTracks,
            duration: nativeRes.duration,
            headers: nativeRes.headers,
            referer: refHeader,
          };
          this.setClientCache(trimmed, nativeRes.m3u8Url, expiresAt, metaPayload);
          this.setClientCache(nativeRes.m3u8Url, nativeRes.m3u8Url, expiresAt, metaPayload);
          return {
            streamUrl: nativeRes.m3u8Url,
            isExtracted: true,
            cached: false,
            isEmbed: false,
            expiresAt
          };
        }
      } catch (nativeErr) {
        console.warn("[SmartStreamService] Native Kotlin scraper fallback to client/server:", nativeErr);
      }
    }

    // 1.8 Direct Client-Side Byse / Filemoon API Extraction (in Kotlin APK if Stage 1 bridge fell back)
    const lowerUrl = trimmed.toLowerCase();
    const isByseOrFilemoon =
      lowerUrl.includes("byse") ||
      lowerUrl.includes("filemoon") ||
      lowerUrl.includes("f16px") ||
      lowerUrl.includes("n1mwq") ||
      lowerUrl.includes("moonmov") ||
      lowerUrl.includes("kerapoxy");

    if (isKotlinApp && isByseOrFilemoon) {
      const clientByse = await this.extractByseClientSide(trimmed);
      if (clientByse.ok && clientByse.m3u8Url) {
        const ttlMs = 170 * 60 * 1000;
        const expiresAt = Date.now() + ttlMs;
        const chosenUrl = clientByse.m3u8Url;
        const meta: CachedStreamMetadata = {
          ...(clientByse.meta || {}),
          rawM3u8Url: clientByse.m3u8Url,
        };
        this.setClientCache(trimmed, chosenUrl, expiresAt, meta);
        this.setClientCache(chosenUrl, chosenUrl, expiresAt, meta);
        return {
          streamUrl: chosenUrl,
          isExtracted: true,
          cached: false,
          isEmbed: false,
          expiresAt,
        };
      }
    }

    // 2. Call backend proxy endpoint
    try {
      const controller = new AbortController();
      const timeoutId = setTimeout(() => controller.abort(), 30000);

      const response = await fetch("/api/extract-stream", {
        method: "POST",
        signal: controller.signal,
        headers: {
          "Content-Type": "application/json"
        },
        body: JSON.stringify({
          url: trimmed,
          forceRefresh: options.forceRefresh === true,
          fast: true
        })
      });
      clearTimeout(timeoutId);

      if (response.ok) {
        const result = await response.json();
        const chosenM3u8 = isKotlinApp
          ? (result.rawM3u8Url || result.m3u8Url)
          : result.m3u8Url;

        if (result.ok && chosenM3u8) {
          if (isKotlinApp && result.headers?.Referer) {
            NativeBridgeService.fetchTextCorsFree(chosenM3u8, result.headers).catch(() => {});
          }
          if (result.expiresAt) {
            const refHeader = result.headers?.Referer || result.headers?.referer || this.resolveEmbedReferer(trimmed);
            const meta: CachedStreamMetadata = {
              qualities: result.qualities,
              qualityLinks: result.qualityLinks,
              rawM3u8Url: result.rawM3u8Url || chosenM3u8,
              proxiedM3u8Url: result.m3u8Url,
              downloadQualities: result.downloadQualities,
              audioTracks: result.audioTracks,
              duration: result.duration,
              headers: result.headers,
              referer: refHeader,
            };
            this.setClientCache(trimmed, chosenM3u8, result.expiresAt, meta);
            this.setClientCache(chosenM3u8, chosenM3u8, result.expiresAt, meta);
            if (result.rawM3u8Url) {
              this.setClientCache(result.rawM3u8Url, chosenM3u8, result.expiresAt, meta);
            }
          }
          return {
            streamUrl: chosenM3u8,
            isExtracted: true,
            cached: Boolean(result.cached),
            isEmbed: false,
            expiresAt: result.expiresAt
          };
        }

        // If backend failed on Byse/Filemoon in web browser, try client-side Byse extractor before returning embed fallback
        if (isByseOrFilemoon) {
          const clientByse = await this.extractByseClientSide(trimmed);
          if (clientByse.ok && clientByse.m3u8Url) {
            const ttlMs = 170 * 60 * 1000;
            const expiresAt = Date.now() + ttlMs;
            const refUrl = clientByse.meta?.referer || "https://byseraguci.com/";
            const chosenUrl = isKotlinApp
              ? clientByse.m3u8Url
              : `/proxy/m3u8?url=${encodeURIComponent(clientByse.m3u8Url)}&referer=${encodeURIComponent(refUrl)}`;
            const meta: CachedStreamMetadata = {
              ...(clientByse.meta || {}),
              rawM3u8Url: clientByse.m3u8Url,
              proxiedM3u8Url: chosenUrl,
            };
            this.setClientCache(trimmed, chosenUrl, expiresAt, meta);
            this.setClientCache(chosenUrl, chosenUrl, expiresAt, meta);
            return {
              streamUrl: chosenUrl,
              isExtracted: true,
              cached: false,
              isEmbed: false,
              expiresAt,
            };
          }
        }

        // If backend returned embed fallback
        if (result.isEmbed && result.fallbackUrl) {
          console.log("[SmartStreamService] Backend provided embed fallback:", result.fallbackUrl);
          return {
            streamUrl: result.fallbackUrl,
            isExtracted: false,
            cached: false,
            isEmbed: true
          };
        }
      }
    } catch (proxyErr) {
      console.warn("[SmartStreamService] Backend proxy call failed or timed out:", proxyErr);
    }

    // 3.5 If backend proxy timed out on Byse/Filemoon, try client-side Byse extractor
    if (isByseOrFilemoon) {
      const clientByse = await this.extractByseClientSide(trimmed);
      if (clientByse.ok && clientByse.m3u8Url) {
        const ttlMs = 170 * 60 * 1000;
        const expiresAt = Date.now() + ttlMs;
        const refUrl = clientByse.meta?.referer || "https://byseraguci.com/";
        const chosenUrl = isKotlinApp
          ? clientByse.m3u8Url
          : `/proxy/m3u8?url=${encodeURIComponent(clientByse.m3u8Url)}&referer=${encodeURIComponent(refUrl)}`;
        const meta: CachedStreamMetadata = {
          ...(clientByse.meta || {}),
          rawM3u8Url: clientByse.m3u8Url,
          proxiedM3u8Url: chosenUrl,
        };
        this.setClientCache(trimmed, chosenUrl, expiresAt, meta);
        this.setClientCache(chosenUrl, chosenUrl, expiresAt, meta);
        return {
          streamUrl: chosenUrl,
          isExtracted: true,
          cached: false,
          isEmbed: false,
          expiresAt,
        };
      }
    }

    // 4. Fallback Tier 2: Check for direct embed iframe URL (e.g. voe.sx/e/ID)
    const embedFallback = SmartStreamService.getEmbedUrl(trimmed);
    if (embedFallback) {
      console.log("[SmartStreamService] Using embed player fallback for source:", embedFallback);
      return {
        streamUrl: embedFallback,
        isExtracted: false,
        cached: false,
        isEmbed: true
      };
    }

    // 5. Fallback Tier 3: Return raw source URL to attempt native playback
    return {
      streamUrl: trimmed,
      isExtracted: false,
      cached: false,
      isEmbed: false
    };
  }

  /**
   * Smart Network & Device RAM Auto-Quality Selector:
   * - Respects explicit user preference if set (`1080p`, `720p`, `480p`, `360p`).
   * - When set to `auto` / `Auto`, checks `navigator.connection` and `navigator.deviceMemory`:
   *   - Weak network (`2g`, `3g`, `< 2.5 Mbps`) or `<= 4GB RAM` -> prefers `480p` / `720p` for instant 0-buffer start.
   *   - Normal 4G (`2.5 - 6 Mbps`) -> prefers `720p`.
   *   - Fast Wi-Fi / 5G (`> 6 Mbps`) -> prefers `1080p` / `720p`.
   */
  public static getOptimalInitialQuality(availableQualities: string[]): string {
    if (!availableQualities || availableQualities.length === 0) return "auto";
    const normalizedMap = new Map<string, string>();
    for (const q of availableQualities) {
      normalizedMap.set(q.toLowerCase().trim(), q);
    }

    // 1. Check saved user preference
    try {
      const savedPref = (
        localStorage.getItem("maxplay_preferred_quality") ||
        localStorage.getItem("maxplay_default_quality") ||
        ""
      )
        .toLowerCase()
        .trim();
      if (savedPref && savedPref !== "auto") {
        const directMatch = normalizedMap.get(savedPref) || normalizedMap.get(`${savedPref}p`);
        if (directMatch) return directMatch;
      }
    } catch {}

    // 2. Smart Network & RAM Detection
    let priorityOrder = ["720p", "1080p", "480p", "360p"];
    try {
      const nav = typeof navigator !== "undefined" ? (navigator as any) : null;
      const conn = nav?.connection || nav?.mozConnection || nav?.webkitConnection;
      const effType: string = (conn?.effectiveType || "").toLowerCase();
      const downlink: number = typeof conn?.downlink === "number" ? conn.downlink : 0;
      const saveData: boolean = Boolean(conn?.saveData);
      const deviceRamGb: number = typeof nav?.deviceMemory === "number" ? nav.deviceMemory : 8;

      if (
        saveData ||
        effType === "slow-2g" ||
        effType === "2g" ||
        effType === "3g" ||
        (downlink > 0 && downlink < 2.5)
      ) {
        // Weak network: start with 480p or 360p for instant playback
        priorityOrder = ["480p", "360p", "720p", "1080p"];
      } else if (deviceRamGb <= 4 || (downlink >= 2.5 && downlink <= 6.5)) {
        // 4GB RAM phone or normal 4G: start with 720p or 480p for smooth lag-free playback
        priorityOrder = ["720p", "480p", "1080p", "360p"];
      } else if (downlink > 6.5) {
        // Fast Wi-Fi / 5G: start with 1080p or 720p
        priorityOrder = ["1080p", "720p", "480p", "360p"];
      }
    } catch {}

    for (const target of priorityOrder) {
      const match = normalizedMap.get(target);
      if (match) return match;
    }

    return availableQualities[0];
  }

  /**
   * Resolves the best source URL for a server that may have separate per-quality links
   * (`1080p`, `720p`, `480p`, `360p`) or a single default URL.
   */
  public static selectSourceUrlForQuality(
    defaultUrlOrLinks?: string | Record<string, string>,
    qualityLinksOrFallback?: Record<string, string> | string,
    requestedQuality?: string
  ): {
    url: string;
    quality: string;
    chosenUrl: string;
    chosenQuality: string;
    availableQualities: string[];
    cleanQualityLinks: Record<string, string>;
  } {
    const defaultUrl =
      typeof defaultUrlOrLinks === "string"
        ? this.sanitizeStreamUrl(defaultUrlOrLinks)
        : typeof qualityLinksOrFallback === "string"
        ? this.sanitizeStreamUrl(qualityLinksOrFallback)
        : "";
    const qualityLinks =
      defaultUrlOrLinks && typeof defaultUrlOrLinks === "object"
        ? defaultUrlOrLinks
        : qualityLinksOrFallback && typeof qualityLinksOrFallback === "object"
        ? qualityLinksOrFallback
        : undefined;

    const cleanLinks: Record<string, string> = {};
    if (qualityLinks && typeof qualityLinks === "object") {
      const order = ["1080p", "720p", "480p", "360p"];
      for (const k of Object.keys(qualityLinks)) {
        const val = qualityLinks[k];
        if (typeof val === "string" && val.trim().length > 0 && k.toLowerCase() !== "default") {
          const sanitized = this.sanitizeStreamUrl(val);
          if (sanitized) cleanLinks[k] = sanitized;
        }
      }
      // Sort keys in standard resolution order
      const sortedKeys = Object.keys(cleanLinks).sort((a, b) => {
        const ia = order.indexOf(a.toLowerCase());
        const ib = order.indexOf(b.toLowerCase());
        return (ia === -1 ? 99 : ia) - (ib === -1 ? 99 : ib);
      });
      const orderedLinks: Record<string, string> = {};
      for (const k of sortedKeys) {
        orderedLinks[k] = cleanLinks[k];
      }
      Object.keys(cleanLinks).forEach((k) => delete cleanLinks[k]);
      Object.assign(cleanLinks, orderedLinks);
    }

    const availableQualities = Object.keys(cleanLinks);
    if (availableQualities.length === 0) {
      const fallback = this.sanitizeStreamUrl(defaultUrl);
      const q = requestedQuality || "auto";
      return {
        url: fallback,
        quality: q,
        chosenUrl: fallback,
        chosenQuality: q,
        availableQualities: [],
        cleanQualityLinks: {},
      };
    }

    // If user explicitly picked a quality (e.g. 480p or 720p or 1080p)
    if (requestedQuality && requestedQuality.toLowerCase() !== "auto") {
      const reqLower = requestedQuality.toLowerCase().trim().replace(/\s*\[(s1|s2)\]/i, "");
      const exactKey = availableQualities.find(
        (q) => q.toLowerCase() === reqLower || q.toLowerCase() === `${reqLower}p`
      );
      if (exactKey && cleanLinks[exactKey]) {
        return {
          url: cleanLinks[exactKey],
          quality: exactKey,
          chosenUrl: cleanLinks[exactKey],
          chosenQuality: exactKey,
          availableQualities,
          cleanQualityLinks: cleanLinks,
        };
      }

      // If exact quality key is not in cleanLinks (e.g. user was on 480p and switched to a server with 720p/1080p master link),
      // pick the closest numeric resolution link so playback switches seamlessly while keeping HLS level targeting intact!
      const reqNum = parseInt(reqLower.replace(/\D/g, ""), 10);
      if (!isNaN(reqNum) && reqNum > 0) {
        let closestKey = availableQualities[0];
        let bestDiff = Infinity;
        for (const k of availableQualities) {
          const kNum = parseInt(k.replace(/\D/g, ""), 10);
          if (!isNaN(kNum) && kNum > 0) {
            const diff = Math.abs(kNum - reqNum);
            if (diff < bestDiff) {
              bestDiff = diff;
              closestKey = k;
            }
          }
        }
        if (closestKey && cleanLinks[closestKey]) {
          return {
            url: cleanLinks[closestKey],
            quality: reqLower,
            chosenUrl: cleanLinks[closestKey],
            chosenQuality: reqLower,
            availableQualities,
            cleanQualityLinks: cleanLinks,
          };
        }
      }
    }

    // Otherwise auto-select optimal quality for user's network & RAM
    const optimalQ = this.getOptimalInitialQuality(availableQualities);
    const chosenUrl =
      cleanLinks[optimalQ] ||
      cleanLinks[availableQualities[0]] ||
      this.sanitizeStreamUrl(defaultUrl);

    return {
      url: chosenUrl,
      quality: optimalQ,
      chosenUrl,
      chosenQuality: optimalQ,
      availableQualities,
      cleanQualityLinks: cleanLinks,
    };
  }

  /**
   * Clears all cached stream extraction tokens from sessionStorage and localStorage.
   */
  public static clearAllStreamCache(): number {
    let removed = 0;
    try {
      for (let i = sessionStorage.length - 1; i >= 0; i--) {
        const k = sessionStorage.key(i);
        if (k && k.startsWith(CACHE_PREFIX)) {
          sessionStorage.removeItem(k);
          removed++;
        }
      }
      for (let i = localStorage.length - 1; i >= 0; i--) {
        const k = localStorage.key(i);
        if (k && k.startsWith(CACHE_PREFIX)) {
          localStorage.removeItem(k);
          removed++;
        }
      }
    } catch {}
    return removed;
  }
}

