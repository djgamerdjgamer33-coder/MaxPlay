# MaxPlay Server 2 Complete Fix Report & Ready-to-Paste AI Prompt

Is document mein **2 sections** hain:
1. **Section 1: Ready-to-Paste Prompt** — Isko aap seedha copy karke apne dusre AI Studio session mein paste kar sakte hain.
2. **Section 2: Detailed File-by-File Technical Report** — Har ek file mein kya problem thi aur exact kaunsa code replace/update kiya gaya hai.

---

## SECTION 1: Ready-to-Paste Prompt (Dusre AI Studio Project Ke Liye)

Neeche wale code block ko copy karke dusre AI Studio session mein bhej dein:

```markdown
🔴 CRITICAL INSTRUCTION: Fix Server 2 Playback, Admin Panel Verification ("Failed to fetch"), and Download Interruption Issues Without Breaking Any Existing MaxPlay Functionality.

Please carefully apply the following exact fixes across the 8 files listed below. Do NOT touch Firebase config, Firestore rules, database structure, or any unrelated features.

### Root Causes to Fix:
1. **Byse / Filemoon Host Migration (`n1mwq.org` is dead/unreachable)**:
   - Byse (`byseraguci.com`) previously redirected `/e/{code}` to `n1mwq.org`, which is now dead (`ENOTFOUND`), and its new dynamic iframe host (`bysesukior.com`) blocks `/api/videos/{code}/embed/details` when called with its own origin instead of `https://byseraguci.com`.
   - Furthermore, the CDN domain changed to `*.r66nv9ed.com` (SprintCDN), which requires `Referer: https://byseraguci.com/`.
2. **Admin Panel `Failed to fetch` on Server 2 Verify**:
   - `public/admin.html` had a hardcoded dead Cloud Run URL (`https://ais-pre-yzg6gpi5jxavdg2ivlhyef-775675507395.asia-southeast1.run.app/api/extract-stream`) inside `window.probeSingleQualityBoxSmart`. When that dead URL failed, `Failed to fetch` was thrown.
   - Also, Byse returns `size_bytes: 4294967295` (`0xFFFFFFFF` uint32 cap) for ~4GB 1080p encodes, which distorted file size calculation unless filtered (`s.size_bytes < 4294967295`) and calibrated across batch qualities.
3. **Player Stuck on Loading Spinner**:
   - In `src/components/player/InlinePlayer.tsx`, if `/proxy/m3u8?url=...` fails or times out, HLS.js retried endlessly instead of unwrapping to the direct CDN `.m3u8` URL first and then falling back to the working embed player (`https://byseraguci.com/e/{code}`).
4. **Downloads Interrupted (`clientHlsDownloader.ts` & `server.ts`)**:
   - `server.ts` was overwriting `downloadQualities[].url` with `/proxy/ts?url=...` instead of keeping the `.m3u8` URL in `url` and `/proxy/m3u8?url=...` in `proxyUrl`.
   - `src/services/clientHlsDownloader.ts` only unwrapped `/api/proxy?url=` and did not unwrap `/proxy/m3u8?url=` or `/proxy/ts?url=`, nor did it fall back to direct CDN fetching when the proxy failed.

---

### Exact Changes Required File-by-File:

#### 1. `server.ts`
- **Byse Origin Priority (`byseEmbedOriginCache`, `prewarmByseFingerprints`, and `extractFilemoonByseStream`)**:
  - Change default `byseEmbedOriginCache` from `"https://n1mwq.org"` to `"https://byseraguci.com"`.
  - In `extractFilemoonByseStream`, build `candidateOrigins` so `origin` (`https://byseraguci.com`) and `"https://f16px.com"` are tried FIRST, and any `discoveredEmbedOrigin` from `<iframe src>` is appended LAST:
    ```ts
    const candidateOrigins = Array.from(new Set([
      origin,
      "https://byseraguci.com",
      "https://f16px.com",
      ...(discoveredEmbedOrigin ? [discoveredEmbedOrigin] : []),
      byseEmbedOriginCache
    ]));
    ```
