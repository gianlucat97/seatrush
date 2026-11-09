#!/usr/bin/env node
// Frames of the spectator view of README section 6.7, while something else fills the venue.
//
// A headless browser driven over the DevTools protocol, and nothing else: Node has had a WebSocket
// client built in since 22, so this needs no dependency of its own and nothing is added to the
// client's package.json for the sake of a recording. Chrome's own --screenshot flag was the
// obvious alternative and it cannot do this: it starts a browser, renders once and exits, so every
// frame would be a cold load of the page, a new sessionStorage and a seat map stream opened from
// scratch. What this records instead is one page, signed in once, left open and photographed.
//
//   node scripts/record-demo.mjs --url http://localhost:5173 --event <uuid> --out target/demo
//   node scripts/record-demo.mjs --url ... --event ... --out ... --frames 24 --interval 2000
//
// The sign in is a fetch the page makes about itself. Section 6.7 says a spectator signs in like
// anybody else, because the gateway validates a token on every read and there is no anonymous view
// of a seat map; the session goes into sessionStorage under the key the client reads it from, and
// the page is then reloaded so the client starts with it rather than being handed one mid-render.
//
// Exit codes: 0 when every frame was written, 2 when the browser, the gateway or the page did not
// do its part. There is no 1, because this tool judges nothing.

