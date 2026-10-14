// File logging for the server.
//
// Why this exists: the server only ever wrote to stdout/stderr, which the
// Android host captures into a fixed-size in-memory buffer that dies with the
// process. When something went wrong on a phone there was nothing left to look
// at afterwards. Writing to a file under the data directory makes failures
// diagnosable after the fact and gives the in-app "export logs" action something
// to export.
//
// Logging must never take the server down: every filesystem operation here is
// best-effort, and a failure degrades to console-only output.

import fs from 'node:fs';
import path from 'node:path';

const MAX_BYTES = 2 * 1024 * 1024;   // per file before rotating
const MAX_FILES = 5;                 // including the active one

let directory = '';
let stream = null;
let written = 0;
let broken = false;

/** Resolves the log directory, preferring an explicit override. */
function resolveDirectory() {
  if (process.env.DFLOW_LOG_DIR) return process.env.DFLOW_LOG_DIR;
  const dataDir = process.env.DFLOW_DATA_DIR
    ? path.resolve(process.env.DFLOW_DATA_DIR)
    : path.join(process.cwd(), 'data');
  return path.join(dataDir, 'logs');
}

function timestamp() {
  // Local time with an explicit offset is what a person reading a log expects;
  // a bare ISO-8601 UTC string makes correlating with what they just did harder.
  const now = new Date();
  const pad = (value, size = 2) => String(value).padStart(size, '0');
  const offsetMinutes = -now.getTimezoneOffset();
  const sign = offsetMinutes >= 0 ? '+' : '-';
  const offset = `${sign}${pad(Math.floor(Math.abs(offsetMinutes) / 60))}${pad(Math.abs(offsetMinutes) % 60)}`;
  return `${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())} `
    + `${pad(now.getHours())}:${pad(now.getMinutes())}:${pad(now.getSeconds())}.${pad(now.getMilliseconds(), 3)}`
    + `${offset}`;
}

function rotateIfNeeded() {
  if (written < MAX_BYTES) return;
  try {
    stream?.end();
    stream = null;
    written = 0;
    // Shift server.log -> server.log.1 -> ... and drop the oldest.
    for (let index = MAX_FILES - 1; index >= 1; index -= 1) {
      const from = path.join(directory, index === 1 ? 'server.log' : `server.log.${index - 1}`);
      const to = path.join(directory, `server.log.${index}`);
      if (fs.existsSync(from)) fs.renameSync(from, to);
    }
  } catch {
    // Rotation is housekeeping; losing it must not stop logging.
  }
}

function openStream() {
  if (broken) return null;
  try {
    if (!directory) directory = resolveDirectory();
    fs.mkdirSync(directory, { recursive: true });
    if (!stream) {
      stream = fs.createWriteStream(path.join(directory, 'server.log'), { flags: 'a' });
      // An async write error (disk full) arrives here, not as a throw.
      stream.on('error', () => { broken = true; stream = null; });
      written = fs.existsSync(path.join(directory, 'server.log'))
        ? fs.statSync(path.join(directory, 'server.log')).size
        : 0;
    }
    return stream;
  } catch {
    broken = true;
    return null;
  }
}

/**
 * Appends one line to the log file.
 *
 * Synchronous on purpose: an async write can be lost if the process exits right
 * after the call, which is exactly the situation (a crash) that matters most.
 */
export function logLine(level, ...parts) {
  rotateIfNeeded();
  const target = openStream();
  if (!target) return;
  const message = parts
    .map(part => {
      if (part instanceof Error) return part.stack || `${part.name}: ${part.message}`;
      if (typeof part === 'string') return part;
      try { return JSON.stringify(part); } catch { return String(part); }
    })
    .join(' ');
  const line = `[${timestamp()}] [${level}] ${message}\n`;
  try {
    target.write(line);
    written += Buffer.byteLength(line);
  } catch {
    broken = true;
    stream = null;
  }
}

/**
 * Installs file logging.
 *
 * console.* is wrapped rather than replaced at every call site so that the
 * existing (and future) console calls all land in the file with correct levels,
 * while stdout keeps working exactly as before.
 */
export function installFileLogging() {
  const directory = resolveDirectory();
  const original = {
    log: console.log.bind(console),
    warn: console.warn.bind(console),
    error: console.error.bind(console),
  };

  console.log = (...parts) => { original.log(...parts); logLine('INFO', ...parts); };
  console.warn = (...parts) => { original.warn(...parts); logLine('WARN', ...parts); };
  console.error = (...parts) => { original.error(...parts); logLine('ERROR', ...parts); };

  // Uncaught failures are the most valuable thing to have in a file, because the
  // process is about to die and take its stdout buffer with it.
  process.on('uncaughtException', error => {
    logLine('FATAL', 'uncaughtException', error);
    original.error(error);
  });
  process.on('unhandledRejection', reason => {
    logLine('FATAL', 'unhandledRejection', reason instanceof Error ? reason : String(reason));
    original.error(reason);
  });

  logLine('INFO', `日志已启用：${directory}`);
  return directory;
}

export function logDirectory() {
  return directory || resolveDirectory();
}

/** Files currently present, newest first, for the export action. */
export function logFiles() {
  const dir = logDirectory();
  try {
    return fs.readdirSync(dir)
      .filter(name => name.startsWith('server.log'))
      .map(name => path.join(dir, name))
      .filter(file => { try { return fs.statSync(file).isFile(); } catch { return false; } })
      .sort()
      .reverse();
  } catch {
    return [];
  }
}
