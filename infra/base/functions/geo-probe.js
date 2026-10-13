// ORP에만 넣은 국가 헤더가 viewer request에서 보이는지 확인용 (design.md §10)
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
