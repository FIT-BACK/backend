import * as ort from 'onnxruntime-web/webgpu';
import { cosineSimilarity, l2Norm, normalizeL2 } from './math.js';

const MODEL_ID = 'Frapic/fashion-clip-onnx';
const MODEL_REVISION = '12eb79267363fd03b8983a25903cd9097b1ec76c';
const MODEL_URL = `https://huggingface.co/${MODEL_ID}/resolve/${MODEL_REVISION}/vision_model.onnx`;
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
  run: document.querySelector('#run'),
  benchmarkRun: document.querySelector('#benchmark-run'),
  status: document.querySelector('#status'),
  runtime: document.querySelector('#runtime'),
  model: document.querySelector('#model'),
  modelLoad: document.querySelector('#model-load'),
  inference: document.querySelector('#inference'),
  rawCosine: document.querySelector('#raw-cosine'),
  normalizedCosine: document.querySelector('#normalized-cosine'),
  cosineDifference: document.querySelector('#cosine-difference'),
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

let sessionPromise;
let runtimeState = 'unknown';
let fallbackUsed = false;

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
  const canTryWebGpu = 'gpu' in navigator;
  const requestedProviders = canTryWebGpu ? ['webgpu', 'wasm'] : ['wasm'];
  try {
    const session = await ort.InferenceSession.create(MODEL_URL, {
      executionProviders: requestedProviders,
      graphOptimizationLevel: 'all',
    });
    runtimeState = canTryWebGpu ? 'webgpu' : 'wasm';
    elements.runtime.textContent = canTryWebGpu
      ? 'webgpu session; wasm fallback armed'
      : 'wasm';
    elements.model.textContent = MODEL_ID;
    elements.modelLoad.textContent = `${formatMilliseconds(performance.now() - started)} ms`;
    return session;
  } catch (error) {
    if (!canTryWebGpu) {
      throw error;
    }
    setStatus(`WebGPU failed (${errorMessage(error)}); retrying with WASM.`);
    let session;
    try {
      session = await ort.InferenceSession.create(MODEL_URL, {
        executionProviders: ['wasm'],
        graphOptimizationLevel: 'all',
      });
    } catch (fallbackError) {
      throw new Error(`WebGPU: ${errorMessage(error)}; WASM: ${errorMessage(fallbackError)}`);
    }
    runtimeState = 'wasm';
    fallbackUsed = true;
    elements.runtime.textContent = 'wasm (WebGPU unavailable)';
    elements.model.textContent = MODEL_ID;
    elements.modelLoad.textContent = `${formatMilliseconds(performance.now() - started)} ms`;
    return session;
  }
}

function errorMessage(error) {
  return error instanceof Error ? error.message : String(error);
}

async function imageToTensorData(file) {
  const image = await createImage(file);
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

function createImage(file) {
  if ('createImageBitmap' in window) {
    return createImageBitmap(file);
  }
  return new Promise((resolve, reject) => {
    const image = new Image();
    const objectUrl = URL.createObjectURL(file);
    image.onload = () => {
      URL.revokeObjectURL(objectUrl);
      resolve(image);
    };
    image.onerror = () => {
      URL.revokeObjectURL(objectUrl);
      reject(new Error(`could not decode ${file.name}`));
    };
    image.src = objectUrl;
  });
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
