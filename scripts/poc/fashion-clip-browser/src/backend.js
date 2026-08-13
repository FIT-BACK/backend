export async function fetchRecommendation({
  baseUrl,
  reportId,
  accessToken = '',
  benchmarkTrace = false,
  fetchImpl = fetch,
}) {
  const url = recommendationUrl(baseUrl, reportId);
  const headers = { Accept: 'application/json' };
  const trimmedToken = accessToken.trim();
  if (trimmedToken) {
    headers.Authorization = `Bearer ${trimmedToken}`;
  }
  if (benchmarkTrace) {
    headers['X-Fitback-Benchmark-Trace'] = 'baseline-v1';
  }

  const started = performance.now();
  const response = await fetchImpl(url, {
    method: 'POST',
    headers,
  });
  let payload = null;
  try {
    payload = await response.json();
  } catch {
    payload = null;
  }
  return {
    status: response.status,
    ok: response.ok,
    latencyMs: performance.now() - started,
    benchmarkTraceId: response.headers?.get('X-Fitback-Benchmark-Trace-Id') ?? null,
    payload,
  };
}
export function extractBrowserReranking(payload) {
  const backendData = payload?.data;
  if (!backendData || typeof backendData !== 'object' || Array.isArray(backendData)) {
    return {
      kind: 'fallback',
      reason: 'backend response data unavailable',
      backendData: null,
    };
  }
  if (!backendData.browserReranking) {
    return {
      kind: 'fallback',
      reason: 'backend response had no browserReranking handoff',
      backendData,
    };
  }
  return {
    kind: 'ready',
    handoff: { browserReranking: backendData.browserReranking },
    backendData,
  };
}

function recommendationUrl(baseUrl, reportId) {
  const parsedBaseUrl = new URL(baseUrl);
  if (!['http:', 'https:'].includes(parsedBaseUrl.protocol)) {
    throw new Error('backend URL must use http or https');
  }
  const normalizedReportId = Number(reportId);
  if (!Number.isInteger(normalizedReportId) || normalizedReportId <= 0) {
    throw new Error('report ID must be a positive integer');
  }
  return new URL(
    `/api/v1/analyses/${encodeURIComponent(normalizedReportId)}/recommendations`,
    `${parsedBaseUrl.origin}/`
  );
}