- **Capture Exact `sizeBytes` in `extractFilemoonByseStream`**:
  - When mapping `allQualities` from decrypted `playbackData.sources`, extract `sizeBytes`:
    ```ts
    const rawBytes = Number(s.size_bytes || s.size || 0);
    const sizeBytes = (rawBytes > 0 && rawBytes < 4294967295) ? rawBytes : 0;
    ```
  - Pass `byseMeta`:
    ```ts
    const byseMeta = {
      durationSec: Number(detailsRes.data?.length_seconds || playbackData?.length_seconds || 0),
      bitrateKbps: Number(bestSource?.bitrate_kbps || 0),
      height: Number(bestSource?.height || 0),
      fps: Number(bestSource?.frame_rate || 0),
      sizeBytes: ( Number(bestSource?.size_bytes || 0) > 0 && Number(bestSource?.size_bytes || 0) < 4294967295 )
        ? Number(bestSource?.size_bytes)
        : 0
    };
    ```
- **In `parseHlsManifestMetadata`**:
  - Add `sizeBytes?: number` to `byseMeta` parameter type.
  - Include `r66nv9ed` in the SprintCDN/Byse referer check and default to `"https://byseraguci.com/"`:
    ```ts
    const effectiveReferer = referer || (
      m3u8Url.includes("sprintcdn") || m3u8Url.includes("r66nv9ed") || m3u8Url.includes("byse") || m3u8Url.includes("bysesukior") || m3u8Url.includes("n1mwq")
        ? "https://byseraguci.com/"
        : new URL(m3u8Url).origin + "/"
    );
    ```
  - When computing `sizeMb` in `parseHlsManifestMetadata`, prefer `byseMeta?.sizeBytes` (`Math.round((byseMeta.sizeBytes / (1024 * 1024)) * 10) / 10`) before falling back to bitrate estimation.
- **In `handleProxyM3u8`, `handleProxyTs`, and `/api/proxy`**:
  - Add `r66nv9ed` to the SprintCDN/Byse domain check and use `"https://byseraguci.com/"` as the default Referer instead of `"https://n1mwq.org/"`.
- **In `/api/extract-stream` (`proxiedDownloadQualities`)**:
  - Do NOT overwrite `dq.url` with `/proxy/ts?url=...`. Keep `dq.url` as the raw `.m3u8` URL and set `proxyUrl: "/proxy/m3u8?url=" + encodeURIComponent(dq.url) + "&referer=" + encodeURIComponent(referer)`:
    ```ts
    const proxiedDownloadQualities = (result.downloadQualities || []).map((dq: any) => ({
      ...dq,
      url: dq.url,
      proxyUrl: `/proxy/m3u8?url=${encodeURIComponent(dq.url)}&referer=${encodeURIComponent(referer)}`
    }));
    ```

#### 2. `public/admin.html`
- **Remove Dead Cloud Run URL in `window.probeSingleQualityBoxSmart`**:
  - Remove `https://ais-pre-yzg6gpi5jxavdg2ivlhyef-775675507395.asia-southeast1.run.app/api/extract-stream`.
  - When running on Web (`window.location.hostname !== 'appassets.androidplatform.net'`), call same-origin `/api/extract-stream` first.
  - Add `window.extractByseDirectInBrowser(rawUrl)` (using WebCrypto ECDSA P-256 + SHA-256 PoW + AES-256-GCM decryption against `https://byseraguci.com` and `https://f16px.com`) as a fallback for `MaxPlay-Admin.apk` (`appassets.androidplatform.net`) or if `/api/extract-stream` is unavailable.
