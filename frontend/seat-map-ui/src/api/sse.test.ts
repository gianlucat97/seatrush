import { describe, expect, it } from 'vitest';
import { SseParser, readEvents } from './sse';

/**
 * The protocol as it actually arrives: in chunks that have nothing to do with where the events are.
 *
 * The cases worth having are the ones a hand written reader gets wrong. An event split across two
 * reads, two events in one read, a heartbeat comment that must not become an empty message, and
 * the single space after a colon that belongs to the protocol rather than to the data.
 */

describe('the parser', () => {
  it('reads one complete event', () => {
    const parser = new SseParser();

    expect(parser.push('event:DELTA\ndata:{"toVersion":4}\n\n')).toEqual([
      { event: 'DELTA', data: '{"toVersion":4}' },
    ]);
  });

  it('waits for the blank line that ends an event', () => {
    const parser = new SseParser();

    expect(parser.push('event:DELTA\ndata:{"toVersion":4}\n')).toEqual([]);
    expect(parser.push('\n')).toEqual([{ event: 'DELTA', data: '{"toVersion":4}' }]);
  });

  it('joins an event that arrived in pieces, even mid-line', () => {
    const parser = new SseParser();

    expect(parser.push('event:DEL')).toEqual([]);
    expect(parser.push('TA\ndata:{"to')).toEqual([]);
    expect(parser.push('Version":7}\n\n')).toEqual([{ event: 'DELTA', data: '{"toVersion":7}' }]);
  });

  it('reads two events that arrived in one chunk', () => {
    const parser = new SseParser();

    const events = parser.push('event:DELTA\ndata:one\n\nevent:DELTA\ndata:two\n\n');

    expect(events.map((event) => event.data)).toEqual(['one', 'two']);
  });

  it('drops the one optional space after the colon and nothing more', () => {
    const parser = new SseParser();

    expect(parser.push('data:  two spaces\n\n')[0]?.data).toBe(' two spaces');
  });

  it('joins several data lines with newlines', () => {
    const parser = new SseParser();

    expect(parser.push('data:first\ndata:second\n\n')[0]?.data).toBe('first\nsecond');
  });

  it('calls an event without a name a message, as the specification does', () => {
    const parser = new SseParser();

    expect(parser.push('data:hello\n\n')[0]?.event).toBe('message');
  });

  /** A heartbeat, which is what a quiet seat map sends every fifteen seconds. */
  it('ignores a comment and does not dispatch an empty event for it', () => {
    const parser = new SseParser();

    expect(parser.push(':no change\n\n')).toEqual([]);
    expect(parser.push(':no change\n\n:no change\n\n')).toEqual([]);
  });

  it('keeps the reconnect delay the server asked for', () => {
    const parser = new SseParser();

    const events = parser.push('retry:200\n:watching the seat map\n\nevent:DELTA\ndata:x\n\n');

    expect(events).toEqual([{ event: 'DELTA', data: 'x', retry: 200 }]);
  });

  it('refuses a reconnect delay that is not a whole number of milliseconds', () => {
    const parser = new SseParser();

    expect(parser.push('retry:soon\ndata:x\n\n')[0]?.retry).toBeUndefined();
  });

  it('reads a stream that uses carriage returns', () => {
    const parser = new SseParser();

    expect(parser.push('event:DELTA\r\ndata:x\r\n\r\n')).toEqual([{ event: 'DELTA', data: 'x' }]);
  });

  it('ignores a field it does not know rather than refusing the event', () => {
    const parser = new SseParser();

    expect(parser.push('id:42\nevent:DELTA\ndata:x\n\n')).toEqual([{ event: 'DELTA', data: 'x' }]);
  });

  it('reads a field with no colon at all as an empty value', () => {
    const parser = new SseParser();

    expect(parser.push('data\n\n')).toEqual([{ event: 'message', data: '' }]);
  });
});

describe('reading a response', () => {
  it('yields the events of the body in order', async () => {
    const response = responseOf(['event:DELTA\ndata:one\n\n', 'event:DELTA\ndata:two\n\n']);

    const read: string[] = [];
    for await (const event of readEvents(response)) {
      read.push(event.data);
    }

    expect(read).toEqual(['one', 'two']);
  });

  it('ends when the stream ends, leaving an unterminated event unsent', async () => {
    const response = responseOf(['event:DELTA\ndata:one\n\n', 'event:DELTA\ndata:half']);

    const read: string[] = [];
    for await (const event of readEvents(response)) {
      read.push(event.data);
    }

    expect(read).toEqual(['one']);
  });

  it('refuses a response with no body to stream', async () => {
    const empty = { body: null } as Response;

    await expect(async () => {
      for await (const event of readEvents(empty)) {
        void event;
      }
    }).rejects.toThrow('no body');
  });
});

/** A response whose body hands out the given chunks, one read at a time. */
function responseOf(chunks: string[]): Response {
  const encoder = new TextEncoder();
  const stream = new ReadableStream<Uint8Array>({
    start(controller) {
      for (const chunk of chunks) {
        controller.enqueue(encoder.encode(chunk));
      }
      controller.close();
    },
  });
  return { body: stream } as Response;
}
