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

const MAX_HANDOFF_CANDIDATES = 30;
const IMAGE_WEIGHT = 0.70;
const TAG_WEIGHT = 0.30;

export function validateBrowserRerankingHandoff(value) {
  if (!value || typeof value !== 'object' || Array.isArray(value)) {
    throw new Error('handoff must be an object');
  }
  const handoff = value.browserReranking;
  if (!handoff || typeof handoff !== 'object' || Array.isArray(handoff)) {
    throw new Error('handoff.browserReranking is required');
  }
  if (!Array.isArray(handoff.candidates)
      || handoff.candidates.length < 1
      || handoff.candidates.length > MAX_HANDOFF_CANDIDATES) {
    throw new Error('handoff candidates must contain between 1 and 30 items');
  }

  const candidateIds = new Set();
  const candidates = handoff.candidates.map((candidate, index) => {
    if (!candidate || typeof candidate !== 'object' || Array.isArray(candidate)) {
      throw new Error(`candidate ${index + 1} must be an object`);
    }
    if (typeof candidate.candidateId !== 'string' || candidate.candidateId.trim() === '') {
      throw new Error(`candidate ${index + 1} candidateId must be nonblank`);
    }
    if (candidateIds.has(candidate.candidateId)) {
      throw new Error(`duplicate candidateId at input index ${index + 1}`);
    }
    candidateIds.add(candidate.candidateId);

    const imageUrl = parseHttpUrl(candidate.imageUrl, `candidate ${index + 1} imageUrl`);
    if (typeof candidate.tagSimilarity !== 'number'
        || !Number.isFinite(candidate.tagSimilarity)
        || candidate.tagSimilarity < 0
        || candidate.tagSimilarity > 1) {
      throw new Error(`candidate ${index + 1} tagSimilarity must be finite and between 0 and 1`);
    }
    return {
      candidateId: candidate.candidateId,
      imageUrl,
      tagSimilarity: candidate.tagSimilarity,
      originalIndex: index + 1,
    };
  });
  return {
    category: typeof handoff.category === 'string' ? handoff.category : null,
    candidates,
  };
}

function parseHttpUrl(value, label) {
  if (typeof value !== 'string' || value.trim() === '') {
    throw new Error(`${label} must be a valid http or https URL`);
  }
  let parsed;
  try {
    parsed = new URL(value);
  } catch {
    throw new Error(`${label} must be a valid http or https URL`);
  }
  if (!['http:', 'https:'].includes(parsed.protocol)) {
    throw new Error(`${label} must be a valid http or https URL`);
  }
  return parsed.href;
}

export function calculateFinalScore(imageSimilarity, tagSimilarity) {
  if (!Number.isFinite(imageSimilarity)) {
    throw new Error('imageSimilarity must be finite');
  }
  if (!Number.isFinite(tagSimilarity) || tagSimilarity < 0 || tagSimilarity > 1) {
    throw new Error('tagSimilarity must be finite and between 0 and 1');
  }
  return imageSimilarity * IMAGE_WEIGHT + tagSimilarity * TAG_WEIGHT;
}

export function sortRerankingResults(results) {
  return [...results].sort((left, right) => {
    const scoreDifference = right.finalScore - left.finalScore;
    return scoreDifference || left.originalIndex - right.originalIndex;
  });
}