- **Batch Duration & 4GB Cap Calibration in `window.verifyServerQualityBoxesBatch`**:
  - After all quality boxes finish probing in `Promise.allSettled`, find the maximum valid `durationSec` across sibling qualities (`consensusDurationSec`).
  - If any box (such as 1080p when `size_bytes` hit `4294967295`) has `durationSec < consensusDurationSec * 0.6` and `bitrateKbps > 0`, recalculate its `durationSec = consensusDurationSec`, `sizeMb = Math.round(((bitrateKbps * 1000 / 8) * consensusDurationSec) / (1024 * 1024))`, and update its badge UI (`720P • 3.20 GB`, etc.).

#### 3. `src/components/player/InlinePlayer.tsx`
- In `Hls` configuration, set `manifestLoadingTimeOut: 7000` and `manifestLoadingMaxRetry: 1` so broken proxy URLs fail fast.
- In `hls.on(Hls.Events.ERROR, ...)`, when `data.fatal` is true and `data.type === Hls.ErrorTypes.NETWORK_ERROR`:
  1. First check if `streamUrlToPlay` is a proxied URL (`/proxy/m3u8?url=...` or `/api/proxy?url=...`). If so, unwrap the `url` query param and call `hls.loadSource(directTarget)` once.
  2. If direct CDN also fails (or it was already direct), check `const fallbackEmbed = SmartStreamService.getEmbedUrl(activeVideoUrl);` and if available, switch immediately to embed playback (`setEmbedUrl(fallbackEmbed); setIsUsingEmbed(true); setIsLoading(false); hls.destroy();`) instead of staying stuck on the loading spinner.

#### 4. `src/services/clientHlsDownloader.ts`
- Update `unwrapProxyUrl(inputUrl: string)` so it unwraps `/proxy/m3u8?url=`, `/proxy/ts?url=`, `/api/proxy/m3u8?url=`, `/api/proxy/ts?url=`, and `/proxy?url=`.
- In `resolveMediaPlaylist`, `parseSegments`, and the segment worker inside `downloadStream`:
  - Wrap `fetch(proxiedUrl)` in a `try/catch` (and check `!res.ok`), and automatically fall back to `fetch(unwrapProxyUrl(rawUrl))` directly from the CDN so downloads never fail or get interrupted when a proxy route fails.

#### 5. `src/services/smartStreamService.ts`
- In `sanitizeStreamUrl`, unwrap any legacy proxy URLs (`nawazkhan001-mw-hub-master.hf.space` or `/proxy/(m3u8|ts)?url=`).
- In `resolveEmbedReferer`, add `r66nv9ed` to the SprintCDN/Byse check and return `"https://byseraguci.com/"`.
- Remove the dead `https://nawazkhan001-mw-hub-master.hf.space/api/extract` fallback and add client-side Byse WebCrypto extraction (`extractByseClientSide`) when running in the Android WebView (`appassets.androidplatform.net`).

#### 6. Android Kotlin Files (`InbuiltStreamScraper.kt`, `CorsFreeWebViewClient.kt`, `MaxPlayNativeBridge.kt`)
- **`InbuiltStreamScraper.kt`**:
  - Update `byseEmbedOriginCache` default from `"https://n1mwq.org"` to `"https://byseraguci.com"`.
  - In `extractByseApiStream`, try `[origin, "https://byseraguci.com", "https://f16px.com", discoveredEmbedOrigin]` in order, and capture `sizeBytes` (`optLong("size_bytes")` when `< 4294967295L`).
- **`CorsFreeWebViewClient.kt`**:
  - Allow `byseraguci.com` in `registerStreamReferer`, add `r66nv9ed` to SprintCDN checks, and default Byse/SprintCDN Referer to `"https://byseraguci.com/"`.
- **`MaxPlayNativeBridge.kt`**:
  - In `probeHlsPlaylistDetailsSync`, use `fallbackByseMeta.sizeBytes` (when `> 0L`) and `bitrateKbps` to calculate accurate `estMb` and `estDur`.