import { spawn } from 'node:child_process';
import { mkdir, readFile, rm, writeFile } from 'node:fs/promises';
import { existsSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

// Where a Windows, a macOS and a Linux install keep it. The first one that is there wins, and
// CHROME overrides all of them.
const CHROME_CANDIDATES = [
  process.env.CHROME,
  'C:/Program Files/Google/Chrome/Application/chrome.exe',
  'C:/Program Files (x86)/Google/Chrome/Application/chrome.exe',
  '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',
  '/usr/bin/google-chrome',
  '/usr/bin/chromium',
  '/usr/bin/chromium-browser',
].filter(Boolean);

const DEFAULTS = {
  url: 'http://localhost:5173',
  out: 'target/demo/frames',
  frames: 24,
  interval: 2000,
  width: 1280,
  height: 800,
  username: 'spectator',
  settle: 20000,
};

function usage() {
  return `frames of the spectator seat map (README section 6.7)

  --url <base>       where the client is served, default ${DEFAULTS.url}
  --event <uuid>     the sale to watch, required
  --out <dir>        where the frames go, default ${DEFAULTS.out}
  --frames <n>       how many, default ${DEFAULTS.frames}
  --interval <ms>    between them, default ${DEFAULTS.interval}
  --width <px>       viewport, default ${DEFAULTS.width}
  --height <px>      viewport, default ${DEFAULTS.height}
  --username <name>  who to sign in as, default ${DEFAULTS.username}
  --settle <ms>      how long the seat map is given to arrive, default ${DEFAULTS.settle}
`;
}

function parseArguments(argv) {
  const options = { ...DEFAULTS, event: null };
  for (let i = 0; i < argv.length; i += 1) {
    const flag = argv[i];
    if (flag === '--help' || flag === '-h') {
      return { help: true };
    }
    if (!flag.startsWith('--')) {
      throw new Error(`not a flag: ${flag}`);
    }
    const name = flag.slice(2);
    if (!(name in options)) {
      throw new Error(`unknown flag: ${flag}`);
    }
    const value = argv[i + 1];
    if (value === undefined) {
      throw new Error(`${flag} needs a value`);
    }
    i += 1;
    options[name] = typeof DEFAULTS[name] === 'number' ? Number(value) : value;
    if (typeof DEFAULTS[name] === 'number' && !Number.isFinite(options[name])) {
      throw new Error(`${flag} is not a number: ${value}`);
    }
  }
  if (options.event === null) {
    throw new Error('--event is required: there is no seat map without a sale');
  }
  // So the fragment appended below is one slash away from the origin and not two.
  options.url = options.url.replace(/\/+$/, '');
  return options;
}

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

/** Chrome, headless, with its debugging port written to a file rather than guessed. */
async function startChrome(options, profile) {
  const binary = CHROME_CANDIDATES.find((path) => existsSync(path));
  if (binary === undefined) {
    throw new Error('no Chrome found. Name one with CHROME=/path/to/chrome');
  }
  const chrome = spawn(
    binary,
    [
      '--headless=new',
      // Port 0 means the operating system picks one and Chrome writes it to DevToolsActivePort in
      // the profile. A fixed port is a race with whatever else on the machine wanted it.
      '--remote-debugging-port=0',
      `--user-data-dir=${profile}`,
      `--window-size=${options.width},${options.height}`,
      '--hide-scrollbars',
      '--no-first-run',
      '--no-default-browser-check',
      '--disable-gpu',
      options.url,
    ],
    { stdio: 'ignore' },
  );

  const portFile = join(profile, 'DevToolsActivePort');
  for (let attempt = 0; attempt < 100; attempt += 1) {
    await sleep(100);
    try {
      const [port] = (await readFile(portFile, 'utf8')).split('\n');
      if (port) {
        return { chrome, port: Number(port) };
      }
    } catch {
      // Not written yet, which is the normal state for the first second.
    }
  }
  chrome.kill();
  throw new Error('Chrome started and never published a debugging port');
}

/** The page target Chrome opened for the url on the command line. */
async function pageTarget(port) {
  for (let attempt = 0; attempt < 50; attempt += 1) {
    const response = await fetch(`http://127.0.0.1:${port}/json/list`);
    const targets = await response.json();
    const page = targets.find((target) => target.type === 'page' && target.webSocketDebuggerUrl);
    if (page) {
      return page.webSocketDebuggerUrl;
    }
    await sleep(100);
  }
  throw new Error('Chrome opened no page to attach to');
}

/**
 * One DevTools connection, as a function that sends a command and waits for its answer.
 *
 * Every message carries an id and the answers come back out of order, which is the whole reason
 * this keeps a map of pending commands instead of awaiting the socket.
 */
async function connect(url) {
  const socket = new WebSocket(url);
  const pending = new Map();
  let nextId = 1;

  await new Promise((resolve, reject) => {
    socket.addEventListener('open', resolve, { once: true });
    socket.addEventListener('error', () => reject(new Error(`cannot attach to ${url}`)), {
      once: true,
    });
  });

  socket.addEventListener('message', (event) => {
    const message = JSON.parse(event.data);
    const waiting = pending.get(message.id);
    if (waiting === undefined) {
      return; // An event rather than an answer. Nothing here subscribes to any.
    }
    pending.delete(message.id);
    if (message.error) {
      waiting.reject(new Error(`${waiting.method}: ${message.error.message}`));
    } else {
      waiting.resolve(message.result);
    }
  });

  const send = (method, params = {}) =>
    new Promise((resolve, reject) => {
      const id = nextId;
      nextId += 1;
      pending.set(id, { resolve, reject, method });
      socket.send(JSON.stringify({ id, method, params }));
    });

  return { send, close: () => socket.close() };
}

/**
 * Waits until the page has finished loading the origin it was sent to.
 *
 * Polled rather than subscribed to, which is the smaller of two kinds of plumbing: the connection
 * above answers commands and ignores events, and one readyState every fifth of a second is cheaper
 * than a second code path for Page.loadEventFired. It is also the check that matters here, because
 * the first thing this does to a page is fetch a relative URL, and a document that has not
 * committed yet has no base to resolve one against.
 */
async function waitForPage(cdp, origin, timeout = 30000) {
  const deadline = Date.now() + timeout;
  let last = 'nothing';
  while (Date.now() < deadline) {
    last = await evaluate(cdp, `document.readyState + ' at ' + location.origin`);
    if (last === `complete at ${origin}`) {
      return;
    }
    await sleep(200);
  }
  throw new Error(`the page never finished loading ${origin}, and is ${last}`);
}

/** Runs an expression in the page and gives back its value, with the page's errors as errors. */
async function evaluate(cdp, expression) {
  const result = await cdp.send('Runtime.evaluate', {
    expression,
    awaitPromise: true,
    returnByValue: true,
  });
  if (result.exceptionDetails) {
    const thrown = result.exceptionDetails.exception;
    throw new Error(thrown?.description ?? thrown?.value ?? 'the page threw');
  }
  return result.result.value;
}

const SIGN_IN = (username) => `
  (async () => {
    const answer = await fetch('/api/auth/demo-login', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ username: ${JSON.stringify(username)}, admin: false }),
    });
    if (!answer.ok) {
      throw new Error('the demo login answered ' + answer.status);
    }
    const login = await answer.json();
    sessionStorage.setItem('seatrush.session', JSON.stringify({
      accessToken: login.accessToken,
      userId: login.userId,
      username: login.username,
      expiresAt: Date.parse(login.expiresAt),
    }));
    return login.username;
  })()
`;

/**
 * What the page is actually showing, which is worth asking before photographing it 24 times.
 *
 * A client that did not pick up its session renders the sign in form, and a sign in form is a
 * perfectly good screenshot of the wrong thing. The seats are counted off the SVG of section 6.7
 * rather than inferred from the route, because the route is what was asked for and the rectangles
 * are what arrived.
 */
const SHOWING = `
  JSON.stringify({
    href: location.href,
    session: JSON.parse(sessionStorage.getItem('seatrush.session') || 'null')?.username ?? null,
    seats: document.querySelectorAll('rect[data-seat-id]').length,
    text: document.body.innerText.replace(/\s+/g, ' ').slice(0, 160),
  })
`;

/**
 * Waits for the seat map to arrive, and says what the page had instead when it does not.
 *
 * Waited for rather than slept through, because what is being timed is a fetch, a parse and a
 * render of up to twenty thousand rectangles on a machine that is busy being load tested. A pause
 * long enough to be safe there is a pause nobody wants in front of every recording.
 */
async function waitForSeatMap(cdp, timeout) {
  const deadline = Date.now() + timeout;
  let showing = { seats: 0, session: null, href: '', text: '' };
  while (Date.now() < deadline) {
    showing = JSON.parse(await evaluate(cdp, SHOWING));
    if (showing.session !== null && showing.seats > 0) {
      return showing;
    }
    await sleep(250);
  }
  throw new Error(
    `the page is not showing a seat map: ${showing.seats} seats, signed in as ` +
      `${showing.session ?? 'nobody'}, at ${showing.href}, and it says: ${showing.text}`,
  );
}

async function main(argv) {
  let options;
  try {
    options = parseArguments(argv);
  } catch (error) {
    process.stderr.write(`${error.message}\n\n${usage()}`);
    return 2;
  }
  if (options.help) {
    process.stdout.write(usage());
    return 0;
  }

  const profile = join(tmpdir(), `seatrush-demo-${process.pid}`);
  await mkdir(options.out, { recursive: true });
  let chrome;
  let cdp;
  try {
    const started = await startChrome(options, profile);
    chrome = started.chrome;
    cdp = await connect(await pageTarget(started.port));
    await cdp.send('Page.enable');

    const origin = new URL(options.url).origin;
    await cdp.send('Page.navigate', { url: options.url });
    await waitForPage(cdp, origin);

    const signedIn = await evaluate(cdp, SIGN_IN(options.username));
    process.stdout.write(`signed in as ${signedIn}\n`);

    // Navigated and then reloaded, and the reload is the whole trick. The client reads its session
    // once when it starts (README section 6.7), and the spectator URL differs from the one above
    // only by its fragment, which is a same-document navigation: the page that was already running
    // stays running, never remounts, and goes on rendering the sign in form it was showing when
    // the session did not exist yet. A perfectly good screenshot of the wrong thing.
    const spectator = `${options.url}/#/events/${options.event}/spectator`;
    await cdp.send('Page.navigate', { url: spectator });
    await cdp.send('Page.reload');
    await waitForPage(cdp, origin);

    const showing = await waitForSeatMap(cdp, options.settle);
    process.stdout.write(`watching ${showing.seats} seats of ${options.event}\n`);

    for (let frame = 0; frame < options.frames; frame += 1) {
      const shot = await cdp.send('Page.captureScreenshot', { format: 'png' });
      const name = join(options.out, `frame-${String(frame).padStart(3, '0')}.png`);
      await writeFile(name, Buffer.from(shot.data, 'base64'));
      process.stdout.write(`\rframe ${frame + 1} of ${options.frames}`);
      if (frame + 1 < options.frames) {
        await sleep(options.interval);
      }
    }
    process.stdout.write(`\n${options.frames} frames in ${options.out}\n`);
    return 0;
  } catch (error) {
    process.stderr.write(`the recording failed: ${error.message}\n`);
    return 2;
  } finally {
    cdp?.close();
    chrome?.kill();
    await rm(profile, { recursive: true, force: true }).catch(() => {});
  }
}

/** The port Chrome wrote for itself, read again now that the process is up. */
async function startedOn(profile) {
  const [port] = (await readFile(join(profile, 'DevToolsActivePort'), 'utf8')).split('\n');
  return { port: Number(port) };
}

process.exitCode = await main(process.argv.slice(2));
