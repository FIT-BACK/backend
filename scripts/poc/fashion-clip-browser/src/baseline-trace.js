import { CANDIDATE_TERMINATION_REASONS } from './candidate-fetch.js';

const TRACE_EVENT = 'browser_fashion_clip_performance_trace';
const TRACE_SCHEMA_VERSION = 'baseline-v1';

export function buildBrowserPerformanceTrace({
  outcome,
  browserE2EWallClockMs,
  backendRequest,
  modelConfig,
  modelReadiness,
  reranking,
}) {
  const metrics = reranking?.metrics ?? {};
  const modelLoad = modelReadiness?.loadRun ?? null;
  const providerState = modelLoad ?? modelReadiness ?? {};
  const selectedCandidates = Array.isArray(reranking?.selectedCandidates)
    ? reranking.selectedCandidates
    : [];
  const candidateCount = safeCount(reranking?.candidateCount ?? selectedCandidates.length);

  return {
    event: TRACE_EVENT,
    schemaVersion: TRACE_SCHEMA_VERSION,
    outcome: safeOutcome(outcome),
    backend: {
      requestCount: 1,
      status: safeCount(backendRequest?.status),
      requestWallClockMs: safeLatency(backendRequest?.latencyMs),
      traceId: safeTraceId(backendRequest?.benchmarkTraceId),
    },
    model: {
      source: safeModelSource(modelConfig?.source),
      url: safeModelUrl(modelConfig?.url),
      cacheState: safeCacheState(modelReadiness?.cacheState),
      headersWallClockMs: safeLatency(modelLoad?.headersMs),
      bodyTransferWallClockMs: safeLatency(modelLoad?.downloadMs),
      transferredBytes: safeCount(modelLoad?.bytes),
      contentLengthBytes: safeCount(modelLoad?.contentLength),
      contentType: safeContentType(modelLoad?.contentType),
      cacheControl: safeCacheControl(modelLoad?.cacheControl),
      redirected: Boolean(modelLoad?.redirected),
      sessionCreationWallClockMs: safeLatency(modelLoad?.sessionCreationMs),
      readinessWallClockMs: safeLatency(modelReadiness?.waitWallClockMs),
      provider: {
        requested: safeProviders(providerState.requestedProviders),
        resolution: safeProviderResolution(providerState.providerResolution),
        fallbackUsed: Boolean(providerState.fallbackUsed),
      },
    },
    query: {
      decodeWallClockMs: safeLatency(metrics.queryDecodeWallClockMs),
      preprocessWallClockMs: safeLatency(metrics.queryPreprocessWallClockMs),
      decodePreprocessWallClockMs: safeLatency(metrics.queryAcquisitionWallClockMs),
    },
    pipeline: {
      queryAndCandidateAcquisitionWallClockMs: safeLatency(
        metrics.concurrentAcquisitionWallClockMs,
      ),
    },
    candidates: {
      count: candidateCount,
      imageFetch: imageFetchTiming(metrics, candidateCount),
      decode: timing(
        metrics.candidateDecodeRequestCount,
        metrics.candidateDecodeSuccessCount,
        metrics.candidateDecodeFailureCount,
        metrics.decodeWallClockMs,
        metrics.decodeCumulativeMs,
      ),
      preprocess: timing(
        metrics.candidatePreprocessRequestCount,
        metrics.candidatePreprocessSuccessCount,
        metrics.candidatePreprocessFailureCount,
        metrics.preprocessWallClockMs,
        metrics.preprocessCumulativeMs,
      ),
    },
    inference: {
      queryWallClockMs: safeLatency(metrics.queryInferenceWallClockMs),
      candidatesWallClockMs: safeLatency(metrics.candidateBatchInferenceWallClockMs),
    },
    scoring: {
      cosineWallClockMs: safeLatency(metrics.cosineWallClockMs),
      finalScoreWallClockMs: safeLatency(metrics.finalScoreCalculationWallClockMs),
      sortingWallClockMs: safeLatency(metrics.sortingWallClockMs),
      renderWallClockMs: safeLatency(metrics.renderUpdateWallClockMs),
    },
    total: {
      browserRerankingWallClockMs: safeLatency(metrics.totalRerankingWallClockMs),
      browserE2EWallClockMs: safeLatency(browserE2EWallClockMs),
    },
  };
}

