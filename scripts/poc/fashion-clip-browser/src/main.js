import * as ort from 'onnxruntime-web/webgpu';
import {
  calculateFinalScore,
  cosineSimilarity,
  l2Norm,
  normalizeL2,
  sortRerankingResults,
  validateBrowserRerankingHandoff,
} from './math.js';

const MODEL_ID = 'Frapic/fashion-clip-onnx';
const MODEL_REVISION = '12eb79267363fd03b8983a25903cd9097b1ec76c';
const DEFAULT_MODEL_URL = `https://huggingface.co/${MODEL_ID}/resolve/${MODEL_REVISION}/vision_model.onnx`;

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
    return { url: url.href, source: `query override (${url.origin})` };
  } catch {
    return { url: DEFAULT_MODEL_URL, source: 'pinned remote default (invalid override ignored)' };
  }
}

const MODEL_CONFIG = resolveModelConfig();
const MODEL_URL = MODEL_CONFIG.url;
const WASM_PATH = 'https://cdn.jsdelivr.net/npm/onnxruntime-web@1.27.0/dist/';
const IMAGE_SIZE = 224;
const BENCHMARK_SIZES = [1, 3, 5, 10];
const BENCHMARK_REPETITIONS = 3;
const NORMALIZED_NORM_TOLERANCE = 1e-5;
const CHANNEL_MEAN = [0.48145466, 0.4578275, 0.40821073];
const CHANNEL_STD = [0.26862954, 0.26130258, 0.27577711];

ort.env.wasm.wasmPaths = WASM_PATH;

