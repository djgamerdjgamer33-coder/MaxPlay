# MaxPlay Server 2 & Cross-Server System Fix Report

## 1. Problem Diagnosis & Root Causes Identified
1. **Server 2 Unlimited Buffering & HTTP 500 (`fetch failed`)**:
   - SprintCDN edge domains (`edge1-frankfurt-sprintcdn.owphbf24.com`, `edge1-madrid-sprintcdn.r66nv9ed.com`, etc.) return 4 round-robin IPv4 addresses per domain.
   - Node's standard `fetch()` (`undici`) attempts only one random IP and hangs for 25-30 seconds if that specific IP filters cloud traffic, causing `/proxy/m3u8` to return HTTP 500 `# Proxy error: fetch failed`.
   - In `InlinePlayer.tsx`, fatal network errors called `hls.startLoad()`, triggering an endless buffering loop.
2. **HTML Page URL Injected into `qualityLinks`**:
   - In `parseHlsManifestMetadata`, when playlist variants were single-quality or in error fallbacks, `qualityLinks[defaultSingleQ] = originalSourceUrl || m3u8Url` stored the Byse web page URL (`https://byseraguci.com/d/...`) instead of the `.m3u8` stream URL.
   - When the user selected a quality or server, the player attempted to play HTML as an M3U8 manifest or triggered an iframe fallback.
3. **Old Server 1 Video Continuing to Play on Server 2 Switch**:
   - In `ContentDetailScreen.tsx`, `InlinePlayer` was mounted with `key={`${content.id}-${currentPartKey}`}`, which omitted `selectedServer`. Switching between `server1` and `server2` did not remount the player, causing the existing HLS engine to retain Server 1's buffer.
4. **Premature Cross-Server Language Toggle ("Switching Server 1" when already on Server 1)**:
   - `handleUnifiedLanguageSelect` previously checked `(!inS1 && (inS2 || s1SpecificLanguages.length > 0))`, which fired before HLS audio tracks finished probing and erroneously triggered server toggling.
5. **Admin Panel Verification Intermittent Failures**:
   - Byse attestation token cache was retained even after receiving HTTP 401/403/428, causing subsequent verify attempts to fail until cache expiry.

---

## 2. Solutions Implemented & Verified
- **Resilient SprintCDN IP Router (`resilientFetchSprintCdn`)**:
   - Implemented an IPv4 DNS resolver and connection race in `server.ts` with SNI and a 3.5s timeout.
   - Caches the responsive IP per hostname in memory (`sprintCdnWorkingIpCache`), allowing all subsequent master manifest, variant playlist, and TS segment requests to resolve in 200–300ms.
- **Pristine Stream URLs in `qualityLinks`**:
   - Updated `parseHlsManifestMetadata` so that `qualityLinks` strictly contains `.m3u8` stream URLs.
   - Guarded `proxiedQualityLinks` in `handleExtractStream` to ensure only `.m3u8` links are proxied, preventing HTML contamination.
- **Server-Isolated Player Mounting**:
   - Updated `InlinePlayer` in `ContentDetailScreen.tsx` with `key={`${content.id}-${currentPartKey}-${selectedServer}`}` and `streamKey={`${content.id}-${currentPartKey}-${selectedServer}`}`.
   - Switching servers cleanly tears down the previous stream and starts the new server instantly from `playbackPosition` with the user's selected quality intact.
- **Iframe Fallback Elimination for Extractable Sources**:
   - In `InlinePlayer.tsx`, `isIframe` explicitly checks `!SmartStreamService.isExtractableSource(activeVideoUrl)`. Extractable VOE and Byse/Filemoon streams will never trigger an iframe.
- **Network Error Retry Capping**:
   - Capped fatal network error retries in `InlinePlayer.tsx` to 4 attempts before presenting a clear retry prompt, preventing infinite buffering loops.
- **Refined Cross-Server Language & Audio Switching**:
   - `handleUnifiedLanguageSelect` now validates that the alternate server actually supports the language before switching servers, and switches live in-stream audio tracks with toast feedback when supported locally.
- **Admin Panel Stale Attestation Eviction**:
   - In both `server.ts` and `InbuiltStreamScraper.kt`, any failed challenge or empty playback response immediately purges the cached attestation token, guaranteeing consistent verification.

---

## 3. End-to-End Verification Results
- **All 4 Live Byse Server 2 URLs Tested**:
  - `https://byseraguci.com/d/w569ru20zbwy`: Extracted in ~4s, master `.m3u8` HTTP 200 in 264ms, variant `.m3u8` HTTP 200 in 637ms, TS segment HTTP 206 in 255ms. All 6 audio tracks detected (`Hindi`, `Tamil`, `Telugu`, `Bengali`, `English`, `Japanese`).
  - `https://byseraguci.com/d/c4grcqgy966t`: Extracted in ~3.5s, master `.m3u8` HTTP 200 in 252ms, TS segment HTTP 206.
  - `https://byseraguci.com/d/wje6dlxkteqk`: Extracted in ~2.4s, master `.m3u8` HTTP 200 in 251ms, TS segment HTTP 206.
  - `https://byseraguci.com/d/lzakc6g08b9f`: Extracted in ~4.6s, master `.m3u8` HTTP 200 in 251ms, TS segment HTTP 206.
- **Zero Lint or Compilation Errors**: `tsc --noEmit` and `compile_applet` both succeeded cleanly.
