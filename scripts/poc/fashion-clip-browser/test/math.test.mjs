import test from 'node:test';
import assert from 'node:assert/strict';
import {
  extractBrowserReranking,
  fetchRecommendation,
  withBenchmarkJsonBody,
} from '../src/backend.js';
import { buildBrowserPerformanceTrace } from '../src/baseline-trace.js';
import {
  CANDIDATE_IMAGE_FETCH_DEADLINE_MS,
  decodeCandidateBlob,
  fetchCandidateBlob,
} from '../src/candidate-fetch.js';
import {
  calculateFinalScore,
  compareRerankingResults,
  cosineSimilarity,
  l2Norm,
  normalizeL2,
  selectRelevanceTopCandidates,
  sortDisplayResults,
  sortRerankingResults,
  validateBrowserRerankingHandoff,
} from '../src/math.js';

test('identical vectors have cosine similarity near one', () => {
  assert.ok(Math.abs(cosineSimilarity([1, 2, 3], [1, 2, 3]) - 1) < 1e-12);
});

test('orthogonal vectors have zero cosine similarity', () => {
  assert.equal(cosineSimilarity([1, 0], [0, 1]), 0);
});

test('diagnostics reject invalid embeddings', () => {
  assert.throws(() => l2Norm([Number.NaN]), /finite/);
  assert.throws(() => l2Norm([0, 0]), /zero/);
  assert.throws(() => cosineSimilarity([1], [1, 2]), /dimensions/);
});

test('L2 normalization preserves dimension and produces unit norm', () => {
  const normalized = normalizeL2([3, 4]);

  assert.ok(normalized instanceof Float32Array);
  assert.equal(normalized.length, 2);
  assert.ok(Math.abs(l2Norm(normalized) - 1) < 1e-6);
  assert.ok(normalized.every(Number.isFinite));
});

test('L2 normalization rejects zero and non-finite embeddings', () => {
  assert.throws(() => normalizeL2([0, 0]), /zero/);
  assert.throws(() => normalizeL2([1, Number.POSITIVE_INFINITY]), /finite/);
});

test('L2 normalization preserves cosine similarity within float error', () => {
  const left = [2, -1, 4];
  const right = [-3, 5, 1];

  const rawCosine = cosineSimilarity(left, right);
  const normalizedCosine = cosineSimilarity(normalizeL2(left), normalizeL2(right));

  assert.ok(Math.abs(rawCosine - normalizedCosine) < 1e-6);
});

function handoff(candidates) {
  return validateBrowserRerankingHandoff({
    browserReranking: {
      category: 'TOP',
      candidates,
    },
  });
}

function candidate(candidateId, tagSimilarity = 0.5, imageUrl = 'https://example.com/image.jpg') {
  return {
    candidateId,
    imageUrl,
    tagSimilarity,
    name: `Product ${candidateId}`,
    sellerName: null,
    price: null,
    purchaseUrl: null,
  };
}

function imageResponse({
  ok = true,
  status = 200,
  contentType = 'image/jpeg',
  blob = new Blob(['image'], { type: contentType }),
} = {}) {
  return {
    ok,
    status,
    headers: {
      get(name) {
        return name.toLowerCase() === 'content-type' ? contentType : null;
      },
    },
    blob: async () => blob,
  };
}

test('candidate fetch keeps the direct no-store request and records a successful terminal reason', async () => {
  let receivedUrl;
  let receivedOptions;
  const result = await fetchCandidateBlob({
    url: 'https://cdn.example/image.jpg',
    originalIndex: 2,
    deadlineMs: 50,
    fetchImpl: async (url, options) => {
      receivedUrl = url;
      receivedOptions = options;
      return imageResponse();
    },
  });

  assert.equal(receivedUrl, 'https://cdn.example/image.jpg');
  assert.equal(receivedOptions.mode, 'cors');
  assert.equal(receivedOptions.credentials, 'omit');
  assert.equal(receivedOptions.cache, 'no-store');
  assert.ok(receivedOptions.signal instanceof AbortSignal);
  assert.equal(result.originalIndex, 2);
  assert.equal(result.terminationReason, 'success');
  assert.equal(result.fetchSucceeded, true);
  assert.equal(result.abortIssued, false);
  assert.equal(result.contentType, 'image/jpeg');
  assert.ok(result.blob instanceof Blob);
});

