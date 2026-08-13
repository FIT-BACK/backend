import * as ort from 'onnxruntime-web/webgpu';
import { extractBrowserReranking, fetchRecommendation } from './backend.js';
import { buildBrowserPerformanceTrace, logBrowserPerformanceTrace } from './baseline-trace.js';
import {
  calculateFinalScore,
  compareRerankingResults,
  cosineSimilarity,
  l2Norm,
  normalizeL2,
  selectRelevanceTopCandidates,
  sortDisplayResults,
  validateBrowserRerankingHandoff,
} from './math.js';

const MODEL_ID = 'Frapic/fashion-clip-onnx';
const MODEL_REVISION = '12eb79267363fd03b8983a25903cd9097b1ec76c';
const DEFAULT_MODEL_URL = `https://huggingface.co/${MODEL_ID}/resolve/${MODEL_REVISION}/vision_model.onnx`;
const ALLOWED_MODEL_ORIGINS = new Set([window.location.origin, 'https://huggingface.co']);

function resolveModelConfig() {
  const override = new URLSearchParams(window.location.search).get('modelUrl');
  if (!override) {
    return { url: DEFAULT_MODEL_URL, source: 'pinned remote default' };
  }
  try {
    const url = new URL(override, window.location.origin);
    if (!['http:', 'https:'].includes(url.protocol)) {
      throw new Error('modelUrl must use http or https');
    }
    if (!ALLOWED_MODEL_ORIGINS.has(url.origin)) {
      throw new Error('modelUrl origin is not allowed');
    }
    return { url: url.href, source: `query override (${url.origin})` };
  } catch {
    return { url: DEFAULT_MODEL_URL, source: 'pinned remote default (invalid override ignored)' };
  }
}

const MODEL_CONFIG = resolveModelConfig();
const MODEL_URL = MODEL_CONFIG.url;
const BENCHMARK_CUSTOM_TAG_PARAM = 'benchmarkCustomTag';

function resolveBenchmarkRecommendationRequest() {
  const customTagName = new URLSearchParams(window.location.search)
    .get(BENCHMARK_CUSTOM_TAG_PARAM)
    ?.trim();
  if (!customTagName || customTagName.length > 50) {
    return null;
  }
  return {
    confirmedTagIds: [],
    customTagNames: [customTagName],
    matchPercentage: 50,
  };
}

const BENCHMARK_RECOMMENDATION_REQUEST = resolveBenchmarkRecommendationRequest();
const WASM_PATH = 'https://cdn.jsdelivr.net/npm/onnxruntime-web@1.27.0/dist/';
const IMAGE_SIZE = 224;
const BENCHMARK_SIZES = [1, 3, 5, 10];
const BENCHMARK_REPETITIONS = 3;
const HANDOFF_BENCHMARK_REPETITIONS = 3;
const DEFAULT_DEMO_CANDIDATE_LIMIT = 10;
const NORMALIZED_NORM_TOLERANCE = 1e-5;
const CHANNEL_MEAN = [0.48145466, 0.4578275, 0.40821073];
const CHANNEL_STD = [0.26862954, 0.26130258, 0.27577711];

ort.env.wasm.wasmPaths = WASM_PATH;

const elements = {
  queryFile: document.querySelector('#query-file'),
  candidateFile: document.querySelector('#candidate-file'),
  candidateUrls: document.querySelector('#candidate-urls'),
  backendUrl: document.querySelector('#backend-url'),
  reportId: document.querySelector('#report-id'),
  accessToken: document.querySelector('#access-token'),
  run: document.querySelector('#run'),
  benchmarkRun: document.querySelector('#benchmark-run'),
  urlRun: document.querySelector('#url-run'),
  backendRun: document.querySelector('#backend-run'),
  backendBenchmarkRun: document.querySelector('#backend-benchmark-run'),
  status: document.querySelector('#status'),
  runtime: document.querySelector('#runtime'),
  model: document.querySelector('#model'),
  modelSource: document.querySelector('#model-source'),
  modelLoad: document.querySelector('#model-load'),
  modelSecondLoad: document.querySelector('#model-second-load'),
  modelArtifactSummary: document.querySelector('#model-artifact-summary'),
  modelLoadRuns: document.querySelector('#model-load-runs tbody'),
  inference: document.querySelector('#inference'),
  rawCosine: document.querySelector('#raw-cosine'),
  normalizedCosine: document.querySelector('#normalized-cosine'),
  cosineDifference: document.querySelector('#cosine-difference'),
  urlSummary: document.querySelector('#url-summary'),
  urlResults: document.querySelector('#url-results tbody'),
  backendSummary: document.querySelector('#backend-summary'),
  handoffSummary: document.querySelector('#handoff-summary'),
  handoffResults: document.querySelector('#handoff-results tbody'),
  handoffBenchmarkSummary: document.querySelector('#handoff-benchmark-summary'),
  handoffQualitySummary: document.querySelector('#handoff-quality-summary'),
  handoffComparisonOrdering: document.querySelector('#handoff-comparison-ordering'),
  benchmarkResults: document.querySelector('#benchmark-results tbody'),
  query: {
    dimension: document.querySelector('#query-dimension'),
    finite: document.querySelector('#query-finite'),
    rawNorm: document.querySelector('#query-raw-norm'),
    normalizedNorm: document.querySelector('#query-normalized-norm'),
  },
  candidate: {
    dimension: document.querySelector('#candidate-dimension'),
    finite: document.querySelector('#candidate-finite'),
    rawNorm: document.querySelector('#candidate-raw-norm'),
    normalizedNorm: document.querySelector('#candidate-normalized-norm'),
  },
};

elements.modelSource.textContent = MODEL_CONFIG.source;

let sessionPromise;
let runtimeState = 'unknown';
let fallbackUsed = false;
const modelLoadRuns = [];

function setStatus(message) {
  elements.status.textContent = message;
}

async function loadSession() {
  if (!sessionPromise) {
    sessionPromise = createSession();
  }
  return sessionPromise;
}

async function loadSessionWithReadiness() {
  const cacheState = sessionPromise ? 'warm' : 'cold';
  const started = performance.now();
  const session = await loadSession();
  return {
    session,
    modelReadiness: {
      cacheState,
      waitWallClockMs: performance.now() - started,
      loadRun: cacheState === 'cold' ? modelLoadRuns.at(-1) : null,
      requestedProviders: runtimeState === 'wasm' ? ['wasm'] : ['webgpu', 'wasm'],
      providerResolution: runtimeState === 'wasm'
        ? 'wasm'
        : 'unexposed-by-onnxruntime-web',
      fallbackUsed,
    },
  };
}

