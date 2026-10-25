import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import {
  unwrapStreamProxyUrl,
  sanitizeStreamUrl,
  isSprintCdnUrl,
  isByseFilemoonHost,
  extractByseVideoCode,
  solveBysePowNode,
  computeByseStreamMetrics,
} from './streamUtils.ts';
import * as crypto from 'crypto';

describe('streamUtils', () => {
  it('unwrapStreamProxyUrl unwraps single and nested proxy URLs', () => {
    const raw = 'https://edge1-waw-sprintcdn.owphbf24.com/hls2/01/06608/lzakc6g08b9f_h/master.m3u8?t=abc&s=123';
    const proxied1 = `http://localhost:3000/proxy/m3u8?url=${encodeURIComponent(raw)}`;
    const proxied2 = `http://localhost:3000/api/proxy?url=${encodeURIComponent(proxied1)}`;

    assert.equal(unwrapStreamProxyUrl(proxied1), raw);
    assert.equal(unwrapStreamProxyUrl(proxied2), raw);
    assert.equal(
      unwrapStreamProxyUrl(`https://nawazkhan001-mw-hub-master.hf.space/proxy/ts?url=${encodeURIComponent(raw)}`),
      raw
    );
  });

  it('sanitizeStreamUrl unwraps dead proxy hosts and strips autoplay/mute params', () => {
    const target = 'https://byseraguci.com/e/lzakc6g08b9f?autoPlay=1&mute=true';
    const wrapped = `https://nawazkhan001-mw-hub-master.hf.space/proxy/m3u8?url=${encodeURIComponent(target)}`;
    assert.equal(sanitizeStreamUrl(wrapped), 'https://byseraguci.com/e/lzakc6g08b9f');
  });

  it('isSprintCdnUrl detects both owphbf24 and r66nv9ed SprintCDN domains', () => {
    assert.equal(isSprintCdnUrl('https://edge1-waw-sprintcdn.owphbf24.com/hls2/master.m3u8'), true);
    assert.equal(isSprintCdnUrl('https://edge1-madrid-sprintcdn.r66nv9ed.com/hls2/master.m3u8'), true);
    assert.equal(isSprintCdnUrl('https://byseraguci.com/e/lzakc6g08b9f'), false);
  });

  it('isByseFilemoonHost detects Byse and Filemoon domains', () => {
    assert.equal(isByseFilemoonHost('https://byseraguci.com/e/lzakc6g08b9f'), true);
    assert.equal(isByseFilemoonHost('https://f16px.com/e/lzakc6g08b9f'), true);
    assert.equal(isByseFilemoonHost('https://filemoon.sx/e/lzakc6g08b9f'), true);
    assert.equal(isByseFilemoonHost('https://vidhide.com/e/abc'), false);
  });

  it('extractByseVideoCode extracts code from /e/, /d/, /epb/ paths', () => {
    assert.equal(extractByseVideoCode('https://byseraguci.com/e/lzakc6g08b9f'), 'lzakc6g08b9f');
    assert.equal(extractByseVideoCode('https://byseraguci.com/d/c4grcqgy966t'), 'c4grcqgy966t');
    assert.equal(extractByseVideoCode('https://n1mwq.org/epb/wje6dlxkteqk'), 'wje6dlxkteqk');
  });

  it('solveBysePowNode finds valid nonce matching difficulty bits', () => {
    const challenge = 'dGVzdGNoYWxsZW5nZQ==';
    const difficulty = 8; // first byte must be 0x00
    const nonce = solveBysePowNode(challenge, difficulty);
    const hash = crypto.createHash('sha256').update(`${challenge}${nonce}`).digest();
    assert.equal(hash[0], 0);
  });

  it('computeByseStreamMetrics ignores uint32 max placeholder and computes real MB and duration', () => {
    // Valid size_bytes (e.g. 207,011,488 bytes = ~197 MB, 1347 kbps)
    const m1 = computeByseStreamMetrics(207011488, 1347, 0);
    assert.equal(m1.sizeMb, 197);
    assert.ok(m1.durationSec > 1200 && m1.durationSec < 1250);

    // uint32 max placeholder 4294967295 with 1400 kbps and 1200s fallback duration
    const m2 = computeByseStreamMetrics(4294967295, 1400, 1200);
    assert.equal(m2.sizeBytes, 0);
    assert.equal(m2.durationSec, 1200);
    assert.equal(m2.sizeMb, 200);
  });
});
