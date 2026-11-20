import test from 'node:test';
import assert from 'node:assert/strict';
import {
  buildByseCandidateOrigins,
  extractVoeVideoCode,
  buildVoeCandidateUrls,
  evaluateStreamtapeJsExpr,
  normalizeStreamtapeDirectUrl,
} from './streamExtractorUtils';

test('buildByseCandidateOrigins prioritizes cached origin and direct filemoon.sx over flaky Cloudflare origin', () => {
  const ordered = buildByseCandidateOrigins('https://byseraguci.com');
  assert.equal(ordered[0], 'https://filemoon.sx');
  assert.equal(ordered[1], 'https://filemoon.to');
  assert.equal(ordered[2], 'https://byseraguci.com');
  assert.equal(new Set(ordered).size, ordered.length);

  const withCache = buildByseCandidateOrigins('https://bysedikamoum.com', 'https://filemoon.to');
  assert.equal(withCache[0], 'https://filemoon.to');
  assert.equal(withCache[1], 'https://filemoon.sx');
  assert.equal(withCache[2], 'https://bysedikamoum.com');
});

test('extractVoeVideoCode extracts video code from /e/, root, and /d/ VOE URLs', () => {
  assert.equal(extractVoeVideoCode('https://voe.sx/e/109moqjmxfga'), '109moqjmxfga');
  assert.equal(extractVoeVideoCode('https://voe.sx/1u6vqnb1k9ld'), '1u6vqnb1k9ld');
  assert.equal(extractVoeVideoCode('https://arianathankspecial.com/e/abc123def456?ref=1'), 'abc123def456');
  assert.equal(extractVoeVideoCode('https://example.com/index.html'), '');
});

test('buildVoeCandidateUrls puts active mirror ahead of DDoS-Guarded voe.sx', () => {
  const urls = buildVoeCandidateUrls('https://voe.sx/1u6vqnb1k9ld', 'https://arianathankspecial.com');
  assert.equal(urls[0], 'https://arianathankspecial.com/e/1u6vqnb1k9ld');
  assert.equal(urls[1], 'https://susanexpectcare.com/e/1u6vqnb1k9ld');
  assert.equal(urls[urls.length - 1], 'https://voe.sx/e/1u6vqnb1k9ld');

  const directMirror = buildVoeCandidateUrls('https://custommirror.com/e/109moqjmxfga', 'https://arianathankspecial.com');
  assert.equal(directMirror[0], 'https://custommirror.com/e/109moqjmxfga');
  assert.equal(directMirror[1], 'https://arianathankspecial.com/e/109moqjmxfga');
});

test('evaluateStreamtapeJsExpr handles nested parentheses and chained substring/slice calls', () => {
  const expr1 = "'//streamtape.com/get_' + ('xxvideo?id=abc&token=XYZ').substring(2)";
  assert.equal(evaluateStreamtapeJsExpr(expr1), '//streamtape.com/get_video?id=abc&token=XYZ');

  const expr2 = "(('ab' + 'cd//streamtape.com/get_video?id=9&token=T1').substring(2)).substring(2)";
  assert.equal(evaluateStreamtapeJsExpr(expr2), '//streamtape.com/get_video?id=9&token=T1');

  const expr3 = "'/streamtape.com/get_video?' + ('12345id=k&token=ok99').slice(5, 18)";
  assert.equal(evaluateStreamtapeJsExpr(expr3), '/streamtape.com/get_video?id=k&token=ok');
});

test('normalizeStreamtapeDirectUrl adds https and stream=1 safely', () => {
  assert.equal(
    normalizeStreamtapeDirectUrl('//streamtape.com/get_video?id=6kjz8WP38qT9AKg&token=abc'),
    'https://streamtape.com/get_video?id=6kjz8WP38qT9AKg&token=abc&stream=1'
  );
  assert.equal(
    normalizeStreamtapeDirectUrl('/streamtape.com/get_video?id=1&token=2&stream=1'),
    'https://streamtape.com/get_video?id=1&token=2&stream=1'
  );
  assert.equal(normalizeStreamtapeDirectUrl('not_a_video_link'), '');
});