async function createSession() {
  runtimeState = 'unknown';
  fallbackUsed = false;
  const started = performance.now();
  const run = {
    run: modelLoadRuns.length + 1,
    urlResolveMs: 0,
    webgpuInitMs: 0,
    headersMs: 0,
    downloadMs: 0,
    bytes: 0,
    contentLength: null,
    contentType: null,
    cacheControl: null,
    sessionCreationMs: 0,
    totalMs: 0,
    redirected: false,
    requestedProviders: [],
    providerResolution: 'unavailable',
    fallbackUsed: false,
  };
  const urlStarted = performance.now();
  const resolvedModelUrl = new URL(MODEL_URL, document.baseURI).href;
  run.urlResolveMs = performance.now() - urlStarted;
  const canTryWebGpu = 'gpu' in navigator;
  const requestedProviders = canTryWebGpu ? ['webgpu', 'wasm'] : ['wasm'];
  run.requestedProviders = requestedProviders;
  const webgpuInitStarted = performance.now();
  if (canTryWebGpu) {
    try {
      const adapter = await navigator.gpu.requestAdapter();
      if (!adapter) {
        throw new Error('WebGPU adapter unavailable');
      }
      const device = await adapter.requestDevice();
      if (typeof device.destroy === 'function') {
        device.destroy();
      }
    } catch (error) {
      setStatus(`WebGPU preflight unavailable (${errorMessage(error)}); session fallback remains armed.`);
    }
  }
  run.webgpuInitMs = performance.now() - webgpuInitStarted;

  const fetchStarted = performance.now();
  const response = await fetch(resolvedModelUrl, { cache: 'default', credentials: 'omit' });
  run.headersMs = performance.now() - fetchStarted;
  if (!response.ok) {
    throw new Error(`model fetch failed with HTTP ${response.status}`);
  }
  run.redirected = response.redirected;
  run.contentLength = response.headers.get('content-length');
  run.contentType = response.headers.get('content-type');
  run.cacheControl = response.headers.get('cache-control');
  const bodyStarted = performance.now();
  const modelBytes = await response.arrayBuffer();
  run.downloadMs = performance.now() - bodyStarted;
  run.bytes = modelBytes.byteLength;
  elements.modelArtifactSummary.textContent = `${MODEL_ID} revision ${MODEL_REVISION}; source ${MODEL_CONFIG.source}; received ${formatBytes(run.bytes)}; Content-Length ${run.contentLength ? formatBytes(Number(run.contentLength)) : 'unavailable'}; final URL redirected ${run.redirected ? 'yes' : 'no'}`;
  try {
    const sessionStarted = performance.now();
    const session = await ort.InferenceSession.create(modelBytes, {
      executionProviders: requestedProviders,
      graphOptimizationLevel: 'all',
    });
    run.sessionCreationMs = performance.now() - sessionStarted;
    runtimeState = canTryWebGpu ? 'webgpu-or-wasm' : 'wasm';
    run.providerResolution = canTryWebGpu
      ? 'unexposed-by-onnxruntime-web'
      : 'wasm';
    elements.runtime.textContent = canTryWebGpu
      ? 'WebGPU/WASM session; selected provider unexposed'
      : 'wasm';
    elements.model.textContent = MODEL_ID;
    run.totalMs = performance.now() - started;
    recordModelLoadRun(run);
    return session;
  } catch (error) {
    if (!canTryWebGpu) {
      throw error;
    }
    setStatus(`WebGPU failed (${errorMessage(error)}); retrying with WASM.`);
    let session;
    try {
      const sessionStarted = performance.now();
      session = await ort.InferenceSession.create(modelBytes, {
        executionProviders: ['wasm'],
        graphOptimizationLevel: 'all',
      });
      run.sessionCreationMs = performance.now() - sessionStarted;
    } catch (fallbackError) {
      throw new Error(`WebGPU: ${errorMessage(error)}; WASM: ${errorMessage(fallbackError)}`);
    }
    runtimeState = 'wasm';
    fallbackUsed = true;
    run.providerResolution = 'wasm';
    run.fallbackUsed = true;
    elements.runtime.textContent = 'wasm (WebGPU unavailable)';
    elements.model.textContent = MODEL_ID;
    run.totalMs = performance.now() - started;
    recordModelLoadRun(run);
    return session;
  }
}

function recordModelLoadRun(run) {
  modelLoadRuns.push(run);
  elements.modelLoad.textContent = `${formatMilliseconds(run.totalMs)} ms`;
  elements.modelSecondLoad.disabled = false;
  const row = document.createElement('tr');
  const cells = [
    run.run,
    formatMilliseconds(run.urlResolveMs),
    formatMilliseconds(run.webgpuInitMs),
    formatMilliseconds(run.headersMs),
    formatMilliseconds(run.downloadMs),
    `${formatBytes(run.bytes)} / ${run.contentLength ? formatBytes(Number(run.contentLength)) : 'unavailable'}`,
    formatMilliseconds(run.sessionCreationMs),
    formatMilliseconds(run.totalMs),
    run.redirected ? 'yes' : 'no',
  ];
  for (const cell of cells) {
    const cellElement = document.createElement('td');
    cellElement.textContent = String(cell);
    row.append(cellElement);
  }
  elements.modelLoadRuns.append(row);
}

function formatBytes(bytes) {
  return `${bytes.toLocaleString('en-US')} B`;
}

function errorMessage(error) {
  return error instanceof Error ? error.message : String(error);
}

async function imageToTensorData(source, label = 'image') {
  const prepared = await imageToTensorDataWithMetrics(source, label);
  return prepared.tensorData;
}

async function imageToTensorDataWithMetrics(source, label = 'image') {
  const decodeStarted = performance.now();
  const image = await createImage(source, label);
  const decodeWallClockMs = performance.now() - decodeStarted;
  const preprocessStarted = performance.now();
  const tensorData = imageToTensorDataFromImage(image);
  const preprocessWallClockMs = performance.now() - preprocessStarted;
  return {
    tensorData,
    metrics: {
      decodeWallClockMs,
      preprocessWallClockMs,
      decodePreprocessWallClockMs: performance.now() - decodeStarted,
    },
  };
}

function imageToTensorDataFromImage(image) {
  try {
    const canvas = document.createElement('canvas');
    canvas.width = IMAGE_SIZE;
    canvas.height = IMAGE_SIZE;
    const context = canvas.getContext('2d', { willReadFrequently: true });
    if (!context) {
      throw new Error('2D canvas is unavailable');
    }

    const scale = IMAGE_SIZE / Math.min(image.width, image.height);
    const width = image.width * scale;
    const height = image.height * scale;
    context.drawImage(image, (IMAGE_SIZE - width) / 2, (IMAGE_SIZE - height) / 2, width, height);

    const pixels = context.getImageData(0, 0, IMAGE_SIZE, IMAGE_SIZE).data;
    const channelLength = IMAGE_SIZE * IMAGE_SIZE;
    const tensorData = new Float32Array(channelLength * 3);
    for (let pixel = 0; pixel < channelLength; pixel += 1) {
      for (let channel = 0; channel < 3; channel += 1) {
        const value = pixels[pixel * 4 + channel] / 255;
        tensorData[channel * channelLength + pixel] = (value - CHANNEL_MEAN[channel]) / CHANNEL_STD[channel];
      }
    }
    return tensorData;
  } finally {
    if (typeof image.close === 'function') {
      image.close();
    }
  }
}

function createImage(source, label = 'image') {
  if ('createImageBitmap' in window) {
    return createImageBitmap(source);
  }
  return new Promise((resolve, reject) => {
    const image = new Image();
    const objectUrl = URL.createObjectURL(source);
    image.onload = () => {
      URL.revokeObjectURL(objectUrl);
      resolve(image);
    };
    image.onerror = () => {
      URL.revokeObjectURL(objectUrl);
      reject(new Error(`could not decode ${label}`));
    };
    image.src = objectUrl;
  });
}

function parseCandidateUrls() {
  return elements.candidateUrls.value
    .split(/\r?\n/)
    .map((url) => url.trim())
    .filter(Boolean)
    .slice(0, 10);
}

function urlHost(url) {
  try {
    return new URL(url).host;
  } catch {
    return 'invalid-url';
  }
}

async function fetchUrlBlob(url, index) {
  const result = {
    index: index + 1,
    host: urlHost(url),
    contentType: '-',
    fetchLatencyMs: 0,
    decodeLatencyMs: 0,
    preprocessLatencyMs: 0,
    fetchSucceeded: false,
    decodeSucceeded: false,
    preprocessSucceeded: false,
    status: 'blocked',
  };
  const fetchStarted = performance.now();
  result.fetchStartedAt = fetchStarted;
  let response;
  try {
    response = await fetch(url, {
      mode: 'cors',
      credentials: 'omit',
      cache: 'no-store',
    });
  } catch (error) {
    result.status = `CORS/network failure: ${errorMessage(error)}`;
    return completeStage(result, 'fetch', fetchStarted);
  }
  result.contentType = response.headers.get('content-type')?.split(';', 1)[0] ?? '-';
  if (!response.ok) {
    result.status = `HTTP ${response.status}`;
    return completeStage(result, 'fetch', fetchStarted);
  }
  if (!['image/jpeg', 'image/png', 'image/webp'].includes(result.contentType)) {
    result.status = `unsupported content type: ${result.contentType}`;
    return completeStage(result, 'fetch', fetchStarted);
  }

  try {
    result.blob = await response.blob();
  } catch (error) {
    result.status = `body read failure: ${errorMessage(error)}`;
    return completeStage(result, 'fetch', fetchStarted);
  }
  result.status = 'fetched';
  result.fetchSucceeded = true;
  return completeStage(result, 'fetch', fetchStarted);
}

async function decodeUrlImage(result) {
  if (result.status !== 'fetched') {
    return result;
  }

  const decodeStarted = performance.now();
  result.decodeStartedAt = decodeStarted;
  try {
    result.image = await createImage(result.blob, `candidate ${result.index}`);
  } catch (error) {
    result.status = `decode failure: ${errorMessage(error)}`;
    return completeStage(result, 'decode', decodeStarted);
  }
  result.blob = null;
  result.status = 'decoded';
  result.decodeSucceeded = true;
  return completeStage(result, 'decode', decodeStarted);
}

