// enable_api=false일 때 /api/*. behavior를 지우면 SPA 재작성이 200을 돌려주므로 남겨 둔다 (design.md §4-1)
function handler(event) {
  return {
    statusCode: 503,
    statusDescription: 'Service Unavailable',
    headers: {
      'content-type': { value: 'application/json' },
      'cache-control': { value: 'no-store' },
      'retry-after': { value: '300' }
    },
    body: {
      encoding: 'text',
      data: JSON.stringify({ code: 'API_MAINTENANCE', detail: 'API is not running' })
    }
  };
}
