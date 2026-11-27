// /content/*·/content/test-* viewer request: contentId × 국가 × KVS 정책 (design.md §6·§7). 서명 검증은 CloudFront가 한다
import cf from 'cloudfront';

var kvs = cf.kvs();

// contentId 규칙은 api AdminContentController와 같다. 버전 경계 슬래시까지 본다 (design.md §5).
// runs/{runId}/는 E2 cold 측정용 실행별 경로라 test- 콘텐츠에만 허용한다 (design.md §11)
var PATH = /^\/content\/([a-z0-9](?:[a-z0-9-]{0,38}[a-z0-9])?)\/(runs\/[a-z0-9-]{1,40}\/)?v[1-9][0-9]{0,8}\/[A-Za-z0-9_-][A-Za-z0-9_.-]*$/;

async function handler(event) {
  var request = event.request;
  var match = PATH.exec(request.uri);
  if (!match || (match[2] && match[1].indexOf('test-') !== 0)) {
    return deny(403, 'POLICY_UNAVAILABLE', null);
  }
  var key = 'policy:' + match[1];

  var policy;
  try {
    policy = await kvs.get(key, { format: 'json' });
  } catch (e) {
    // get()은 키가 없어도 실패한다. 없는 것만 403, 읽기·파싱 실패는 503 (fail-closed)
    var exists;
    try {
      exists = await kvs.exists(key);
    } catch (e2) {
      return deny(503, null, null);
    }
    return exists ? deny(503, null, null) : deny(403, 'POLICY_UNAVAILABLE', null);
  }

  var rights = policy && policy.rights;
  if (typeof rights !== 'object' || rights === null || Array.isArray(rights)) {
    return deny(503, null, policy && policy.rev);
  }
  var header = request.headers['cloudfront-viewer-country'];
  var period = header ? rights[header.value] : undefined;
  if (period === undefined) {
    return deny(403, 'GEO_BLOCKED', policy.rev);
  }
  if (!isPeriod(period)) {
    return deny(503, null, policy.rev);
  }
  var now = Math.floor(Date.now() / 1000);
  if (now < period[0] || now >= period[1]) {
    return deny(403, 'NOT_AVAILABLE', policy.rev);
  }
  return request;
}

function isPeriod(value) {
  return Array.isArray(value) && value.length === 2 && typeof value[0] === 'number' && typeof value[1] === 'number';
}

function deny(status, code, rev) {
  var headers = { 'cache-control': { value: 'no-store' } };
  if (code) {
    headers['x-playback-denied'] = { value: code };
  }
  // synthetic 클라이언트가 엣지가 실제로 읽은 정책 순번을 기록한다 (design.md §8)
  if (typeof rev === 'number') {
    headers['x-policy-rev'] = { value: String(rev) };
  }
  return { statusCode: status, statusDescription: status === 403 ? 'Forbidden' : 'Service Unavailable', headers: headers };
}