async function preprocessUrlImage(result) {
  if (result.status !== 'decoded') {
    return result;
  }

  const preprocessStarted = performance.now();
  result.preprocessStartedAt = preprocessStarted;
  try {
    result.tensorData = imageToTensorDataFromImage(result.image);
  } catch (error) {
    result.status = `preprocess failure: ${errorMessage(error)}`;
    return completeStage(result, 'preprocess', preprocessStarted);
  }
  result.status = 'ready';
  result.preprocessSucceeded = true;
  return completeStage(result, 'preprocess', preprocessStarted);
}

async function fetchCandidateTensorData(candidates) {
  const acquisitionStarted = performance.now();
  const results = await Promise.all(candidates.map(async (candidate) => {
    const fetched = await fetchUrlBlob(candidate.imageUrl, candidate.originalIndex - 1);
    const decoded = await decodeUrlImage(fetched);
    const ready = await preprocessUrlImage(decoded);
    return { ...ready, ...candidate };
  }));

  return {
    results,
    metrics: {
      acquisitionWallClockMs: performance.now() - acquisitionStarted,
      candidateFetchRequestCount: results.length,
      candidateImageFetchSuccessCount: results.filter((result) => result.fetchSucceeded).length,
      candidateImageFetchFailureCount: results.filter((result) => !result.fetchSucceeded).length,
      candidateDecodeRequestCount: results.filter((result) => result.fetchSucceeded).length,
      candidateDecodeSuccessCount: results.filter((result) => result.decodeSucceeded).length,
      candidateDecodeFailureCount: results.filter(
        (result) => result.fetchSucceeded && !result.decodeSucceeded,
      ).length,
      candidatePreprocessRequestCount: results.filter((result) => result.decodeSucceeded).length,
      candidatePreprocessSuccessCount: results.filter((result) => result.preprocessSucceeded).length,
      candidatePreprocessFailureCount: results.filter(
        (result) => result.decodeSucceeded && !result.preprocessSucceeded,
      ).length,
      fetchWallClockMs: stageWallClock(results, 'fetchStartedAt', 'fetchCompletedAt'),
      fetchCumulativeMs: sumMetric(results, 'fetchLatencyMs'),
      decodeWallClockMs: stageWallClock(results, 'decodeStartedAt', 'decodeCompletedAt'),
      decodeCumulativeMs: sumMetric(results, 'decodeLatencyMs'),
      preprocessWallClockMs: stageWallClock(results, 'preprocessStartedAt', 'preprocessCompletedAt'),
      preprocessCumulativeMs: sumMetric(results, 'preprocessLatencyMs'),
    },
  };
}

function readEmbedding(output, index, batchSize) {
  const dimension = output.dims.at(-1);
  if (output.dims.length !== 2 || output.dims[0] !== batchSize || !dimension) {
    throw new Error(`unexpected embedding shape: ${output.dims.join(' × ')} for batch ${batchSize}`);
  }
  return output.data.slice(index * dimension, (index + 1) * dimension);
}

function diagnoseEmbedding(rawEmbedding) {
  const finite = rawEmbedding.every(Number.isFinite);
  if (!finite) {
    throw new Error('embedding values must be finite');
  }
  const normalizedEmbedding = normalizeL2(rawEmbedding);
  const normalizedFinite = normalizedEmbedding.every(Number.isFinite);
  const normalizedNorm = l2Norm(normalizedEmbedding);
  if (!normalizedFinite || Math.abs(normalizedNorm - 1) > NORMALIZED_NORM_TOLERANCE) {
    throw new Error(`normalized embedding norm is not approximately 1: ${normalizedNorm}`);
  }
  return {
    raw: rawEmbedding,
    normalized: normalizedEmbedding,
    dimension: rawEmbedding.length,
    finite,
    rawNorm: l2Norm(rawEmbedding),
    normalizedNorm,
  };
}

function showDiagnostics(target, diagnostic) {
  target.dimension.textContent = String(diagnostic.dimension);
  target.finite.textContent = String(diagnostic.finite);
  target.rawNorm.textContent = diagnostic.rawNorm.toFixed(6);
  target.normalizedNorm.textContent = diagnostic.normalizedNorm.toFixed(6);
}

function createBatchData(tensorDataList) {
  if (tensorDataList.length === 0) {
    throw new Error('cannot create an empty tensor batch');
  }
  const itemLength = tensorDataList[0].length;
  const batchData = new Float32Array(itemLength * tensorDataList.length);
  tensorDataList.forEach((tensorData, index) => {
    if (tensorData.length !== itemLength) {
      throw new Error('tensor batch items must have the same size');
    }
    batchData.set(tensorData, index * itemLength);
  });
  return batchData;
}

async function runEmbeddingBatch(session, tensorDataList) {
  const batchSize = tensorDataList.length;
  const batchData = createBatchData(tensorDataList);
  const inputName = session.inputNames[0];
  const outputName = session.outputNames[0];
  const input = new ort.Tensor('float32', batchData, [batchSize, 3, IMAGE_SIZE, IMAGE_SIZE]);
  const started = performance.now();
  const outputs = await session.run({ [inputName]: input });
  const latencyMs = performance.now() - started;
  const output = outputs[outputName];
  if (!output) {
    throw new Error(`missing model output: ${outputName}`);
  }
  const rawEmbeddings = Array.from({ length: batchSize }, (_, index) => readEmbedding(output, index, batchSize));
  return { latencyMs, rawEmbeddings };
}

function calculateCosines(queryDiagnostic, candidateDiagnostics) {
  const started = performance.now();
  const raw = candidateDiagnostics.map((candidate) => cosineSimilarity(queryDiagnostic.raw, candidate.raw));
  const normalized = candidateDiagnostics.map((candidate) => cosineSimilarity(queryDiagnostic.normalized, candidate.normalized));
  return {
    raw,
    normalized,
    latencyMs: performance.now() - started,
  };
}

async function prepareInputs(queryFile, candidateFiles) {
  const [queryData, candidateData] = await Promise.all([
    imageToTensorData(queryFile),
    Promise.all(candidateFiles.map((file) => imageToTensorData(file))),
  ]);
  return { queryData, candidateData };
}

async function runComparison(queryFile, candidateFile) {
  const session = await loadSession();
  const { queryData, candidateData } = await prepareInputs(queryFile, [candidateFile]);
  const queryRun = await runEmbeddingBatch(session, [queryData]);
  const candidateRun = await runEmbeddingBatch(session, candidateData);
  const queryDiagnostic = diagnoseEmbedding(queryRun.rawEmbeddings[0]);
  const candidateDiagnostic = diagnoseEmbedding(candidateRun.rawEmbeddings[0]);
  const cosines = calculateCosines(queryDiagnostic, [candidateDiagnostic]);
  const totalInferenceMs = queryRun.latencyMs + candidateRun.latencyMs;

  elements.inference.textContent = `${formatMilliseconds(totalInferenceMs)} ms`;
  showDiagnostics(elements.query, queryDiagnostic);
  showDiagnostics(elements.candidate, candidateDiagnostic);
  showCosines(cosines, 0);
}

async function runBenchmarkIteration(session, queryData, candidateData) {
  const queryRun = await runEmbeddingBatch(session, [queryData]);
  const candidateRun = await runEmbeddingBatch(session, candidateData);
  const queryDiagnostic = diagnoseEmbedding(queryRun.rawEmbeddings[0]);
  const candidateDiagnostics = candidateRun.rawEmbeddings.map(diagnoseEmbedding);
  const cosines = calculateCosines(queryDiagnostic, candidateDiagnostics);
  const normalizedCosineMean = mean(cosines.normalized);
  const maxCosineDifference = Math.max(
    ...cosines.raw.map((rawCosine, index) => Math.abs(rawCosine - cosines.normalized[index])),
  );
  return {
    queryDiagnostic,
    candidateDiagnostics,
    queryLatencyMs: queryRun.latencyMs,
    candidateLatencyMs: candidateRun.latencyMs,
    totalInferenceMs: queryRun.latencyMs + candidateRun.latencyMs,
    perImageMs: (queryRun.latencyMs + candidateRun.latencyMs) / (candidateData.length + 1),
    cosineLatencyMs: cosines.latencyMs,
    normalizedCosineMean,
    maxCosineDifference,
  };
}