function imageFetchTiming(metrics, candidateCount) {
  return {
    ...timing(
      metrics.candidateFetchRequestCount ?? candidateCount,
      metrics.candidateImageFetchSuccessCount,
      metrics.candidateImageFetchFailureCount,
      metrics.fetchWallClockMs,
      metrics.fetchCumulativeMs,
    ),
    deadlineMs: safeLatency(metrics.candidateFetchDeadlineMs),
    slowestRequestLatencyMs: safeLatency(metrics.candidateFetchMaxLatencyMs),
    outcomes: {
      successCount: safeCount(metrics.candidateTerminationSuccessCount),
      httpErrorCount: safeCount(metrics.candidateFetchHttpErrorCount),
      timeoutCount: safeCount(metrics.candidateFetchTimeoutCount),
      networkErrorCount: safeCount(metrics.candidateFetchNetworkErrorCount),
      abortedCount: safeCount(metrics.candidateFetchAbortedCount),
      decodeErrorCount: safeCount(metrics.candidateDecodeErrorCount),
    },
    terminations: safeCandidateTerminations(metrics.candidateFetchTerminations),
  };
}

export function logBrowserPerformanceTrace(trace, logger = console) {
  logger.info(TRACE_EVENT, JSON.stringify(trace));
  return trace;
}

function timing(requestCount, successCount, failureCount, wallClockMs, cumulativeMs) {
  return {
    requestCount: safeCount(requestCount),
    successCount: safeCount(successCount),
    failureCount: safeCount(failureCount),
    wallClockMs: safeLatency(wallClockMs),
    cumulativeMs: safeLatency(cumulativeMs),
  };
}

function safeCandidateTerminations(value) {
  if (!Array.isArray(value)) {
    return [];
  }
  const allowedReasons = new Set(CANDIDATE_TERMINATION_REASONS);
  return value.flatMap((candidate) => {
    const originalIndex = safeOriginalIndex(candidate?.originalIndex);
    const reason = candidate?.reason;
    if (originalIndex === null || !allowedReasons.has(reason)) {
      return [];
    }
    return [{
      originalIndex,
      reason,
      fetchLatencyMs: safeLatency(candidate.fetchLatencyMs),
    }];
  });
}

function safeLatency(value) {
  if (!Number.isFinite(value) || value < 0) {
    return null;
  }
  return Number(value.toFixed(3));
}

function safeCount(value) {
  const number = Number(value);
  if (!Number.isFinite(number) || number < 0) {
    return null;
  }
  return Math.trunc(number);
}

function safeOriginalIndex(value) {
  const index = safeCount(value);
  return index !== null && index > 0 ? index : null;
}

function safeTraceId(value) {
  return typeof value === 'string' && /^[a-f0-9-]{36}$/i.test(value) ? value : null;
}

function safeModelUrl(value) {
  if (typeof value !== 'string') {
    return null;
  }
  try {
    const url = new URL(value);
    return `${url.origin}${url.pathname}`;
  } catch {
    return null;
  }
}

function safeModelSource(value) {
  return typeof value === 'string' ? value.split(/[?#]/, 1)[0] : null;
}

function safeContentType(value) {
  if (typeof value !== 'string') {
    return null;
  }
  const contentType = value.split(';', 1)[0].trim().toLowerCase();
  return /^[a-z0-9.+-]+\/[a-z0-9.+-]+$/.test(contentType) ? contentType : null;
}

function safeCacheControl(value) {
  if (typeof value !== 'string') {
    return null;
  }
  const directives = value.split(',')
    .map((directive) => directive.trim().toLowerCase())
    .filter((directive) => (
      directive === 'public'
        || directive === 'private'
        || directive === 'no-cache'
        || directive === 'no-store'
        || directive === 'immutable'
        || /^(?:max-age|s-maxage)=\d+$/.test(directive)
    ));
  return directives.length > 0 ? directives.join(', ') : null;
}

function safeProviders(value) {
  if (!Array.isArray(value)) {
    return [];
  }
  return value.filter((provider) => provider === 'webgpu' || provider === 'wasm');
}

function safeProviderResolution(value) {
  return value === 'wasm'
    || value === 'unexposed-by-onnxruntime-web'
    || value === 'unavailable'
    ? value
    : 'unavailable';
}

function safeCacheState(value) {
  return value === 'cold' || value === 'warm' ? value : 'unknown';
}

function safeOutcome(value) {
  return value === 'SUCCESS' || value === 'BROWSER_RERANKING_FAILED'
    ? value
    : 'UNAVAILABLE';
}