test('candidate fetch aborts a never-resolving request at the explicit deadline', async () => {
  let abortObserved = false;
  const started = performance.now();
  const result = await fetchCandidateBlob({
    url: 'https://cdn.example/never.jpg',
    originalIndex: 3,
    deadlineMs: 10,
    fetchImpl: (_url, options) => new Promise(() => {
      options.signal.addEventListener('abort', () => {
        abortObserved = options.signal.aborted;
      }, { once: true });
    }),
  });

  assert.equal(result.terminationReason, 'timeout');
  assert.equal(result.fetchSucceeded, false);
  assert.equal(result.abortIssued, true);
  assert.equal(abortObserved, true);
  assert.ok(performance.now() - started < 250);
});

test('candidate fetch also bounds a never-resolving image body transfer', async () => {
  let abortObserved = false;
  const result = await fetchCandidateBlob({
    url: 'https://cdn.example/never-body.jpg',
    originalIndex: 4,
    deadlineMs: 10,
    fetchImpl: async (_url, options) => {
      options.signal.addEventListener('abort', () => {
        abortObserved = options.signal.aborted;
      }, { once: true });
      return {
        ...imageResponse(),
        blob: () => new Promise(() => {}),
      };
    },
  });

  assert.equal(result.terminationReason, 'timeout');
  assert.equal(result.fetchSucceeded, false);
  assert.equal(result.abortIssued, true);
  assert.equal(abortObserved, true);
});

test('candidate fetch classifies explicit abort, HTTP, and network failures without payloads', async () => {
  const abortError = new Error('request cancelled');
  abortError.name = 'AbortError';
  const [aborted, httpError, networkError] = await Promise.all([
    fetchCandidateBlob({
      url: 'https://cdn.example/aborted.jpg',
      originalIndex: 1,
      deadlineMs: 50,
      fetchImpl: async () => { throw abortError; },
    }),
    fetchCandidateBlob({
      url: 'https://cdn.example/error.jpg',
      originalIndex: 2,
      deadlineMs: 50,
      fetchImpl: async () => imageResponse({ ok: false, status: 503 }),
    }),
    fetchCandidateBlob({
      url: 'https://cdn.example/network.jpg',
      originalIndex: 3,
      deadlineMs: 50,
      fetchImpl: async () => { throw new Error('network down'); },
    }),
  ]);

  assert.equal(aborted.terminationReason, 'aborted');
  assert.equal(httpError.terminationReason, 'http_error');
  assert.equal(httpError.httpStatus, 503);
  assert.equal(networkError.terminationReason, 'network_error');
  assert.doesNotMatch(JSON.stringify([aborted, httpError, networkError]), /cdn\.example/);
});

test('candidate decode error clears its blob and becomes a terminal decode_error', async () => {
  const fetched = await fetchCandidateBlob({
    url: 'https://cdn.example/invalid-image.jpg',
    originalIndex: 5,
    deadlineMs: 50,
    fetchImpl: async () => imageResponse(),
  });
  const decoded = await decodeCandidateBlob(fetched, {
    decodeImage: async () => { throw new Error('decode failed'); },
  });

  assert.equal(decoded.terminationReason, 'decode_error');
  assert.equal(decoded.decodeSucceeded, false);
  assert.equal(decoded.blob, null);
  assert.equal(decoded.image, null);
});