async function runBenchmark(queryFile, candidateFiles) {
  if (candidateFiles.length < Math.max(...BENCHMARK_SIZES)) {
    throw new Error(`select at least ${Math.max(...BENCHMARK_SIZES)} candidate images for the benchmark`);
  }

  const session = await loadSession();
  const { queryData, candidateData } = await prepareInputs(queryFile, candidateFiles.slice(0, Math.max(...BENCHMARK_SIZES)));
  const rows = [];
  elements.benchmarkResults.replaceChildren();

  for (const candidateCount of BENCHMARK_SIZES) {
    setStatus(`Warming candidate batch size ${candidateCount}…`);
    const selectedCandidateData = candidateData.slice(0, candidateCount);
    await runBenchmarkIteration(session, queryData, selectedCandidateData);

    setStatus(`Measuring candidate batch size ${candidateCount} (${BENCHMARK_REPETITIONS} runs)…`);
    const measuredRuns = [];
    for (let repetition = 0; repetition < BENCHMARK_REPETITIONS; repetition += 1) {
      measuredRuns.push(await runBenchmarkIteration(session, queryData, selectedCandidateData));
    }
    const row = summarizeBenchmarkRuns(candidateCount, measuredRuns);
    rows.push(row);
    appendBenchmarkRow(row);

    if (candidateCount === 1) {
      showDiagnostics(elements.query, measuredRuns[0].queryDiagnostic);
      showDiagnostics(elements.candidate, measuredRuns[0].candidateDiagnostics[0]);
      showCosines({
        raw: [cosineSimilarity(measuredRuns[0].queryDiagnostic.raw, measuredRuns[0].candidateDiagnostics[0].raw)],
        normalized: [cosineSimilarity(measuredRuns[0].queryDiagnostic.normalized, measuredRuns[0].candidateDiagnostics[0].normalized)],
      }, 0);
    }
  }

  const lastRow = rows.at(-1);
  elements.inference.textContent = `${formatMilliseconds(lastRow.totalInferenceMs)} ms median (${lastRow.candidateCount} candidates)`;
  return rows;
}

async function runUrlIntegration(queryFile, urls) {
  const session = await loadSession();
  const started = performance.now();
  const queryData = await imageToTensorData(queryFile, queryFile.name);
  setStatus(`Fetching, decoding, and preprocessing ${urls.length} candidate image URL(s) in parallel…`);
  const acquisition = await fetchCandidateTensorData(urls.map((imageUrl, index) => ({
    imageUrl,
    originalIndex: index + 1,
  })));
  const { results } = acquisition;
  const ready = results.filter((result) => result.status === 'ready');
  if (ready.length === 0) {
    renderUrlResults(results);
    throw new Error('no candidate URL passed direct browser fetch/decode/preprocess');
  }

  setStatus(`Running Fashion-CLIP candidate batch [${ready.length},3,224,224]…`);
  const queryRun = await runEmbeddingBatch(session, [queryData]);
  const candidateRun = await runEmbeddingBatch(session, ready.map((result) => result.tensorData));
  const queryDiagnostic = diagnoseEmbedding(queryRun.rawEmbeddings[0]);
  const candidateDiagnostics = candidateRun.rawEmbeddings.map(diagnoseEmbedding);
  const cosines = calculateCosines(queryDiagnostic, candidateDiagnostics);
  ready.forEach((result, index) => {
    result.cosine = cosines.normalized[index];
  });
  const totalRerankingMs = performance.now() - started;
  elements.urlSummary.textContent = [
    `success ${ready.length}/${results.length}`,
    `CORS/network failures ${results.filter((result) => result.status.startsWith('CORS/network')).length}`,
    `fetch cumulative ${formatMilliseconds(acquisition.metrics.fetchCumulativeMs)} ms / wall ${formatMilliseconds(acquisition.metrics.fetchWallClockMs)} ms`,
    `decode cumulative ${formatMilliseconds(acquisition.metrics.decodeCumulativeMs)} ms / wall ${formatMilliseconds(acquisition.metrics.decodeWallClockMs)} ms`,
    `preprocess cumulative ${formatMilliseconds(acquisition.metrics.preprocessCumulativeMs)} ms / wall ${formatMilliseconds(acquisition.metrics.preprocessWallClockMs)} ms`,
    `query batch ${formatMilliseconds(queryRun.latencyMs)} ms`,
    `candidate batch [${ready.length},3,224,224] ${formatMilliseconds(candidateRun.latencyMs)} ms`,
    `cosine ${formatMilliseconds(cosines.latencyMs)} ms`,
    `total reranking ${formatMilliseconds(totalRerankingMs)} ms`,
  ].join(' · ');
  renderUrlResults(results);
  showDiagnostics(elements.query, queryDiagnostic);
  showDiagnostics(elements.candidate, candidateDiagnostics[0]);
  showCosines(cosines, 0);
  elements.inference.textContent = `${formatMilliseconds(queryRun.latencyMs + candidateRun.latencyMs)} ms`;
}

async function runHandoffRerankingPass(session, queryFile, handoff, candidateLimit, { render = false } = {}) {
  const totalStarted = performance.now();
  const selectionStarted = performance.now();
  const selectedCandidates = [...handoff.candidates];
  const candidateSelectionWallClockMs = performance.now() - selectionStarted;

  const queryAcquisitionStarted = performance.now();
  let queryMetrics = {
    decodeWallClockMs: 0,
    preprocessWallClockMs: 0,
    decodePreprocessWallClockMs: 0,
  };
  const queryDataPromise = (async () => {
    const prepared = await imageToTensorDataWithMetrics(queryFile, queryFile.name);
    queryMetrics = prepared.metrics;
    return prepared.tensorData;
  })();
  setStatus(`Fetching ${selectedCandidates.length}/${handoff.candidates.length} handoff image URL(s) directly in parallel…`);
  const acquisitionPromise = fetchCandidateTensorData(selectedCandidates);
  const [queryData, acquisition] = await Promise.all([queryDataPromise, acquisitionPromise]);
  const concurrentAcquisitionWallClockMs = performance.now() - queryAcquisitionStarted;
  const { results } = acquisition;
  const failed = results.filter((result) => result.status !== 'ready');
  const readyCount = results.length - failed.length;
  const baseMetrics = {
    candidateSelectionWallClockMs,
    queryDecodeWallClockMs: queryMetrics.decodeWallClockMs,
    queryPreprocessWallClockMs: queryMetrics.preprocessWallClockMs,
    queryAcquisitionWallClockMs: queryMetrics.decodePreprocessWallClockMs,
    concurrentAcquisitionWallClockMs,
    ...acquisition.metrics,
    fetchSuccessCount: readyCount,
    fetchFailureCount: failed.length,
  };

  if (failed.length > 0) {
    let renderUpdateWallClockMs = 0;
    if (render) {
      const renderStarted = performance.now();
      renderHandoffResults(results);
      renderUpdateWallClockMs = performance.now() - renderStarted;
    }
    const error = new Error(`candidate image fetch/decode failed at input index ${failed.map((result) => result.originalIndex).join(', ')}`);
    error.reranking = {
      selectedCandidates,
      results,
      runtime: runtimeState,
      metrics: {
        ...baseMetrics,
        queryInferenceWallClockMs: 0,
        candidateBatchInferenceWallClockMs: 0,
        diagnosticsWallClockMs: 0,
        cosineWallClockMs: 0,
        finalScoreCalculationWallClockMs: 0,
        sortingWallClockMs: 0,
        renderUpdateWallClockMs,
        totalRerankingWallClockMs: performance.now() - totalStarted,
      },
    };
    throw error;
  }

  setStatus(`Running Fashion-CLIP query and candidate batches [1] + [${results.length},3,224,224]…`);
  const queryRun = await runEmbeddingBatch(session, [queryData]);
  const candidateRun = await runEmbeddingBatch(session, results.map((result) => result.tensorData));
  const diagnosticsStarted = performance.now();
  const queryDiagnostic = diagnoseEmbedding(queryRun.rawEmbeddings[0]);
  const candidateDiagnostics = candidateRun.rawEmbeddings.map(diagnoseEmbedding);
  const diagnosticsWallClockMs = performance.now() - diagnosticsStarted;

  const cosineStarted = performance.now();
  const cosines = calculateCosines(queryDiagnostic, candidateDiagnostics);
  const cosineWallClockMs = performance.now() - cosineStarted;

  const scoreStarted = performance.now();
  const scored = results.map((result, index) => ({
    candidateId: result.candidateId,
    originalIndex: result.originalIndex,
    imageUrl: result.imageUrl,
    name: result.name,
    sellerName: result.sellerName,
    price: result.price,
    purchaseUrl: result.purchaseUrl,
    imageSimilarity: cosines.normalized[index],
    tagSimilarity: result.tagSimilarity,
    finalScore: calculateFinalScore(cosines.normalized[index], result.tagSimilarity),
  }));
  const finalScoreCalculationWallClockMs = performance.now() - scoreStarted;

  const sortingStarted = performance.now();
  const relevanceRanked = selectRelevanceTopCandidates(scored, scored.length);
  const relevanceShortlist = relevanceRanked.slice(0, Math.min(candidateLimit, relevanceRanked.length));
  const displayRanked = sortDisplayResults(relevanceShortlist);
  const sortingWallClockMs = performance.now() - sortingStarted;

  let renderUpdateWallClockMs = 0;
  if (render) {
    const renderStarted = performance.now();
    renderHandoffResults(results, displayRanked);
    showDiagnostics(elements.query, queryDiagnostic);
    showDiagnostics(elements.candidate, candidateDiagnostics[0]);
    showCosines(cosines, 0);
    elements.inference.textContent = `${formatMilliseconds(queryRun.latencyMs + candidateRun.latencyMs)} ms`;
    renderUpdateWallClockMs = performance.now() - renderStarted;
  }

  const metrics = {
    ...baseMetrics,
    queryInferenceWallClockMs: queryRun.latencyMs,
    candidateBatchInferenceWallClockMs: candidateRun.latencyMs,
    diagnosticsWallClockMs,
    cosineWallClockMs,
    finalScoreCalculationWallClockMs,
    sortingWallClockMs,
    renderUpdateWallClockMs,
    totalRerankingWallClockMs: performance.now() - totalStarted,
  };
  return {
    selectedCandidates,
    results,
    ranked: displayRanked,
    relevanceRanked,
    relevanceShortlist,
    displayRanked,
    runtime: runtimeState,
    metrics,
    ranges: {
      imageSimilarity: range(displayRanked.map((result) => result.imageSimilarity)),
      tagSimilarity: range(displayRanked.map((result) => result.tagSimilarity)),
      finalScore: range(displayRanked.map((result) => result.finalScore)),
    },
  };
}

