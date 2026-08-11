export function l2Norm(values) {
  if (!values || values.length === 0) {
    throw new Error('embedding must not be null or empty');
  }

  let squared = 0;
  for (const value of values) {
    if (!Number.isFinite(value)) {
      throw new Error('embedding values must be finite');
    }
    squared += value * value;
  }
  if (squared === 0) {
    throw new Error('zero embedding is not supported');
  }
  return Math.sqrt(squared);
}

export function normalizeL2(values) {
  const norm = l2Norm(values);
  if (!Number.isFinite(norm) || norm === 0) {
    throw new Error('embedding norm must be finite and non-zero');
  }

  const normalized = new Float32Array(values.length);
  for (let index = 0; index < values.length; index += 1) {
    normalized[index] = values[index] / norm;
  }
  return normalized;
}

export function cosineSimilarity(left, right) {
  if (!left || !right || left.length === 0 || right.length === 0) {
    throw new Error('embeddings must not be null or empty');
  }
  if (left.length !== right.length) {
    throw new Error('embedding dimensions must match');
  }

  let dot = 0;
  let leftSquared = 0;
  let rightSquared = 0;
  for (let index = 0; index < left.length; index += 1) {
    const leftValue = left[index];
    const rightValue = right[index];
    if (!Number.isFinite(leftValue) || !Number.isFinite(rightValue)) {
      throw new Error('embedding values must be finite');
    }
    dot += leftValue * rightValue;
    leftSquared += leftValue * leftValue;
    rightSquared += rightValue * rightValue;
  }
  if (leftSquared === 0 || rightSquared === 0) {
    throw new Error('zero embedding is not supported');
  }
  return dot / (Math.sqrt(leftSquared) * Math.sqrt(rightSquared));
}