test('mixed candidate fetches all settle within the slowest configured deadline', async () => {
  let timeoutAbortObserved = false;
  const started = performance.now();
  const results = await Promise.all([
    fetchCandidateBlob({
      url: 'https://cdn.example/ok.jpg',
      originalIndex: 1,
      deadlineMs: 15,
      fetchImpl: async () => imageResponse(),
    }),
    fetchCandidateBlob({
      url: 'https://cdn.example/http.jpg',
      originalIndex: 2,
      deadlineMs: 15,
      fetchImpl: async () => imageResponse({ ok: false, status: 404 }),
    }),
    fetchCandidateBlob({
      url: 'https://cdn.example/never.jpg',
      originalIndex: 3,
      deadlineMs: 15,
      fetchImpl: (_url, options) => new Promise(() => {
        options.signal.addEventListener('abort', () => {
          timeoutAbortObserved = options.signal.aborted;
        }, { once: true });
      }),
    }),
    fetchCandidateBlob({
      url: 'https://cdn.example/network.jpg',
      originalIndex: 4,
      deadlineMs: 15,
      fetchImpl: async () => { throw new Error('offline'); },
    }),
  ]);

  assert.deepEqual(results.map((result) => result.terminationReason), [
    'success',
    'http_error',
    'timeout',
    'network_error',
  ]);
  assert.equal(timeoutAbortObserved, true);
  assert.ok(performance.now() - started < 250);
});

test('uses a conservative explicit direct Shopify image deadline for the PoC', () => {
  assert.equal(CANDIDATE_IMAGE_FETCH_DEADLINE_MS, 30_000);
});

test('validates a one-to-thirty candidate handoff and preserves original input indexes', () => {
  const validated = handoff([candidate('a'), candidate('b', 1)]);

  assert.equal(validated.candidates.length, 2);
  assert.deepEqual(validated.candidates.map((item) => item.originalIndex), [1, 2]);
  assert.equal(validated.candidates[1].tagSimilarity, 1);
});

test('rejects missing handoff, empty or oversized candidate pools', () => {
  assert.throws(() => validateBrowserRerankingHandoff({}), /browserReranking/);
  assert.throws(() => handoff([]), /between 1 and 30/);
  assert.throws(() => handoff(Array.from({ length: 31 }, (_, index) => candidate(String(index)))), /between 1 and 30/);
});

test('rejects blank ids, invalid image URLs, invalid tag similarity, and duplicate ids', () => {
  assert.throws(() => handoff([candidate(' ')]), /candidateId/);
  assert.throws(() => handoff([candidate('a', 0.5, 'data:image/png;base64,abc')]), /http or https/);
  assert.throws(() => handoff([candidate('a', Number.NaN)]), /finite/);
  assert.throws(() => handoff([candidate('a', -0.1)]), /between 0 and 1/);
  assert.throws(() => handoff([candidate('a', 1.1)]), /between 0 and 1/);
  assert.throws(() => handoff([candidate('a'), candidate('a')]), /duplicate candidateId/);
});

test('accepts nullable display metadata and rejects fabricated or malformed values', () => {
  const validated = handoff([candidate('nullable')]);

  assert.equal(validated.candidates[0].sellerName, null);
  assert.equal(validated.candidates[0].price, null);
  assert.equal(validated.candidates[0].purchaseUrl, null);
  assert.throws(() => handoff([{ ...candidate('bad-price'), price: { amount: '100', currency: 'KRW' } }]), /amount/);
  assert.throws(() => handoff([{ ...candidate('bad-url'), purchaseUrl: 'javascript:alert(1)' }]), /http or https/);
});

test('calculates the demo final score with the positive 70/30 hypothesis', () => {
  assert.ok(Math.abs(calculateFinalScore(0.8, 0.3) - 0.65) < 1e-12);
  assert.ok(Math.abs(calculateFinalScore(0.9, 0) - 0.63) < 1e-12);
  assert.ok(Math.abs(calculateFinalScore(0.9, 1) - 0.93) < 1e-12);
});

test('selects relevance top K by finalScore without mutating input', () => {
  const candidates = [
    { candidateId: 'low', finalScore: 0.2, originalIndex: 1 },
    { candidateId: 'best', finalScore: 0.9, originalIndex: 3 },
    { candidateId: 'middle', finalScore: 0.5, originalIndex: 2 },
  ];

  const selected = selectRelevanceTopCandidates(candidates, 2);

  assert.deepEqual(selected.map((candidate) => candidate.candidateId), ['best', 'middle']);
  assert.deepEqual(candidates.map((candidate) => candidate.candidateId), ['low', 'best', 'middle']);
});