async function runHandoffIntegration(queryFile, handoff, backendRequest) {
  const { session, modelReadiness } = await loadSessionWithReadiness();
  try {
    const pass = await runHandoffRerankingPass(
      session,
      queryFile,
      handoff,
      DEFAULT_DEMO_CANDIDATE_LIMIT,
      { render: true },
    );
    const tracedPass = { ...pass, modelReadiness };
    elements.handoffSummary.textContent = formatHandoffPassSummary(tracedPass, handoff, backendRequest);
    return tracedPass;
  } catch (error) {
    if (error.reranking) {
      error.reranking.modelReadiness = modelReadiness;
      elements.handoffSummary.textContent = formatHandoffFailureSummary(
        error.reranking,
        handoff,
        backendRequest,
      );
    }
    throw error;
  }
}

async function runHandoffBenchmark(queryFile, handoff, backendRequest) {
  const session = await loadSession();
  const paths = [
    { label: 'full relevance/display-30', candidateLimit: handoff.candidates.length },
    { label: 'relevance top-10/display-price', candidateLimit: Math.min(DEFAULT_DEMO_CANDIDATE_LIMIT, handoff.candidates.length) },
  ];
  const summaries = [];

  for (const path of paths) {
    try {
      setStatus(`Warming ${path.label} path once; model session remains warm…`);
      await runHandoffRerankingPass(session, queryFile, handoff, path.candidateLimit);
      const runs = [];
      for (let repetition = 0; repetition < HANDOFF_BENCHMARK_REPETITIONS; repetition += 1) {
        setStatus(`Measuring ${path.label} path (${repetition + 1}/${HANDOFF_BENCHMARK_REPETITIONS})…`);
        runs.push(await runHandoffRerankingPass(
          session,
          queryFile,
          handoff,
          path.candidateLimit,
          { render: true },
        ));
      }
      summaries.push(summarizeHandoffRuns(path, runs));
    } catch (error) {
      if (error.reranking) {
        error.reranking.pathLabel = path.label;
      }
      throw error;
    }
  }

  const fullSummary = summaries[0];
  const reducedSummary = summaries[1];
  const comparison = compareRerankingResults(
    fullSummary.representative.ranked,
    reducedSummary.representative.ranked,
  );
  renderHandoffBenchmark(fullSummary, reducedSummary, comparison, handoff, backendRequest);
  return { summaries, comparison };
}

function summarizeHandoffRuns(path, runs) {
  const metricNames = [
    'candidateSelectionWallClockMs',
    'queryPreprocessWallClockMs',
    'fetchWallClockMs',
    'fetchCumulativeMs',
    'decodeWallClockMs',
    'decodeCumulativeMs',
    'preprocessWallClockMs',
    'preprocessCumulativeMs',
    'queryInferenceWallClockMs',
    'candidateBatchInferenceWallClockMs',
    'diagnosticsWallClockMs',
    'cosineWallClockMs',
    'finalScoreCalculationWallClockMs',
    'sortingWallClockMs',
    'renderUpdateWallClockMs',
    'totalRerankingWallClockMs',
  ];
  const medianMetrics = Object.fromEntries(metricNames.map((name) => [
    name,
    median(runs.map((run) => run.metrics[name])),
  ]));
  return {
    label: path.label,
    candidateCount: runs[0].selectedCandidates.length,
    shortlistCount: runs[0].relevanceShortlist.length,
    runtime: runs[0].runtime,
    fetchSuccessCounts: runs.map((run) => run.metrics.fetchSuccessCount),
    fetchFailureCounts: runs.map((run) => run.metrics.fetchFailureCount),
    medianMetrics,
    runs,
    representative: runs.at(-1),
  };
}

function formatHandoffPassSummary(pass, handoff, backendRequest) {
  const metrics = pass.metrics;
  const modelLoad = modelLoadRuns.at(-1);
  return [
    `backend HTTP ${backendRequest.status}`,
    `backend request ${formatMilliseconds(backendRequest.latencyMs)} ms`,
    `category ${handoff.category ?? '-'}`,
    `handoff candidates ${handoff.candidates.length}`,
    `metadata completeness ${metadataCompleteness(pass.selectedCandidates)}`,
    `relevance shortlist ${pass.relevanceShortlist.length}`,
    `image fetch ${metrics.fetchSuccessCount} success / ${metrics.fetchFailureCount} failure`,
    `model readiness ${modelLoad ? formatMilliseconds(modelLoad.totalMs) + ' ms' : 'cached'} (${runtimeState})`,
    `imageSimilarity ${pass.ranges.imageSimilarity}`,
    `tagSimilarity ${pass.ranges.tagSimilarity}`,
    `finalScore ${pass.ranges.finalScore}`,
    formatRerankingMetrics(metrics),
    `relevance order ${formatRelevanceOrdering(pass.relevanceShortlist)}`,
    `display price order ${formatDisplayOrdering(pass.displayRanked)}`,
  ].join(' · ');
}

function formatHandoffFailureSummary(failure, handoff, backendRequest) {
  const metrics = failure.metrics;
  return [
    `backend HTTP ${backendRequest.status}`,
    `handoff candidates ${handoff.candidates.length}`,
    `selected ${failure.selectedCandidates.length}`,
    `image fetch ${metrics.fetchSuccessCount} success / ${metrics.fetchFailureCount} failure`,
    'browser-reranking unavailable; backend result kept',
    'no partial reranking was calculated',
    formatRerankingMetrics(metrics),
  ].join(' · ');
}

function formatHandoffBenchmarkFailureSummary(failure, handoff, backendRequest) {
  return [
    `benchmark ${failure.pathLabel ?? 'path'} failed`,
    formatHandoffFailureSummary(failure, handoff, backendRequest),
    'remaining benchmark path not measured; backend result kept',
  ].join('\n');
}

