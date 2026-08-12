import test from 'node:test';
import assert from 'node:assert/strict';
import { extractBrowserReranking, fetchRecommendation } from '../src/backend.js';
import {
  calculateFinalScore,
  compareRerankingResults,
  cosineSimilarity,
  l2Norm,
  normalizeL2,
  selectTagSimilarityTopCandidates,
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
  return { candidateId, imageUrl, tagSimilarity };
}

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

test('calculates the demo final score with the positive 70/30 hypothesis', () => {
  assert.ok(Math.abs(calculateFinalScore(0.8, 0.3) - 0.65) < 1e-12);
  assert.ok(Math.abs(calculateFinalScore(0.9, 0) - 0.63) < 1e-12);
  assert.ok(Math.abs(calculateFinalScore(0.9, 1) - 0.93) < 1e-12);
});

test('selects tagSimilarity top candidates without mutating input and keeps original-index ties', () => {
  const candidates = [
    { candidateId: 'late', tagSimilarity: 0.9, originalIndex: 5 },
    { candidateId: 'low', tagSimilarity: 0.2, originalIndex: 1 },
    { candidateId: 'early', tagSimilarity: 0.9, originalIndex: 2 },
  ];

  const selected = selectTagSimilarityTopCandidates(candidates, 2);

  assert.deepEqual(selected.map((candidate) => candidate.candidateId), ['early', 'late']);
  assert.deepEqual(candidates.map((candidate) => candidate.candidateId), ['late', 'low', 'early']);
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