test('sorts the relevance shortlist by ascending same-currency price', () => {
  const sorted = sortDisplayResults([
    { candidateId: 'expensive', finalScore: 0.9, originalIndex: 1, price: { amount: 100, currency: 'KRW' } },
    { candidateId: 'cheap', finalScore: 0.8, originalIndex: 2, price: { amount: 20, currency: 'KRW' } },
  ]);

  assert.deepEqual(sorted.map((candidate) => candidate.candidateId), ['cheap', 'expensive']);
});

test('keeps relevance order for mixed currencies and missing prices', () => {
  const mixedCurrency = sortDisplayResults([
    { candidateId: 'krw-expensive', finalScore: 0.9, originalIndex: 1, price: { amount: 100, currency: 'KRW' } },
    { candidateId: 'usd', finalScore: 0.8, originalIndex: 2, price: { amount: 1, currency: 'USD' } },
    { candidateId: 'krw-cheap', finalScore: 0.7, originalIndex: 3, price: { amount: 1, currency: 'KRW' } },
  ]);
  const missingPrice = sortDisplayResults([
    { candidateId: 'priced-expensive', finalScore: 0.9, originalIndex: 1, price: { amount: 100, currency: 'KRW' } },
    { candidateId: 'missing', finalScore: 0.8, originalIndex: 2, price: null },
    { candidateId: 'priced-cheap', finalScore: 0.7, originalIndex: 3, price: { amount: 1, currency: 'KRW' } },
  ]);

  assert.deepEqual(mixedCurrency.map((candidate) => candidate.candidateId), [
    'krw-expensive',
    'usd',
    'krw-cheap',
  ]);
  assert.deepEqual(missingPrice.map((candidate) => candidate.candidateId), [
    'priced-expensive',
    'missing',
    'priced-cheap',
  ]);
});

test('uses finalScore DESC then original input index for equal comparable prices', () => {
  const sorted = sortDisplayResults([
    { candidateId: 'tie-late', finalScore: 0.5, originalIndex: 4, price: { amount: 20, currency: 'KRW' } },
    { candidateId: 'tie-early', finalScore: 0.5, originalIndex: 3, price: { amount: 20, currency: 'KRW' } },
    { candidateId: 'higher-score', finalScore: 0.9, originalIndex: 9, price: { amount: 20, currency: 'KRW' } },
  ]);

  assert.deepEqual(sorted.map((candidate) => candidate.candidateId), [
    'higher-score',
    'tie-early',
    'tie-late',
  ]);
});

test('compares reduced ranking overlap, rank changes, and excluded full-ranking positions', () => {
  const fullRanked = Array.from({ length: 10 }, (_, index) => ({
    candidateId: `candidate-${index + 1}`,
    originalIndex: index + 1,
  }));
  const reducedRanked = [2, 3, 1, 5, 12, 6, 4, 8, 10, 13].map((index) => ({
    candidateId: `candidate-${index}`,
    originalIndex: index,
  }));

  const comparison = compareRerankingResults(fullRanked, reducedRanked);

  assert.equal(comparison.overlapCount, 8);
  assert.deepEqual(comparison.topOverlap, { 3: 3, 5: 4, 10: 8 });
  assert.equal(comparison.rankChangeCount, 6);
  assert.deepEqual(comparison.excludedInFullTop, { 3: 0, 5: 0, 10: 2 });
  assert.deepEqual(comparison.rankChanges.find((change) => change.originalIndex === 2), {
    originalIndex: 2,
    fullRank: 2,
    reducedRank: 1,
    delta: -1,
  });
});

test('keeps cosine scale unchanged and accepts tag similarity endpoints', () => {
  assert.equal(calculateFinalScore(-0.4, 0), -0.27999999999999997);
  assert.equal(calculateFinalScore(1, 1), 1);
});