function formatRerankingMetrics(metrics) {
  return [
    `selection wall ${formatMilliseconds(metrics.candidateSelectionWallClockMs)} ms`,
    `query preprocess wall ${formatMilliseconds(metrics.queryPreprocessWallClockMs)} ms`,
    `image fetch cumulative ${formatMilliseconds(metrics.fetchCumulativeMs)} ms / wall ${formatMilliseconds(metrics.fetchWallClockMs)} ms`,
    `decode cumulative ${formatMilliseconds(metrics.decodeCumulativeMs)} ms / wall ${formatMilliseconds(metrics.decodeWallClockMs)} ms`,
    `preprocess cumulative ${formatMilliseconds(metrics.preprocessCumulativeMs)} ms / wall ${formatMilliseconds(metrics.preprocessWallClockMs)} ms`,
    `query inference wall ${formatMilliseconds(metrics.queryInferenceWallClockMs)} ms`,
    `candidate batch wall ${formatMilliseconds(metrics.candidateBatchInferenceWallClockMs)} ms`,
    `cosine wall ${formatMilliseconds(metrics.cosineWallClockMs)} ms`,
    `finalScore wall ${formatMilliseconds(metrics.finalScoreCalculationWallClockMs)} ms`,
    `sort wall ${formatMilliseconds(metrics.sortingWallClockMs)} ms`,
    `render/update wall ${formatMilliseconds(metrics.renderUpdateWallClockMs)} ms`,
    `total reranking wall ${formatMilliseconds(metrics.totalRerankingWallClockMs)} ms`,
  ].join(' · ');
}

function renderHandoffBenchmark(fullSummary, reducedSummary, comparison, handoff, backendRequest) {
  const modelLoad = modelLoadRuns.at(-1);
  elements.handoffBenchmarkSummary.textContent = [
    `same backend HTTP ${backendRequest.status}; handoff pool ${handoff.candidates.length}; measured ${HANDOFF_BENCHMARK_REPETITIONS} runs/path after one warm-up/path`,
    `model readiness ${modelLoad ? formatMilliseconds(modelLoad.totalMs) + ' ms' : 'cached'}; runtime ${runtimeState}; WebGPU fallback ${fallbackUsed ? 'used' : 'not used'}`,
    formatHandoffBenchmarkPath(fullSummary),
    formatHandoffBenchmarkPath(reducedSummary),
  ].join('\n');
  elements.handoffSummary.textContent = [
    `benchmark complete · backend HTTP ${backendRequest.status} · handoff candidates ${handoff.candidates.length}`,
    `full relevance/display-30 finalScore ${fullSummary.representative.ranges.finalScore}`,
    `relevance top-10/display-price finalScore ${reducedSummary.representative.ranges.finalScore}`,
    `runtime ${runtimeState} · WebGPU fallback ${fallbackUsed ? 'used' : 'not used'} · browser score persistence none`,
  ].join(' · ');
  elements.handoffQualitySummary.textContent = [
    `final ranking overlap: top 3 ${comparison.topOverlap[3]}/3 · top 5 ${comparison.topOverlap[5]}/5 · top 10 ${comparison.topOverlap[10]}/10 · common ${comparison.overlapCount}`,
    `common-candidate rank changes ${comparison.rankChangeCount}/${comparison.overlapCount}`,
    `display top-10 selected from the same full finalScore relevance ranking; price-only reorder is applied after selection`,
  ].join('\n');
  elements.handoffComparisonOrdering.textContent = [
    `full relevance top 10 (input-index:finalScore): ${formatTopOrdering(fullSummary.representative.relevanceShortlist)}`,
    `display price order (input-index:price): ${formatDisplayOrdering(reducedSummary.representative.displayRanked)}`,
    `full relevance measured orders: ${fullSummary.runs.map((run) => formatRelevanceOrdering(run.relevanceShortlist)).join(' | ')}`,
    `display price measured orders: ${reducedSummary.runs.map((run) => formatDisplayOrdering(run.displayRanked)).join(' | ')}`,
  ].join('\n');
}

function formatHandoffBenchmarkPath(summary) {
  const metrics = summary.medianMetrics;
  const runValues = (metricName) => summary.runs.map((run) => formatMilliseconds(run.metrics[metricName])).join('/');
  return [
    `${summary.label} reranked ${summary.candidateCount}; relevance shortlist ${summary.shortlistCount}; fetch success/failure ${summary.fetchSuccessCounts.join('/')} / ${summary.fetchFailureCounts.join('/')}`,
    `selection ${formatMilliseconds(metrics.candidateSelectionWallClockMs)} ms; fetch wall ${runValues('fetchWallClockMs')} ms (median ${formatMilliseconds(metrics.fetchWallClockMs)}); fetch cumulative ${formatMilliseconds(metrics.fetchCumulativeMs)} ms`,
    `decode cumulative ${formatMilliseconds(metrics.decodeCumulativeMs)} ms / wall ${formatMilliseconds(metrics.decodeWallClockMs)} ms; preprocess cumulative ${formatMilliseconds(metrics.preprocessCumulativeMs)} ms / wall ${formatMilliseconds(metrics.preprocessWallClockMs)} ms; query preprocess wall ${formatMilliseconds(metrics.queryPreprocessWallClockMs)} ms`,
    `query inference ${formatMilliseconds(metrics.queryInferenceWallClockMs)} ms; candidate batch ${formatMilliseconds(metrics.candidateBatchInferenceWallClockMs)} ms; cosine ${formatMilliseconds(metrics.cosineWallClockMs)} ms; finalScore ${formatMilliseconds(metrics.finalScoreCalculationWallClockMs)} ms`,
    `sort ${formatMilliseconds(metrics.sortingWallClockMs)} ms; render/update ${formatMilliseconds(metrics.renderUpdateWallClockMs)} ms; total reranking median ${formatMilliseconds(metrics.totalRerankingWallClockMs)} ms`,
    `ranges imageSimilarity ${summary.representative.ranges.imageSimilarity}; tagSimilarity ${summary.representative.ranges.tagSimilarity}; finalScore ${summary.representative.ranges.finalScore}`,
  ].join('\n');
}

function formatTopOrdering(ranked) {
  return ranked
    .slice(0, 10)
    .map((result) => `${result.originalIndex}:${result.finalScore.toFixed(8)}`)
    .join(' > ');
}

function metadataCompleteness(candidates) {
  const complete = candidates.filter((candidate) => (
    typeof candidate.name === 'string'
      && typeof candidate.imageUrl === 'string'
      && Object.hasOwn(candidate, 'sellerName')
      && Object.hasOwn(candidate, 'price')
      && Object.hasOwn(candidate, 'purchaseUrl')
  )).length;
  return `${complete}/${candidates.length}`;
}

function formatRelevanceOrdering(ranked) {
  return ranked
    .map((result) => `${result.originalIndex}:${result.finalScore.toFixed(8)}`)
    .join(' > ');
}

function formatDisplayOrdering(ranked) {
  return ranked
    .map((result) => `${result.originalIndex}:${formatPrice(result.price)}`)
    .join(' > ');
}

function formatPrice(price) {
  if (!price
      || typeof price.amount !== 'number'
      || !Number.isFinite(price.amount)
      || typeof price.currency !== 'string') {
    return '-';
  }
  return `${price.amount} ${price.currency}`;
}

function summarizeBackendResult(backendData) {
  if (!backendData) {
    return 'backend recommendation result unavailable';
  }
  const groups = Array.isArray(backendData.recommendationGroups)
    ? backendData.recommendationGroups
    : [];
  const itemCount = groups.reduce(
    (count, group) => count + (Array.isArray(group.items) ? group.items.length : 0),
    0
  );
  return [
    `backend result kept: status ${backendData.recommendationStatus ?? '-'}`,
    `groups ${groups.length}`,
    `items ${itemCount}`,
    `scoreVersion ${backendData.scoreVersion ?? '-'}`,
  ].join(' · ');
}

function showBrowserUnavailable(reason, backendData = null, preserveMetrics = false) {
  elements.backendSummary.textContent = summarizeBackendResult(backendData);
  if (!preserveMetrics) {
    elements.handoffResults.replaceChildren();
    elements.handoffSummary.textContent = [
      `browser-reranking unavailable: ${reason}`,
      'backend recommendation result kept',
    ].join(' · ');
  }
  setStatus(`Browser reranking unavailable: ${reason}`);
}

