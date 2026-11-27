// synthetic 클라이언트가 테스트 키로 Signed Cookie를 직접 만든다 (design.md §5 "테스트용 서명 키").
// api(SignedCookieSigner)와 같은 custom policy + SHA-256 형식이다
import { createPrivateKey, sign } from 'node:crypto';

// CloudFront는 base64의 + = /를 쿠키에 쓸 수 있는 - _ ~로 바꾼 값을 받는다
export function cloudFrontBase64(buffer) {
  return buffer.toString('base64').replace(/\+/g, '-').replace(/=/g, '_').replace(/\//g, '~');
}

export function customPolicy(resource, expiresAtEpochSeconds) {
  return JSON.stringify({
    Statement: [{ Resource: resource, Condition: { DateLessThan: { 'AWS:EpochTime': expiresAtEpochSeconds } } }]
  });
}

export function signedCookies({ resource, expiresAt, keyPairId, privateKeyPem }) {
  const policy = customPolicy(resource, expiresAt);
  const signature = sign('sha256', Buffer.from(policy), createPrivateKey(privateKeyPem));
  return {
    'CloudFront-Policy': cloudFrontBase64(Buffer.from(policy)),
    'CloudFront-Signature': cloudFrontBase64(signature),
    'CloudFront-Key-Pair-Id': keyPairId,
    // 생략하면 SHA1로 검증한다
    'CloudFront-Hash-Algorithm': 'SHA256'
  };
}

export function cookieHeader(cookies) {
  return Object.entries(cookies)
    .map(([name, value]) => `${name}=${value}`)
    .join('; ');
}

// master가 있는 버전 디렉터리 전체. api가 발급하는 Resource(/content/{id}/v{n}/*)와 같은 범위다
export function versionResource(masterUrl) {
  const url = new URL(masterUrl);
  return `${url.origin}${url.pathname.slice(0, url.pathname.lastIndexOf('/') + 1)}*`;
}