test('sorts by final score descending and keeps stable original input order on ties', () => {
  const sorted = sortRerankingResults([
    { candidateId: 'second', originalIndex: 2, finalScore: 0.5 },
    { candidateId: 'first', originalIndex: 1, finalScore: 0.9 },
    { candidateId: 'tie-late', originalIndex: 4, finalScore: 0.5 },
    { candidateId: 'tie-early', originalIndex: 3, finalScore: 0.5 },
  ]);

  assert.deepEqual(sorted.map((result) => result.candidateId), [
    'first',
    'second',
    'tie-early',
    'tie-late',
  ]);
});

test('fetches the recommendation POST with an optional bearer token and preserves status', async () => {
  const calls = [];
  const result = await fetchRecommendation({
    baseUrl: 'http://localhost:8080/',
    reportId: '4',
    accessToken: ' local-token ',
    fetchImpl: async (url, options) => {
      calls.push({ url, options });
      return {
        status: 200,
        ok: true,
        json: async () => ({ data: { recommendationStatus: 'CURRENT' } }),
      };
    },
  });

  assert.equal(calls.length, 1);
  assert.equal(
    calls[0].url.href,
    'http://localhost:8080/api/v1/analyses/4/recommendations'
  );
  assert.deepEqual(calls[0].options, {
    method: 'POST',
    headers: {
      Accept: 'application/json',
      Authorization: 'Bearer local-token',
    },
  });
  assert.equal(result.status, 200);
  assert.equal(result.ok, true);
});

test('keeps a JSON POST body and headers when the recommendation URL is an object', async () => {
  const calls = [];
  const requestBody = {
    confirmedTagIds: [],
    customTagNames: ['black hoodie'],
    matchPercentage: 50,
  };

  await fetchRecommendation({
    baseUrl: 'http://localhost:8080',
    reportId: 4,
    accessToken: 'local-token',
    benchmarkTrace: true,
    requestBody,
    fetchImpl: async (url, options) => {
      calls.push({ url, options });
      return {
        status: 200,
        ok: true,
        json: async () => ({ data: {} }),
      };
    },
  });

  assert.ok(calls[0].url instanceof URL);
  assert.equal(calls[0].url.href, 'http://localhost:8080/api/v1/analyses/4/recommendations');
  assert.equal(calls[0].options.method, 'POST');
  assert.equal(calls[0].options.headers.Accept, 'application/json');
  assert.equal(calls[0].options.headers.Authorization, 'Bearer local-token');
  assert.equal(calls[0].options.headers['X-Fitback-Benchmark-Trace'], 'baseline-v1');
  assert.equal(calls[0].options.headers['Content-Type'], 'application/json');
  assert.equal(calls[0].options.body, JSON.stringify(requestBody));
  assert.ok(new TextEncoder().encode(calls[0].options.body).byteLength > 0);
});

test('does not attach a benchmark body to GET or an existing request body', () => {
  const requestBody = { customTagNames: ['black hoodie'] };
  const getOptions = { method: 'GET', headers: { Accept: 'application/json' } };
  const existingPost = {
    method: 'POST',
    headers: { Accept: 'application/json' },
    body: '{"existing":true}',
  };

  assert.equal(withBenchmarkJsonBody(getOptions, requestBody), getOptions);
  assert.equal(withBenchmarkJsonBody(existingPost, requestBody), existingPost);
});

test('requests the opt-in backend trace without exposing the access token in its result', async () => {
  const calls = [];
  const result = await fetchRecommendation({
    baseUrl: 'http://localhost:8080',
    reportId: 4,
    accessToken: 'local-token',
    benchmarkTrace: true,
    fetchImpl: async (url, options) => {
      calls.push({ url, options });
      return {
        status: 200,
        ok: true,
        headers: new Headers({ 'X-Fitback-Benchmark-Trace-Id': 'trace-123' }),
        json: async () => ({ data: {} }),
      };
    },
  });

  assert.equal(calls[0].options.headers['X-Fitback-Benchmark-Trace'], 'baseline-v1');
  assert.equal(result.benchmarkTraceId, 'trace-123');
  assert.doesNotMatch(JSON.stringify(result), /local-token/);
});

