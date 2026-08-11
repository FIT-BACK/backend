import * as ort from 'onnxruntime-web/webgpu';
import { cosineSimilarity, l2Norm } from './math.js';

const MODEL_ID = 'Frapic/fashion-clip-onnx';
const MODEL_REVISION = '12eb79267363fd03b8983a25903cd9097b1ec76c';
const MODEL_URL = `https://huggingface.co/${MODEL_ID}/resolve/${MODEL_REVISION}/vision_model.onnx`;
const WASM_PATH = 'https://cdn.jsdelivr.net/npm/onnxruntime-web@1.27.0/dist/';
const IMAGE_SIZE = 224;
const CHANNEL_MEAN = [0.48145466, 0.4578275, 0.40821073];
const CHANNEL_STD = [0.26862954, 0.26130258, 0.27577711];

ort.env.wasm.wasmPaths = WASM_PATH;

const elements = {
  queryFile: document.querySelector('#query-file'),
  candidateFile: document.querySelector('#candidate-file'),
  run: document.querySelector('#run'),
  status: document.querySelector('#status'),
  runtime: document.querySelector('#runtime'),
  model: document.querySelector('#model'),
  modelLoad: document.querySelector('#model-load'),
  inference: document.querySelector('#inference'),
  cosine: document.querySelector('#cosine'),
  query: {
    dimension: document.querySelector('#query-dimension'),
    finite: document.querySelector('#query-finite'),
    norm: document.querySelector('#query-norm'),
  },
  candidate: {
    dimension: document.querySelector('#candidate-dimension'),
    finite: document.querySelector('#candidate-finite'),
    norm: document.querySelector('#candidate-norm'),
  },
};

let sessionPromise;

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
    elements.runtime.textContent = requestedProviders.join(' → ');
    elements.model.textContent = MODEL_ID;
    elements.modelLoad.textContent = `${formatMilliseconds(performance.now() - started)} ms`;
    return session;
  } catch (error) {
    if (!canTryWebGpu) {
      throw error;
    }
    setStatus('WebGPU session failed; retrying with WASM.');
    const session = await ort.InferenceSession.create(MODEL_URL, {
      executionProviders: ['wasm'],
      graphOptimizationLevel: 'all',
    });
    elements.runtime.textContent = 'wasm (WebGPU unavailable)';
    elements.model.textContent = MODEL_ID;
    elements.modelLoad.textContent = `${formatMilliseconds(performance.now() - started)} ms`;
    return session;
  }
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

function readEmbedding(output, index) {
  const dimension = output.dims.at(-1);
  if (!dimension || output.dims.length !== 2 || output.dims[0] !== 2) {
    throw new Error(`unexpected embedding shape: ${output.dims.join(' × ')}`);
  }
  return output.data.slice(index * dimension, (index + 1) * dimension);
}

function showDiagnostics(target, embedding) {
  target.dimension.textContent = String(embedding.length);
  target.finite.textContent = String(embedding.every(Number.isFinite));
  target.norm.textContent = l2Norm(embedding).toFixed(6);
}

function formatMilliseconds(milliseconds) {
  return milliseconds.toFixed(1);
}

async function compareImages(queryFile, candidateFile) {
  const session = await loadSession();
  const [queryData, candidateData] = await Promise.all([
    imageToTensorData(queryFile),
    imageToTensorData(candidateFile),
  ]);
  const batchData = new Float32Array(queryData.length + candidateData.length);
  batchData.set(queryData);
  batchData.set(candidateData, queryData.length);

  const inputName = session.inputNames[0];
  const outputName = session.outputNames[0];
  const input = new ort.Tensor('float32', batchData, [2, 3, IMAGE_SIZE, IMAGE_SIZE]);
  const started = performance.now();
  const outputs = await session.run({ [inputName]: input });
  elements.inference.textContent = `${formatMilliseconds(performance.now() - started)} ms`;

  const output = outputs[outputName];
  if (!output) {
    throw new Error(`missing model output: ${outputName}`);
  }
  const queryEmbedding = readEmbedding(output, 0);
  const candidateEmbedding = readEmbedding(output, 1);
  showDiagnostics(elements.query, queryEmbedding);
  showDiagnostics(elements.candidate, candidateEmbedding);
  elements.cosine.textContent = cosineSimilarity(queryEmbedding, candidateEmbedding).toFixed(8);
}

elements.run.addEventListener('click', async () => {
  const queryFile = elements.queryFile.files[0];
  const candidateFile = elements.candidateFile.files[0];
  if (!queryFile || !candidateFile) {
    setStatus('Select both local images first.');
    return;
  }

  elements.run.disabled = true;
  setStatus('Loading the browser model and running local inference…');
  try {
    await compareImages(queryFile, candidateFile);
    setStatus('Done. Both embeddings and cosine were computed in this browser tab.');
  } catch (error) {
    setStatus(`Blocked: ${error instanceof Error ? error.message : String(error)}`);
  } finally {
    elements.run.disabled = false;
  }
});
