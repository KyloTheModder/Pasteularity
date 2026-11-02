import { existsSync } from 'node:fs';

const candidates = [
  process.env.CHROME_PATH,
  `${process.env.ProgramFiles}\\Google\\Chrome\\Application\\chrome.exe`,
  `${process.env['ProgramFiles(x86)']}\\Google\\Chrome\\Application\\chrome.exe`,
  `${process.env.LOCALAPPDATA}\\Google\\Chrome\\Application\\chrome.exe`,
  `${process.env['ProgramFiles(x86)']}\\Microsoft\\Edge\\Application\\msedge.exe`,
  '/usr/bin/google-chrome',
  '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',
];

/** Finds an installed Chrome or Edge. Set CHROME_PATH to use a different one. */
export function findBrowser() {
  const found = candidates.find((path) => path && existsSync(path));
  if (!found) throw new Error('Chrome or Edge not found. Set CHROME_PATH.');
  return found;
}