test('normalizes integer-like report IDs before building the recommendation URL', async () => {
  const calls = [];
  await fetchRecommendation({
    baseUrl: 'http://localhost:8080',
    reportId: '4.0',
    fetchImpl: async (url) => {
      calls.push(url);
      return {
        status: 200,
        ok: true,
        json: async () => ({ data: {} }),
      };
    },
  });

  assert.equal(calls[0].href, 'http://localhost:8080/api/v1/analyses/4/recommendations');
});

test('classifies missing handoff and non-success recommendation responses as fallback', async () => {
  const missing = extractBrowserReranking({
    data: { recommendationStatus: 'CURRENT', recommendationGroups: [] },
  });
  assert.equal(missing.kind, 'fallback');
  assert.match(missing.reason, /no browserReranking/);
  assert.equal(missing.backendData.recommendationStatus, 'CURRENT');

  const unavailable = await fetchRecommendation({
    baseUrl: 'http://localhost:8080',
    reportId: 4,
    fetchImpl: async () => ({
      status: 503,
      ok: false,
      json: async () => ({ code: 'PRODUCT503_1', data: null }),
    }),
  });
  assert.equal(unavailable.status, 503);
  assert.equal(unavailable.ok, false);
  assert.equal(extractBrowserReranking(unavailable.payload).kind, 'fallback');
});

test('forwards a valid handoff without interpreting its opaque candidate ID', () => {
  const opaqueId = 'v1.opaque-member-bound-token/with.unparsed.parts';
  const extracted = extractBrowserReranking({
    data: {
      browserReranking: {
        category: 'TOP',
        candidates: [{
          candidateId: opaqueId,
          imageUrl: 'https://cdn.example/item.jpg',
          tagSimilarity: 0.5,
          name: 'Opaque product',
          sellerName: null,
          price: null,
          purchaseUrl: null,
        }],
      },
    },
  });

  assert.equal(extracted.kind, 'ready');
  assert.equal(
    extracted.handoff.browserReranking.candidates[0].candidateId,
    opaqueId
  );
});

