export const CANDIDATE_IMAGE_FETCH_DEADLINE_MS = 30_000;

export const CANDIDATE_TERMINATION_REASONS = Object.freeze([
  'success',
  'http_error',
  'timeout',
  'network_error',
  'aborted',
  'decode_error',
]);

const SUPPORTED_IMAGE_CONTENT_TYPES = new Set([
  'image/jpeg',
  'image/png',
  'image/webp',
]);

export async function fetchCandidateBlob({
  url,
  originalIndex,
  deadlineMs = CANDIDATE_IMAGE_FETCH_DEADLINE_MS,
  fetchImpl = fetch,
  now = defaultNow,
  setTimeoutImpl = setTimeout,
  clearTimeoutImpl = clearTimeout,
  AbortControllerImpl = AbortController,
}) {
  if (!Number.isFinite(deadlineMs) || deadlineMs <= 0) {
    throw new RangeError('candidate image fetch deadline must be positive');
  }

  const fetchStartedAt = now();
  const controller = new AbortControllerImpl();
  let deadlineHandle = null;
  let deadlineTriggered = false;
  const requestOutcome = Promise.resolve()
    .then(() => fetchImpl(url, {
      mode: 'cors',
      credentials: 'omit',
      cache: 'no-store',
      signal: controller.signal,
    }))
    .then(async (response) => {
      const contentType = normalizeContentType(response.headers?.get?.('content-type'));
      const httpStatus = safeHttpStatus(response.status);
      if (!response.ok || !SUPPORTED_IMAGE_CONTENT_TYPES.has(contentType)) {
        return { terminationReason: 'http_error', contentType, httpStatus, blob: null };
      }
      const blob = await response.blob();
      return { terminationReason: 'success', contentType, httpStatus, blob };
    })
    .catch((error) => ({
      terminationReason: isAbortError(error) ? 'aborted' : 'network_error',
      contentType: null,
      httpStatus: null,
      blob: null,
    }));

  const deadlineOutcome = new Promise((resolve) => {
    deadlineHandle = setTimeoutImpl(() => {
      deadlineTriggered = true;
      try {
        controller.abort();
      } catch {
        // The deadline result must still settle even if a nonstandard controller rejects abort.
      }
      resolve({
        terminationReason: 'timeout',
        contentType: null,
        httpStatus: null,
        blob: null,
      });
    }, deadlineMs);
  });

  const outcome = await Promise.race([requestOutcome, deadlineOutcome]);
  clearTimeoutImpl(deadlineHandle);
  const fetchCompletedAt = now();

  return {
    originalIndex,
    terminationReason: outcome.terminationReason,
    fetchSucceeded: outcome.terminationReason === 'success',
    abortIssued: deadlineTriggered && controller.signal.aborted,
    contentType: outcome.contentType ?? '-',
    httpStatus: outcome.httpStatus,
    blob: outcome.blob,
    fetchStartedAt,
    fetchCompletedAt,
    fetchLatencyMs: nonNegativeDuration(fetchCompletedAt - fetchStartedAt),
  };
}

export async function decodeCandidateBlob(candidate, {
  decodeImage,
  now = defaultNow,
}) {
  if (!candidate.fetchSucceeded) {
    return candidate;
  }

  const decodeStartedAt = now();
  try {
    const image = await decodeImage(candidate.blob);
    const decodeCompletedAt = now();
    return {
      ...candidate,
      blob: null,
      image,
      decodeSucceeded: true,
      decodeStartedAt,
      decodeCompletedAt,
      decodeLatencyMs: nonNegativeDuration(decodeCompletedAt - decodeStartedAt),
    };
  } catch {
    const decodeCompletedAt = now();
    return {
      ...candidate,
      blob: null,
      image: null,
      terminationReason: 'decode_error',
      decodeSucceeded: false,
      decodeStartedAt,
      decodeCompletedAt,
      decodeLatencyMs: nonNegativeDuration(decodeCompletedAt - decodeStartedAt),
    };
  }
}

function defaultNow() {
  return performance.now();
}

function normalizeContentType(value) {
  return typeof value === 'string' ? value.split(';', 1)[0].trim().toLowerCase() : '-';
}

function safeHttpStatus(value) {
  return Number.isInteger(value) && value >= 0 ? value : null;
}

function isAbortError(error) {
  return error?.name === 'AbortError' || error?.code === 'ABORT_ERR';
}

function nonNegativeDuration(value) {
  return Number.isFinite(value) && value >= 0 ? value : 0;
}