function setIntegrationButtonsDisabled(disabled) {
  elements.run.disabled = disabled;
  elements.benchmarkRun.disabled = disabled;
  elements.urlRun.disabled = disabled;
  elements.backendRun.disabled = disabled;
  elements.backendBenchmarkRun.disabled = disabled;
}

function emitBrowserBaselineTrace({
  outcome,
  startedAt,
  request,
  reranking = null,
  candidateCount = 0,
}) {
  return logBrowserPerformanceTrace(buildBrowserPerformanceTrace({
    outcome,
    browserE2EWallClockMs: performance.now() - startedAt,
    backendRequest: request,
    modelConfig: MODEL_CONFIG,
    modelReadiness: reranking?.modelReadiness,
    reranking: reranking ?? { candidateCount, metrics: {} },
  }));
}

async function runBackendIntegration(queryFile) {
  const browserE2EStarted = performance.now();
  const request = await fetchRecommendation({
    baseUrl: elements.backendUrl.value.trim(),
    reportId: elements.reportId.value.trim(),
    accessToken: elements.accessToken.value,
    benchmarkTrace: true,
    requestBody: BENCHMARK_RECOMMENDATION_REQUEST,
  });
  const extracted = extractBrowserReranking(request.payload);
  elements.backendSummary.textContent = [
    `backend HTTP ${request.status}`,
    `request ${formatMilliseconds(request.latencyMs)} ms`,
    summarizeBackendResult(extracted.backendData),
  ].join(' · ');

  if (!request.ok) {
    showBrowserUnavailable(`backend HTTP ${request.status}`, extracted.backendData);
    emitBrowserBaselineTrace({
      outcome: 'BROWSER_RERANKING_FAILED',
      startedAt: browserE2EStarted,
      request,
    });
    return;
  }
  if (extracted.kind !== 'ready') {
    showBrowserUnavailable(extracted.reason, extracted.backendData);
    emitBrowserBaselineTrace({
      outcome: 'BROWSER_RERANKING_FAILED',
      startedAt: browserE2EStarted,
      request,
    });
    return;
  }

  let handoff;
  try {
    handoff = validateBrowserRerankingHandoff(extracted.handoff);
  } catch (error) {
    showBrowserUnavailable(`invalid backend handoff: ${errorMessage(error)}`, extracted.backendData);
    emitBrowserBaselineTrace({
      outcome: 'BROWSER_RERANKING_FAILED',
      startedAt: browserE2EStarted,
      request,
    });
    return;
  }

  try {
    const pass = await runHandoffIntegration(queryFile, handoff, request);
    emitBrowserBaselineTrace({
      outcome: 'SUCCESS',
      startedAt: browserE2EStarted,
      request,
      reranking: pass,
    });
    setStatus('Done. Recommendation response and browser reranking completed locally.');
  } catch (error) {
    emitBrowserBaselineTrace({
      outcome: 'BROWSER_RERANKING_FAILED',
      startedAt: browserE2EStarted,
      request,
      reranking: error.reranking,
      candidateCount: handoff.candidates.length,
    });
    showBrowserUnavailable(errorMessage(error), extracted.backendData, Boolean(error.reranking));
  }
}

async function runBackendBenchmarkIntegration(queryFile) {
  const request = await fetchRecommendation({
    baseUrl: elements.backendUrl.value.trim(),
    reportId: elements.reportId.value.trim(),
    accessToken: elements.accessToken.value,
    requestBody: BENCHMARK_RECOMMENDATION_REQUEST,
  });
  const extracted = extractBrowserReranking(request.payload);
  elements.backendSummary.textContent = [
    `backend HTTP ${request.status}`,
    `request ${formatMilliseconds(request.latencyMs)} ms`,
    summarizeBackendResult(extracted.backendData),
  ].join(' · ');

  if (!request.ok) {
    showBrowserUnavailable(`backend HTTP ${request.status}`, extracted.backendData);
    return;
  }
  if (extracted.kind !== 'ready') {
    showBrowserUnavailable(extracted.reason, extracted.backendData);
    return;
  }

  let handoff;
  try {
    handoff = validateBrowserRerankingHandoff(extracted.handoff);
  } catch (error) {
    showBrowserUnavailable(`invalid backend handoff: ${errorMessage(error)}`, extracted.backendData);
    return;
  }

  try {
    await runHandoffBenchmark(queryFile, handoff, request);
    setStatus('Done. Same recommendation handoff was measured for full relevance and price-ordered top-10.');
  } catch (error) {
    if (error.reranking) {
      elements.handoffSummary.textContent = formatHandoffBenchmarkFailureSummary(
        error.reranking,
        handoff,
        request,
      );
      elements.handoffBenchmarkSummary.textContent = [
        `benchmark unavailable · ${error.reranking.pathLabel ?? 'path'} failed`,
        formatRerankingMetrics(error.reranking.metrics),
        'backend recommendation result kept; no browser score persisted',
      ].join('\n');
      elements.handoffQualitySummary.textContent = 'Final-ranking comparison unavailable; backend result kept.';
      elements.handoffComparisonOrdering.textContent = 'Final ordering unavailable because browser reranking failed.';
    }
    showBrowserUnavailable(errorMessage(error), extracted.backendData, Boolean(error.reranking));
  }
}

function renderHandoffResults(results, ranked = []) {
  const resultsByIndex = new Map(results.map((result) => [result.originalIndex, result]));
  const displayResults = ranked.length > 0 ? ranked : results;
  elements.handoffResults.replaceChildren();
  for (const result of displayResults) {
    const source = resultsByIndex.get(result.originalIndex) ?? result;
    const row = document.createElement('tr');
    appendTextCell(row, result.originalIndex);
    appendTextCell(row, result.name ?? source.name ?? '-');
    appendTextCell(row, result.sellerName ?? source.sellerName ?? '-');
    appendLinkCell(row, result.imageUrl ?? source.imageUrl, 'image');
    appendTextCell(row, formatPrice(result.price ?? source.price));
    appendLinkCell(row, result.purchaseUrl ?? source.purchaseUrl, 'purchase');
    appendTextCell(row, result.imageSimilarity === undefined
      ? '-'
      : result.imageSimilarity.toFixed(8));
    appendTextCell(row, result.tagSimilarity === undefined
      ? '-'
      : result.tagSimilarity.toFixed(8));
    appendTextCell(row, result.finalScore === undefined ? '-' : result.finalScore.toFixed(8));
    appendTextCell(row, source.status === 'ready' ? 'ready' : safeHandoffFailure(source.status));
    elements.handoffResults.append(row);
  }
}

function appendTextCell(row, value) {
  const cell = document.createElement('td');
  cell.textContent = String(value);
  row.append(cell);
}

function appendLinkCell(row, url, label) {
  const cell = document.createElement('td');
  if (url) {
    const link = document.createElement('a');
    link.href = url;
    link.target = '_blank';
    link.rel = 'noreferrer noopener';
    link.textContent = label;
    cell.append(link);
  } else {
    cell.textContent = '-';
  }
  row.append(cell);
}

function safeHandoffFailure(status) {
  if (status.startsWith('CORS/network')) return 'CORS/network failure';
  if (status.startsWith('HTTP ')) return status.split(':', 1)[0];
  if (status.startsWith('unsupported content type')) return 'unsupported content type';
  if (status.startsWith('body read failure')) return 'body read failure';
  if (status.startsWith('decode failure')) return 'decode failure';
  if (status.startsWith('preprocess failure')) return 'preprocess failure';
  return 'candidate image failure';
}

function range(values) {
  return `${Math.min(...values).toFixed(8)}..${Math.max(...values).toFixed(8)}`;
}

function renderUrlResults(results) {
  elements.urlResults.replaceChildren();
  for (const result of results) {
    const cells = [
      result.index,
      result.host,
      result.contentType,
      formatMilliseconds(result.fetchLatencyMs),
      formatMilliseconds(result.decodeLatencyMs),
      formatMilliseconds(result.preprocessLatencyMs),
      result.cosine === undefined ? '-' : result.cosine.toFixed(8),
      result.status,
    ];
    const row = document.createElement('tr');
    for (const cell of cells) {
      const cellElement = document.createElement('td');
      cellElement.textContent = String(cell);
      row.append(cellElement);
    }
    elements.urlResults.append(row);
  }
}