test('builds one safe structured browser performance trace without candidate payloads', () => {
  const trace = buildBrowserPerformanceTrace({
    outcome: 'SUCCESS',
    browserE2EWallClockMs: 1800.25,
    backendRequest: {
      status: 200,
      latencyMs: 450.5,
      benchmarkTraceId: '1c468b6b-d72c-4c75-a48c-8c4916a5f8ee',
    },
    modelConfig: {
      source: 'pinned remote default',
      url: 'https://huggingface.co/Frapic/fashion-clip-onnx/resolve/pinned/vision_model.onnx?signature=secret',
    },
    modelReadiness: {
      cacheState: 'cold',
      waitWallClockMs: 900.4,
      loadRun: {
        headersMs: 12.5,
        downloadMs: 400.25,
        bytes: 123456,
        contentLength: '123456',
        contentType: 'application/octet-stream; charset=binary',
        cacheControl: 'public, max-age=3600, x-token=secret',
        sessionCreationMs: 480.75,
        totalMs: 900.4,
        requestedProviders: ['webgpu', 'wasm'],
        providerResolution: 'unexposed-by-onnxruntime-web',
        fallbackUsed: false,
      },
    },
    reranking: {
      selectedCandidates: [{
        candidateId: 'opaque-candidate-token',
        imageUrl: 'https://cdn.example/private-image.jpg',
        embedding: [0.1, 0.2],
      }],
      metrics: {
        queryDecodeWallClockMs: 10,
        queryPreprocessWallClockMs: 20,
        queryAcquisitionWallClockMs: 30,
        fetchWallClockMs: 100,
        fetchCumulativeMs: 160,
        candidateFetchRequestCount: 1,
        candidateImageFetchSuccessCount: 1,
        candidateImageFetchFailureCount: 0,
        candidateFetchDeadlineMs: 30000,
        candidateFetchMaxLatencyMs: 100,
        candidateTerminationSuccessCount: 1,
        candidateFetchHttpErrorCount: 0,
        candidateFetchTimeoutCount: 0,
        candidateFetchNetworkErrorCount: 0,
        candidateFetchAbortedCount: 0,
        candidateDecodeErrorCount: 0,
        candidateFetchTerminations: [{
          originalIndex: 1,
          reason: 'success',
          fetchLatencyMs: 100,
          candidateId: 'opaque-candidate-token',
          imageUrl: 'https://cdn.example/private-image.jpg',
        }],
        decodeWallClockMs: 12,
        decodeCumulativeMs: 12,
        preprocessWallClockMs: 16,
        preprocessCumulativeMs: 16,
        queryInferenceWallClockMs: 44,
        candidateBatchInferenceWallClockMs: 55,
        cosineWallClockMs: 2,
        finalScoreCalculationWallClockMs: 1,
        sortingWallClockMs: 3,
        renderUpdateWallClockMs: 4,
        totalRerankingWallClockMs: 320,
      },
    },
  });

  assert.equal(trace.backend.requestCount, 1);
  assert.equal(trace.backend.requestWallClockMs, 450.5);
  assert.equal(trace.model.url, 'https://huggingface.co/Frapic/fashion-clip-onnx/resolve/pinned/vision_model.onnx');
  assert.equal(trace.model.transferredBytes, 123456);
  assert.equal(trace.model.contentType, 'application/octet-stream');
  assert.equal(trace.model.cacheControl, 'public, max-age=3600');
  assert.equal(trace.model.provider.resolution, 'unexposed-by-onnxruntime-web');
  assert.deepEqual(trace.candidates.imageFetch, {
    requestCount: 1,
    successCount: 1,
    failureCount: 0,
    wallClockMs: 100,
    cumulativeMs: 160,
    deadlineMs: 30000,
    slowestRequestLatencyMs: 100,
    outcomes: {
      successCount: 1,
      httpErrorCount: 0,
      timeoutCount: 0,
      networkErrorCount: 0,
      abortedCount: 0,
      decodeErrorCount: 0,
    },
    terminations: [{
      originalIndex: 1,
      reason: 'success',
      fetchLatencyMs: 100,
    }],
  });
  assert.equal(trace.total.browserRerankingWallClockMs, 320);
  assert.equal(trace.total.browserE2EWallClockMs, 1800.25);
  const serialized = JSON.stringify(trace);
  assert.doesNotMatch(serialized, /opaque-candidate-token|private-image|embedding|signature|secret/);
});

test('keeps timeout terminal reasons safe on the fail-closed browser reranking trace', () => {
  const trace = buildBrowserPerformanceTrace({
    outcome: 'BROWSER_RERANKING_FAILED',
    browserE2EWallClockMs: 30010,
    backendRequest: { status: 200, latencyMs: 100 },
    reranking: {
      selectedCandidates: [{
        candidateId: 'opaque-candidate-token',
        imageUrl: 'https://cdn.example/private-image.jpg',
      }],
      metrics: {
        candidateFetchRequestCount: 1,
        candidateImageFetchSuccessCount: 0,
        candidateImageFetchFailureCount: 1,
        candidateFetchDeadlineMs: 30000,
        candidateFetchMaxLatencyMs: 30000,
        candidateTerminationSuccessCount: 0,
        candidateFetchHttpErrorCount: 0,
        candidateFetchTimeoutCount: 1,
        candidateFetchNetworkErrorCount: 0,
        candidateFetchAbortedCount: 0,
        candidateDecodeErrorCount: 0,
        candidateFetchTerminations: [{
          originalIndex: 1,
          reason: 'timeout',
          fetchLatencyMs: 30000,
          candidateId: 'opaque-candidate-token',
          imageUrl: 'https://cdn.example/private-image.jpg',
        }],
      },
    },
  });

  assert.equal(trace.outcome, 'BROWSER_RERANKING_FAILED');
  assert.equal(trace.candidates.imageFetch.outcomes.timeoutCount, 1);
  assert.deepEqual(trace.candidates.imageFetch.terminations, [{
    originalIndex: 1,
    reason: 'timeout',
    fetchLatencyMs: 30000,
  }]);
  assert.doesNotMatch(JSON.stringify(trace), /opaque-candidate-token|private-image/);
});
