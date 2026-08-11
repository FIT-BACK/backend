import test from 'node:test';
import assert from 'node:assert/strict';
import { cosineSimilarity, l2Norm, normalizeL2 } from '../src/math.js';

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
