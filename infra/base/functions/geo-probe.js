// 0단계 스모크 테스트 (design.md §10).
// 질문: CloudFront-Viewer-Country를 Origin Request Policy에만 넣었을 때 viewer request 단계에서 읽히는가?
// headerPresent가 false면 2단계의 국가 정책 설계를 시작 전에 다시 검토한다.
function handler(event) {
  var headers = event.request.headers;
  var country = headers['cloudfront-viewer-country']
    ? headers['cloudfront-viewer-country'].value
    : null;

  return {
    statusCode: 200,
    statusDescription: 'OK',
    headers: {
      'content-type': { value: 'application/json' },
      'cache-control': { value: 'no-store' }
    },
    body: {
      encoding: 'text',
      data: JSON.stringify({ country: country, headerPresent: country !== null })
    }
  };
}
