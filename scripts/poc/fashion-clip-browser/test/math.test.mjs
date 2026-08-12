import test from 'node:test';
import assert from 'node:assert/strict';
import {
  calculateFinalScore,
  cosineSimilarity,
  l2Norm,
  normalizeL2,
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
