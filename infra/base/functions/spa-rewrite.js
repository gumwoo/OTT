// 기본 behavior 전용. 확장자가 없는 앱 경로만 /index.html로 바꾼다.
// /content/*, /api/*는 별도 behavior라 이 함수가 실행되지 않는다.
function handler(event) {
  var request = event.request;
  var uri = request.uri;

  if (uri.endsWith('/')) {
    request.uri = '/index.html';
    return request;
  }

  var lastSegment = uri.substring(uri.lastIndexOf('/') + 1);
  if (lastSegment.indexOf('.') === -1) {
    request.uri = '/index.html';
  }

  return request;
}