```

---

## SECTION 2: Detailed File-by-File Technical Report (Kya Problem Thi Aur Kaise Theek Kiya)

### 1. Problem 1: Server 2 Video Stuck on Loading / Not Playing
* **Kya Hua Tha (Root Cause)**:
  1. Server 2 (`byseraguci.com` / Filemoon-Byse) pahle apne embed page `/e/{code}` ko `https://n1mwq.org` par redirect karta tha. Ab `n1mwq.org` domain **band (dead / NXDOMAIN)** ho chuka hai.
  2. Byse ka naya iframe domain `bysesukior.com` hai, lekin jab backend `bysesukior.com` par `/api/videos/{code}/embed/details` call karta hai, toh woh **HTTP 403 (`Invalid embed domain`)** deta hai. Yeh API sirf tab kaam karti hai jab request `https://byseraguci.com` ya `https://f16px.com` par bheji jaye.
  3. Decrypt hone ke baad jo HLS `.m3u8` stream milti hai, uska CDN domain ab `*.r66nv9ed.com` (SprintCDN) hai, jisko `Referer: https://byseraguci.com/` chahiye hota hai (purana `https://n1mwq.org/` referer dene par stream reject ho jati thi).
  4. `InlinePlayer.tsx` mein agar proxy stream fail hoti thi, toh HLS player spinner ghumata rehta tha aur direct CDN ya Embed iframe (`https://byseraguci.com/e/{code}`) par fallback nahi karta tha.
* **Kaise Fix Kiya**:
  - `server.ts` aur `InbuiltStreamScraper.kt` mein `byseEmbedOriginCache` ko `"https://byseraguci.com"` kiya aur candidate origins mein `[origin, "https://byseraguci.com", "https://f16px.com"]` ko first priority di.
  - `server.ts`, `smartStreamService.ts`, aur `CorsFreeWebViewClient.kt` mein `r66nv9ed` domain add kiya aur default Referer `"https://byseraguci.com/"` set kiya.
  - `InlinePlayer.tsx` mein `manifestLoadingTimeOut: 7000` aur `manifestLoadingMaxRetry: 1` lagaya, aur network error aane par pahle `/proxy/m3u8?url=...` ko unwrap karke direct CDN `.m3u8` try kiya, aur agar woh bhi fail ho toh turant `SmartStreamService.getEmbedUrl(activeVideoUrl)` (`https://byseraguci.com/e/{code}`) par switch kar diya.

---

### 2. Problem 2: Admin Panel Verify Clicking Gives `Server 2 Verification Failed: 1080p: Failed to fetch`
* **Kya Hua Tha (Root Cause)**:
  1. `public/admin.html` ke andar `window.probeSingleQualityBoxSmart` function mein ek purana, dead Cloud Run URL hardcoded tha:
     `https://ais-pre-yzg6gpi5jxavdg2ivlhyef-775675507395.asia-southeast1.run.app/api/extract-stream`
  2. Jab Admin Panel mein **Verify** dabaya jata tha, toh local `/api/extract-stream` mein `n1mwq.org` ki wajah se failure aata tha, aur phir code us dead Cloud Run URL ko fetch karne ki koshish karta tha. Dead Cloud Run URL par browser `TypeError: Failed to fetch` throw karta tha, jo seedha UI par `Server 2 Verification Failed: 1080p: Failed to fetch` bankar dikhta tha.
  3. Iske alawa, 1080p quality (`lzakc6g08b9f`) ke liye Byse API `size_bytes: 4294967295` (32-bit unsigned integer limit = 4GB) return karti hai, jisse 1080p ka size galat calculate ho raha tha.
* **Kaise Fix Kiya**:
  - `public/admin.html` se dead Cloud Run URL (`ais-pre-yzg6gpi5jxavdg2ivlhyef...`) hata diya.
  - Web par same-origin `/api/extract-stream` ko primary rakha aur Android Admin APK (`appassets.androidplatform.net`) ke liye browser ke andar hi `window.extractByseDirectInBrowser` (WebCrypto ECDSA P-256 + SHA-256 PoW solver + AES-256-GCM decryptor) add kar diya.
  - `server.ts` aur `public/admin.html` mein `size_bytes < 4294967295` filter lagaya aur `verifyServerQualityBoxesBatch` mein sibling qualities (720p/480p/360p) ke true duration se 1080p ka exact MB/GB size calibrate kar diya.

