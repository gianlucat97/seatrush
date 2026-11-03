/**
 * Server-sent events, read from a `fetch` response instead of from an `EventSource`.
 *
 * `EventSource` cannot set a header, and every stream in this system is behind the bearer token the
 * gateway validates (README section 13). The alternatives would be to put the token in the query
 * string, which puts a credential in every access log between here and the pod, or to parse the
 * protocol here. The protocol is a dozen lines, so it is parsed here.
 *
 * What is implemented is the part of the specification these streams use: `event`, `data`, `retry`,
 * comments, and a blank line to dispatch. `id` and the last-event-id handshake are deliberately
 * absent, because neither stream of this system replays anything on reconnect: the seat map client
 * reloads a snapshot and the waiting room client asks where it stands.
 */

/** One dispatched event, with the fields this client reads. */
export interface ServerSentEvent {
  /** The event name, or `message` when the stream did not give one, as the specification says. */
  event: string;
  /** The data lines, joined by newlines with no trailing one. */
  data: string;
  /** The reconnect delay the server last asked for, in milliseconds, if it asked. */
  retry?: number;
}

/**
 * Turns the text of a stream into events, one chunk at a time.
 *
 * A chunk is whatever arrived, which is not a line and not an event: a delta can be split across
 * two reads, and two deltas can arrive in one. The parser therefore keeps whatever is after the
 * last newline and uses it as the start of the next chunk, which is the one thing a hand written
 * reader of this protocol usually gets wrong.
 */
export class SseParser {
  private pending = '';
  private eventName: string | null = null;
  private dataLines: string[] = [];
  private retry: number | undefined;

  /** Feeds one chunk in and takes out whatever it completed. */
  push(chunk: string): ServerSentEvent[] {
    this.pending += chunk;
    const dispatched: ServerSentEvent[] = [];
    // A stream may use any of the three line endings the specification allows. Normalising first
    // keeps the rest of this class from caring which one a proxy decided to use.
    const lines = this.pending.replace(/\r\n|\r/g, '\n').split('\n');
    // The last element is what came after the last newline: an incomplete line, or the empty
    // string when the chunk ended cleanly. Either way it is not ready to be read.
    this.pending = lines.pop() ?? '';
    for (const line of lines) {
      const event = this.consume(line);
      if (event !== null) {
        dispatched.push(event);
      }
    }
    return dispatched;
  }

  /** Reads one complete line, and returns an event if that line dispatched one. */
  private consume(line: string): ServerSentEvent | null {
    if (line === '') {
      return this.dispatch();
    }
    if (line.startsWith(':')) {
      // A comment, which is what a heartbeat is: it keeps the connection warm and says nothing.
      return null;
    }
    const colon = line.indexOf(':');
    const field = colon === -1 ? line : line.slice(0, colon);
    // One optional space after the colon belongs to the protocol and not to the value.
    const raw = colon === -1 ? '' : line.slice(colon + 1);
    const value = raw.startsWith(' ') ? raw.slice(1) : raw;

    switch (field) {
      case 'event':
        this.eventName = value;
        break;
      case 'data':
        this.dataLines.push(value);
        break;
      case 'retry': {
        const delay = Number(value);
        if (Number.isInteger(delay) && delay >= 0) {
          this.retry = delay;
        }
        break;
      }
      default:
        // `id` and anything a later version of a stream adds. Ignored rather than refused: a
        // client that failed on a field it does not know could not be deployed before its server.
        break;
    }
    return null;
  }

  /**
   * Ends the event being built, if there is one.
   *
   * A blank line after nothing but comments dispatches nothing, which is what keeps a quiet stream
   * of heartbeats from handing the page a stream of empty events.
   */
  private dispatch(): ServerSentEvent | null {
    if (this.dataLines.length === 0 && this.eventName === null) {
      return null;
    }
    const event: ServerSentEvent = {
      event: this.eventName ?? 'message',
      data: this.dataLines.join('\n'),
      ...(this.retry === undefined ? {} : { retry: this.retry }),
    };
    this.eventName = null;
    this.dataLines = [];
    return event;
  }
}

/**
 * Reads a streaming response as events until it ends or the caller aborts it.
 *
 * A generator rather than a callback, so the code that follows a stream reads as a loop and the
 * cleanup is the end of that loop. The reader is released whatever ends it, including an abort,
 * which is what keeps a page that navigated away from holding a connection open.
 */
export async function* readEvents(response: Response): AsyncGenerator<ServerSentEvent> {
  if (response.body === null) {
    throw new Error('the response carries no body to stream');
  }
  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  const parser = new SseParser();
  try {
    for (;;) {
      const { done, value } = await reader.read();
      if (done) {
        return;
      }
      // `stream: true` keeps a multi-byte character that straddles two chunks intact.
      for (const event of parser.push(decoder.decode(value, { stream: true }))) {
        yield event;
      }
    }
  } finally {
    reader.releaseLock();
  }
}