function summarizeBenchmarkRuns(candidateCount, runs) {
  return {
    candidateCount,
    runtime: runtimeState,
    fallback: fallbackUsed,
    totalInferenceMs: median(runs.map((run) => run.totalInferenceMs)),
    perImageMs: median(runs.map((run) => run.perImageMs)),
    queryLatencyMs: median(runs.map((run) => run.queryLatencyMs)),
    candidateLatencyMs: median(runs.map((run) => run.candidateLatencyMs)),
    cosineLatencyMs: median(runs.map((run) => run.cosineLatencyMs)),
    normalizedCosineMean: median(runs.map((run) => run.normalizedCosineMean)),
    maxCosineDifference: Math.max(...runs.map((run) => run.maxCosineDifference)),
  };
}

function appendBenchmarkRow(row) {
  const cells = [
    row.candidateCount,
    row.runtime,
    row.fallback ? 'yes' : 'no',
    formatMilliseconds(row.totalInferenceMs),
    formatMilliseconds(row.perImageMs),
    formatMilliseconds(row.queryLatencyMs),
    formatMilliseconds(row.candidateLatencyMs),
    formatMilliseconds(row.cosineLatencyMs),
    row.normalizedCosineMean.toFixed(8),
    row.maxCosineDifference.toExponential(2),
    'ok',
  ];
  const rowElement = document.createElement('tr');
  for (const cell of cells) {
    const cellElement = document.createElement('td');
    cellElement.textContent = String(cell);
    rowElement.append(cellElement);
  }
  elements.benchmarkResults.append(rowElement);
}

function showCosines(cosines, index) {
  const rawCosine = cosines.raw[index];
  const normalizedCosine = cosines.normalized[index];
  elements.rawCosine.textContent = rawCosine.toFixed(8);
  elements.normalizedCosine.textContent = normalizedCosine.toFixed(8);
  elements.cosineDifference.textContent = Math.abs(rawCosine - normalizedCosine).toExponential(2);
}

function completeStage(result, stageName, startedAt) {
  const completedAt = performance.now();
  result[`${stageName}CompletedAt`] = completedAt;
  result[`${stageName}LatencyMs`] = completedAt - startedAt;
  return result;
}

function stageWallClock(items, startedAtName, completedAtName) {
  const intervals = items
    .map((item) => ({
      start: item[startedAtName],
      end: item[completedAtName],
    }))
    .filter(({ start, end }) => Number.isFinite(start) && Number.isFinite(end))
    .sort((left, right) => left.start - right.start);
  if (intervals.length === 0) {
    return 0;
  }

  let total = 0;
  let activeStart = intervals[0].start;
  let activeEnd = intervals[0].end;
  for (const interval of intervals.slice(1)) {
    if (interval.start > activeEnd) {
      total += activeEnd - activeStart;
      activeStart = interval.start;
      activeEnd = interval.end;
    } else {
      activeEnd = Math.max(activeEnd, interval.end);
    }
  }
  return total + activeEnd - activeStart;
}

function sumMetric(items, metricName) {
  return items.reduce((sum, item) => sum + item[metricName], 0);
}

function mean(values) {
  return values.reduce((sum, value) => sum + value, 0) / values.length;
}

function median(values) {
  const sorted = [...values].sort((left, right) => left - right);
  const middle = Math.floor(sorted.length / 2);
  return sorted.length % 2 === 0 ? (sorted[middle - 1] + sorted[middle]) / 2 : sorted[middle];
}

function formatMilliseconds(milliseconds) {
  return milliseconds.toFixed(1);
}

elements.modelSecondLoad.addEventListener('click', async () => {
  elements.modelSecondLoad.disabled = true;
  setIntegrationButtonsDisabled(true);
  setStatus('Measuring second model load with the browser HTTP cache available…');
  sessionPromise = null;
  try {
    await loadSession();
    setStatus('Second model load measured.');
  } catch (error) {
    setStatus(`Blocked: ${errorMessage(error)}`);
    elements.modelSecondLoad.disabled = false;
  } finally {
    setIntegrationButtonsDisabled(false);
  }
});

elements.run.addEventListener('click', async () => {
  const queryFile = elements.queryFile.files[0];
  const candidateFile = elements.candidateFile.files[0];
  if (!queryFile || !candidateFile) {
    setStatus('Select one query image and at least one candidate image first.');
    return;
  }

  elements.run.disabled = true;
  elements.benchmarkRun.disabled = true;
  setStatus('Loading the browser model and running local inference…');
  try {
    await runComparison(queryFile, candidateFile);
    setStatus('Done. Raw and explicitly normalized embeddings were compared in this browser tab.');
  } catch (error) {
    setStatus(`Blocked: ${errorMessage(error)}`);
  } finally {
    elements.run.disabled = false;
    elements.benchmarkRun.disabled = false;
  }
});

elements.benchmarkRun.addEventListener('click', async () => {
  const queryFile = elements.queryFile.files[0];
  const candidateFiles = Array.from(elements.candidateFile.files);
  if (!queryFile || candidateFiles.length < Math.max(...BENCHMARK_SIZES)) {
    setStatus(`Select one query image and at least ${Math.max(...BENCHMARK_SIZES)} candidate images first.`);
    return;
  }

  elements.run.disabled = true;
  elements.benchmarkRun.disabled = true;
  setStatus('Loading the browser model and preparing the benchmark…');
  try {
    await runBenchmark(queryFile, candidateFiles);
    setStatus('Done. Warm medians were computed for candidate batch sizes 1, 3, 5, and 10.');
  } catch (error) {
    setStatus(`Blocked: ${errorMessage(error)}`);
  } finally {
    elements.run.disabled = false;
    elements.benchmarkRun.disabled = false;
  }
});

elements.urlRun.addEventListener('click', async () => {
  const queryFile = elements.queryFile.files[0];
  const urls = parseCandidateUrls();
  if (!queryFile || urls.length === 0) {
    setStatus('Select one query image and at least one candidate URL first.');
    return;
  }

  elements.run.disabled = true;
  elements.benchmarkRun.disabled = true;
  elements.urlRun.disabled = true;
  setStatus('Loading the browser model and testing direct candidate image URLs…');
  try {
    await runUrlIntegration(queryFile, urls);
    setStatus('Done. Candidate URLs were fetched directly by this browser tab.');
  } catch (error) {
    setStatus(`Blocked: ${errorMessage(error)}`);
  } finally {
    elements.run.disabled = false;
    elements.benchmarkRun.disabled = false;
    elements.urlRun.disabled = false;
  }
});

elements.backendRun.addEventListener('click', async () => {
  const queryFile = elements.queryFile.files[0];
  if (!queryFile) {
    setStatus('Select one local query crop image first.');
    return;
  }

  setIntegrationButtonsDisabled(true);
  elements.backendSummary.textContent = 'Requesting recommendation from backend…';
  elements.handoffSummary.textContent = 'Waiting for backend browserReranking handoff.';
  setStatus('Posting recommendation request and waiting for browserReranking…');
  try {
    await runBackendIntegration(queryFile);
  } catch (error) {
    showBrowserUnavailable(`backend request failed: ${errorMessage(error)}`);
  } finally {
    setIntegrationButtonsDisabled(false);
  }
});

elements.backendBenchmarkRun.addEventListener('click', async () => {
  const queryFile = elements.queryFile.files[0];
  if (!queryFile) {
    setStatus('Select one local query crop image first.');
    return;
  }

  setIntegrationButtonsDisabled(true);
  elements.backendSummary.textContent = 'Requesting one recommendation handoff for the full relevance vs price-ordered top-10 benchmark…';
  elements.handoffSummary.textContent = 'Waiting for browser benchmark handoff.';
  elements.handoffBenchmarkSummary.textContent = 'No full relevance vs price-ordered top-10 benchmark measured.';
  elements.handoffQualitySummary.textContent = 'No final-ranking comparison measured.';
  elements.handoffComparisonOrdering.textContent = 'No final ordering measured.';
  setStatus('Posting one recommendation request and preparing warm full relevance vs price-ordered top-10 browser benchmark…');
  try {
    await runBackendBenchmarkIntegration(queryFile);
  } catch (error) {
    showBrowserUnavailable(`backend benchmark request failed: ${errorMessage(error)}`);
  } finally {
    setIntegrationButtonsDisabled(false);
  }
});