---

### 3. Problem 3: Downloads Interrupted on Server 2
* **Kya Hua Tha (Root Cause)**:
  1. `server.ts` mein `/api/extract-stream` endpoint ke andar `proxiedDownloadQualities` banate waqt galti se `dq.url` ko `/proxy/ts?url=...` se overwrite kar diya gaya tha (jabki woh ek `.m3u8` playlist thi, `.ts` segment nahi).
  2. `src/services/clientHlsDownloader.ts` mein `unwrapProxyUrl()` sirf `/api/proxy?url=` ko pehchanta tha, `/proxy/m3u8?url=` ya `/proxy/ts?url=` ko unwrap nahi kar pata tha.
  3. Jab download ke dauran proxy request fail hoti thi, toh `clientHlsDownloader.ts` ke paas direct CDN (`*.r66nv9ed.com`) se segment fetch karne ka fallback nahi tha, jisse download beech mein hi `interrupted` ho jata tha.
* **Kaise Fix Kiya**:
  - `server.ts` mein `proxiedDownloadQualities` ke andar `dq.url` ko original `.m3u8` URL hi rehne diya aur `proxyUrl` mein `/proxy/m3u8?url=...` set kiya.
  - `src/services/clientHlsDownloader.ts` mein `unwrapProxyUrl` ko update kiya taaki woh `/proxy/m3u8?url=`, `/proxy/ts?url=`, `/api/proxy/m3u8?url=`, `/api/proxy/ts?url=`, aur `/proxy?url=` sabko unwrap kar sake.
  - `resolveMediaPlaylist`, `parseSegments`, aur segment download workers mein automatic direct CDN fallback (`fetch(unwrapProxyUrl(...))`) laga diya taaki proxy slow ya fail hone par bhi download bina ruke 100% complete ho.

---

### Summary Table of Modified Files

| # | File Path | Purpose of Fix |
|---|---|---|
| 1 | `/server.ts` | Replaced dead `n1mwq.org` with `byseraguci.com` & `f16px.com`, added `r66nv9ed` SprintCDN referer support, extracted exact `sizeBytes`, fixed `proxiedDownloadQualities` URL. |
| 2 | `/public/admin.html` | Removed dead hardcoded Cloud Run URL causing `Failed to fetch`, added browser WebCrypto Byse extractor for APK, calibrated 4GB-capped 1080p sizes in batch verification. |
| 3 | `/src/components/player/InlinePlayer.tsx` | Added fast timeout (`7000ms`), direct CDN unwrap retry, and automatic fallback to `byseraguci.com/e/{code}` embed player on fatal HLS error. |
| 4 | `/src/services/clientHlsDownloader.ts` | Unwrapped `/proxy/m3u8` & `/proxy/ts` URLs and added direct CDN fallback in playlist & segment downloaders. |
| 5 | `/src/services/smartStreamService.ts` | Unwrapped legacy proxy URLs, added `r66nv9ed` referer support, replaced dead HF Space extractor with client-side WebCrypto extractor. |
| 6 | `/android-kotlin/app/src/main/java/com/maxplay/app/InbuiltStreamScraper.kt` | Updated Kotlin native scraper to use `byseraguci.com` & `f16px.com` instead of dead `n1mwq.org` and capture `sizeBytes`. |
| 7 | `/android-kotlin/app/src/main/java/com/maxplay/app/CorsFreeWebViewClient.kt` | Allowed `byseraguci.com` referer registration and added `r66nv9ed` SprintCDN referer headers. |
| 8 | `/android-kotlin/app/src/main/java/com/maxplay/app/MaxPlayNativeBridge.kt` | Used `fallbackByseMeta.sizeBytes` and `bitrateKbps` for accurate native HLS size & duration probing. |