const elements = {
  queryFile: document.querySelector('#query-file'),
  candidateFile: document.querySelector('#candidate-file'),
  candidateUrls: document.querySelector('#candidate-urls'),
  handoffJson: document.querySelector('#handoff-json'),
  run: document.querySelector('#run'),
  benchmarkRun: document.querySelector('#benchmark-run'),
  urlRun: document.querySelector('#url-run'),
  handoffRun: document.querySelector('#handoff-run'),
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
  handoffSummary: document.querySelector('#handoff-summary'),
  handoffResults: document.querySelector('#handoff-results tbody'),
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

async function createSession() {
  const started = performance.now();
  const run = {
    run: modelLoadRuns.length + 1,
    urlResolveMs: 0,
    webgpuInitMs: 0,
    headersMs: 0,
    downloadMs: 0,
    bytes: 0,
    contentLength: null,
    sessionCreationMs: 0,
    totalMs: 0,
    redirected: false,
  };
  const urlStarted = performance.now();
  const resolvedModelUrl = new URL(MODEL_URL, document.baseURI).href;
  run.urlResolveMs = performance.now() - urlStarted;
  const canTryWebGpu = 'gpu' in navigator;
  const requestedProviders = canTryWebGpu ? ['webgpu', 'wasm'] : ['wasm'];
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
    runtimeState = canTryWebGpu ? 'webgpu' : 'wasm';
    elements.runtime.textContent = canTryWebGpu
      ? 'webgpu session; wasm fallback armed'
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
  const image = await createImage(source, label);
  return imageToTensorDataFromImage(image);
}

function imageToTensorDataFromImage(image) {
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
  if (typeof image.close === 'function') {
    image.close();
  }

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

async function fetchUrlTensorData(url, index) {
  const result = {
    index: index + 1,
    host: urlHost(url),
    contentType: '-',
    fetchLatencyMs: 0,
    decodeLatencyMs: 0,
    preprocessLatencyMs: 0,
    status: 'blocked',
  };
  const fetchStarted = performance.now();
  let response;
  try {
    response = await fetch(url, { mode: 'cors', credentials: 'omit' });
  } catch (error) {
    result.fetchLatencyMs = performance.now() - fetchStarted;
    result.status = `CORS/network failure: ${errorMessage(error)}`;
    return result;
  }
  result.fetchLatencyMs = performance.now() - fetchStarted;
  result.contentType = response.headers.get('content-type')?.split(';', 1)[0] ?? '-';
  if (!response.ok) {
    result.status = `HTTP ${response.status}`;
    return result;
  }
  if (!['image/jpeg', 'image/png', 'image/webp'].includes(result.contentType)) {
    result.status = `unsupported content type: ${result.contentType}`;
    return result;
  }

  let blob;
  try {
    blob = await response.blob();
  } catch (error) {
    result.status = `body read failure: ${errorMessage(error)}`;
    return result;
  }
  const decodeStarted = performance.now();
  let image;
  try {
    image = await createImage(blob, `candidate ${index + 1}`);
  } catch (error) {
    result.decodeLatencyMs = performance.now() - decodeStarted;
    result.status = `decode failure: ${errorMessage(error)}`;
    return result;
  }
  result.decodeLatencyMs = performance.now() - decodeStarted;
  const preprocessStarted = performance.now();
  try {
    result.tensorData = imageToTensorDataFromImage(image);
  } catch (error) {
    result.preprocessLatencyMs = performance.now() - preprocessStarted;
    result.status = `preprocess failure: ${errorMessage(error)}`;
    return result;
  }
  result.preprocessLatencyMs = performance.now() - preprocessStarted;
  result.status = 'ready';
  return result;
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
  setStatus(`Fetching and decoding ${urls.length} candidate image URL(s)…`);
  const results = [];
  for (const [index, url] of urls.entries()) {
    results.push(await fetchUrlTensorData(url, index));
  }
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
  const fetchTotalMs = results.reduce((sum, result) => sum + result.fetchLatencyMs, 0);
  const decodeTotalMs = results.reduce((sum, result) => sum + result.decodeLatencyMs, 0);
  const preprocessTotalMs = results.reduce((sum, result) => sum + result.preprocessLatencyMs, 0);
  const totalRerankingMs = performance.now() - started;
  elements.urlSummary.textContent = [
    `success ${ready.length}/${results.length}`,
    `CORS/network failures ${results.filter((result) => result.status.startsWith('CORS/network')).length}`,
    `fetch ${formatMilliseconds(fetchTotalMs)} ms`,
    `decode ${formatMilliseconds(decodeTotalMs)} ms`,
    `preprocess ${formatMilliseconds(preprocessTotalMs)} ms`,
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

function parseHandoffJson() {
  let parsed;
  try {
    parsed = JSON.parse(elements.handoffJson.value);
  } catch {
    throw new Error('handoff JSON is invalid');
  }
  return validateBrowserRerankingHandoff(parsed);
}

async function runHandoffIntegration(queryFile, handoff) {
  const session = await loadSession();
  const started = performance.now();
  const queryData = await imageToTensorData(queryFile, queryFile.name);
  setStatus(`Fetching and decoding ${handoff.candidates.length} handoff candidate image URL(s)…`);
  const results = await Promise.all(handoff.candidates.map(async (candidate) => {
    const result = await fetchUrlTensorData(candidate.imageUrl, candidate.originalIndex - 1);
    return {
      ...result,
      candidateId: candidate.candidateId,
      tagSimilarity: candidate.tagSimilarity,
      originalIndex: candidate.originalIndex,
    };
  }));
  renderHandoffResults(results);
  const failed = results.filter((result) => result.status !== 'ready');
  if (failed.length > 0) {
    elements.handoffSummary.textContent = `blocked: ${failed.length}/${results.length} candidate image(s) failed; no partial reranking was calculated`;
    throw new Error(`candidate image fetch/decode failed at input index ${failed.map((result) => result.originalIndex).join(', ')}`);
  }

  setStatus(`Running Fashion-CLIP candidate batch [${results.length},3,224,224]…`);
  const queryRun = await runEmbeddingBatch(session, [queryData]);
  const candidateRun = await runEmbeddingBatch(session, results.map((result) => result.tensorData));
  const queryDiagnostic = diagnoseEmbedding(queryRun.rawEmbeddings[0]);
  const candidateDiagnostics = candidateRun.rawEmbeddings.map(diagnoseEmbedding);
  const cosines = calculateCosines(queryDiagnostic, candidateDiagnostics);
  const ranked = sortRerankingResults(results.map((result, index) => ({
    candidateId: result.candidateId,
    originalIndex: result.originalIndex,
    imageSimilarity: cosines.normalized[index],
    tagSimilarity: result.tagSimilarity,
    finalScore: calculateFinalScore(cosines.normalized[index], result.tagSimilarity),
  })));
  const totalRerankingMs = performance.now() - started;
  const imageSimilarities = ranked.map((result) => result.imageSimilarity);
  const tagSimilarities = ranked.map((result) => result.tagSimilarity);
  const finalScores = ranked.map((result) => result.finalScore);
  elements.handoffSummary.textContent = [
    `category ${handoff.category ?? '-'}`,
    `candidates ${ranked.length}`,
    `imageSimilarity ${range(imageSimilarities)}`,
    `tagSimilarity ${range(tagSimilarities)}`,
    `finalScore ${range(finalScores)}`,
    `query batch ${formatMilliseconds(queryRun.latencyMs)} ms`,
    `candidate batch ${formatMilliseconds(candidateRun.latencyMs)} ms`,
    `total reranking ${formatMilliseconds(totalRerankingMs)} ms`,
    `runtime ${runtimeState}`,
  ].join(' · ');
  renderHandoffResults(results, ranked);
  showDiagnostics(elements.query, queryDiagnostic);
  showDiagnostics(elements.candidate, candidateDiagnostics[0]);
  showCosines(cosines, 0);
  elements.inference.textContent = `${formatMilliseconds(queryRun.latencyMs + candidateRun.latencyMs)} ms`;
  return { ranked, totalRerankingMs, runtime: runtimeState };
}

function renderHandoffResults(results, ranked = []) {
  const rankedByIndex = new Map(ranked.map((result) => [result.originalIndex, result]));
  elements.handoffResults.replaceChildren();
  for (const result of results) {
    const rankedResult = rankedByIndex.get(result.originalIndex);
    const cells = [
      result.candidateId ?? '-',
      result.originalIndex,
      rankedResult?.imageSimilarity === undefined ? '-' : rankedResult.imageSimilarity.toFixed(8),
      result.tagSimilarity === undefined ? '-' : result.tagSimilarity.toFixed(8),
      rankedResult?.finalScore === undefined ? '-' : rankedResult.finalScore.toFixed(8),
      result.status === 'ready' ? 'ready' : safeHandoffFailure(result.status),
    ];
    const row = document.createElement('tr');
    for (const cell of cells) {
      const cellElement = document.createElement('td');
      cellElement.textContent = String(cell);
      row.append(cellElement);
    }
    elements.handoffResults.append(row);
  }
  if (ranked.length > 0) {
    for (const result of ranked) {
      const row = Array.from(elements.handoffResults.children)
        .find((candidateRow) => candidateRow.children[1].textContent === String(result.originalIndex));
      if (row) {
        elements.handoffResults.append(row);
      }
    }
  }
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
  elements.run.disabled = true;
  elements.benchmarkRun.disabled = true;
  elements.urlRun.disabled = true;
  setStatus('Measuring second model load with the browser HTTP cache available…');
  sessionPromise = null;
  try {
    await loadSession();
    setStatus('Second model load measured.');
  } catch (error) {
    setStatus(`Blocked: ${errorMessage(error)}`);
    elements.modelSecondLoad.disabled = false;
  } finally {
    elements.run.disabled = false;
    elements.benchmarkRun.disabled = false;
    elements.urlRun.disabled = false;
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

elements.handoffRun.addEventListener('click', async () => {
  const queryFile = elements.queryFile.files[0];
  if (!queryFile) {
    setStatus('Select one local query crop image first.');
    return;
  }

  let handoff;
  try {
    handoff = parseHandoffJson();
  } catch (error) {
    setStatus(`Blocked: ${errorMessage(error)}`);
    return;
  }

  elements.run.disabled = true;
  elements.benchmarkRun.disabled = true;
  elements.urlRun.disabled = true;
  elements.handoffRun.disabled = true;
  setStatus('Loading the browser model and preparing handoff reranking…');
  try {
    await runHandoffIntegration(queryFile, handoff);
    setStatus('Done. Handoff candidates were reranked in this browser tab.');
  } catch (error) {
    setStatus(`Blocked: ${errorMessage(error)}`);
  } finally {
    elements.run.disabled = false;
    elements.benchmarkRun.disabled = false;
    elements.urlRun.disabled = false;
    elements.handoffRun.disabled = false;
  }
});
