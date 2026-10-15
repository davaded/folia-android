import { readFileSync } from 'node:fs';
import { runInNewContext } from 'node:vm';
import { describe, expect, it, vi } from 'vitest';

// test/unit/android/filesystemCompat.test.ts
const source = readFileSync('packaging/android/filesystem-compat.js', 'utf8');

function createRuntime(options: { quota?: number; writeError?: Error; nativePicker?: () => void } = {}) {
    const events: string[] = [];
    const streams = { active: 0, maximum: 0 };
    class Handle {
        constructor(public name: string, public parent: Directory | null = null) {}
    }
    class FileHandle extends Handle {
        kind = 'file';
        bytes: number[] = [];
        async createWritable() {
            const file = this;
            return new WritableStream({
                start() { streams.maximum = Math.max(streams.maximum, ++streams.active); events.push(`open:${file.name}`); },
                async write(chunk: Uint8Array) {
                    await Promise.resolve();
                    if (options.writeError) throw options.writeError;
                    file.bytes.push(...chunk);
                },
                close() { streams.active--; events.push(`close:${file.name}`); },
                abort() { streams.active--; events.push(`abort:${file.name}`); },
            });
        }
    }
    class Directory extends Handle {
        kind = 'directory';
        entries = new Map<string, Directory | FileHandle>();
        async getDirectoryHandle(name: string) {
            if (!this.entries.has(name)) this.entries.set(name, new Directory(name, this));
            return this.entries.get(name) as Directory;
        }
        async getFileHandle(name: string) {
            if (!this.entries.has(name)) this.entries.set(name, new FileHandle(name, this));
            return this.entries.get(name) as FileHandle;
        }
        async removeEntry(name: string) { this.entries.delete(name); events.push(`remove:${name}`); }
        async resolve(handle: Handle) {
            const parts: string[] = [];
            let node: Handle | null = handle;
            while (node && node !== this) { parts.unshift(node.name); node = node.parent; }
            return node === this ? parts : null;
        }
    }
    class Input extends EventTarget {
        files: unknown[] = [];
        removed = false;
        type = '';
        multiple = false;
        webkitdirectory = false;
        hidden = false;
        click = vi.fn();
        setAttribute = vi.fn();
        remove() { this.removed = true; }
    }
    const originRoot = new Directory('');
    const inputs: Input[] = [];
    const storage = {
        getDirectory: vi.fn(async () => originRoot),
        estimate: async () => ({ usage: 0, quota: options.quota ?? 1000 }),
    };
    const window = {
        FileSystemHandle: Handle,
        FileSystemDirectoryHandle: Directory,
        FileSystemFileHandle: FileHandle,
        showDirectoryPicker: options.nativePicker as unknown as (options?: { signal?: AbortSignal }) => Promise<Directory>,
        crypto: { randomUUID: () => `snapshot-${inputs.length}` },
    };
    const document = {
        createElement: () => { const input = new Input(); inputs.push(input); return input; },
        body: { appendChild: vi.fn() },
    };
    runInNewContext(source, { window, navigator: { storage }, document, DOMException, Object, console });
    const imports = () => originRoot.entries.get('folia-android-imports') as Directory | undefined;
    return { window, inputs, originRoot, storage, events, streams, Directory, FileHandle, imports };
}

function file(path: string, bytes = [1, 2, 3], readError?: Error) {
    return {
        name: path.split('/').at(-1), webkitRelativePath: path, size: bytes.length,
        stream: () => new ReadableStream({
            start(controller) {
                if (readError) controller.error(readError);
                else { controller.enqueue(new Uint8Array(bytes)); controller.close(); }
            },
        }),
    };
}

describe('Android OPFS directory compatibility', () => {
    it('preserves a native picker and does not patch native permission methods', () => {
        const nativePicker = vi.fn();
        const runtime = createRuntime({ nativePicker });
        expect(runtime.window.showDirectoryPicker).toBe(nativePicker);
        expect('queryPermission' in runtime.originRoot).toBe(false);
    });

    it('streams files serially into a real directory, preserves paths and scopes permission to OPFS', async () => {
        const runtime = createRuntime();
        const promise = runtime.window.showDirectoryPicker();
        const input = runtime.inputs[0];
        expect(input.webkitdirectory).toBe(true);
        expect(input.click).toHaveBeenCalledOnce();
        input.files = [file('Music/Album/song.flac'), file('Music/Album/song.lrc'), file('Music/cover.jpg')];
        input.dispatchEvent(new Event('change'));
        const root = await promise;
        expect(root).toBeInstanceOf(runtime.Directory);
        const snapshot = runtime.imports()?.entries.get('snapshot-1') as typeof root;
        expect(root).toBe(snapshot.entries.get('Music'));
        expect(root.name).toBe('Music');
        expect(runtime.streams.maximum).toBe(1);
        expect(runtime.events.slice(0, 4)).toEqual(['open:song.flac', 'close:song.flac', 'open:song.lrc', 'close:song.lrc']);
        const lyric = (root.entries.get('Album') as typeof root).entries.get('song.lrc') as InstanceType<typeof runtime.FileHandle>;
        expect(lyric.bytes).toEqual([1, 2, 3]);
        expect(await (root as any).queryPermission({ mode: 'read' })).toBe('granted');
        expect(await (new runtime.Directory('external') as any).requestPermission()).toBe('denied');
        expect(input.removed).toBe(true);
        expect('electron' in runtime.window).toBe(false);
    });

    it.each(['cancel', 'change'])('rejects an empty %s with AbortError and removes the chooser', async event => {
        const runtime = createRuntime();
        const promise = runtime.window.showDirectoryPicker();
        runtime.inputs[0].dispatchEvent(new Event(event));
        await expect(promise).rejects.toMatchObject({ name: 'AbortError' });
        expect(runtime.inputs[0].removed).toBe(true);
        expect(runtime.storage.getDirectory).not.toHaveBeenCalled();
    });

    it.each(['Music/../bad.mp3', '/Music/bad.mp3', 'Music//bad.mp3', 'Music/dir\\bad.mp3'])('rejects unsafe path %s before writing', async path => {
        const runtime = createRuntime();
        const promise = runtime.window.showDirectoryPicker();
        runtime.inputs[0].files = [file(path)];
        runtime.inputs[0].dispatchEvent(new Event('change'));
        await expect(promise).rejects.toMatchObject({ name: 'DataError' });
        expect(runtime.storage.getDirectory).not.toHaveBeenCalled();
    });

    it('rejects insufficient storage before creating a snapshot', async () => {
        const runtime = createRuntime({ quota: 2 });
        const promise = runtime.window.showDirectoryPicker();
        runtime.inputs[0].files = [file('Music/song.mp3')];
        runtime.inputs[0].dispatchEvent(new Event('change'));
        await expect(promise).rejects.toMatchObject({ name: 'QuotaExceededError' });
        expect(runtime.storage.getDirectory).not.toHaveBeenCalled();
    });

    it.each([
        ['Music/song.mp3', 'Other/song.mp3'],
        ['Music/song.mp3', 'Music/song.mp3'],
    ])('rejects mixed roots or duplicate paths before writing: %j', async (first, second) => {
        const runtime = createRuntime();
        const promise = runtime.window.showDirectoryPicker();
        runtime.inputs[0].files = [file(first), file(second)];
        runtime.inputs[0].dispatchEvent(new Event('change'));
        await expect(promise).rejects.toMatchObject({ name: 'DataError' });
        expect(runtime.storage.getDirectory).not.toHaveBeenCalled();
    });

    it.each(['read', 'write'])('propagates %s failures and removes the incomplete snapshot', async stage => {
        const error = new DOMException(`Failed to ${stage} music`, 'NotReadableError');
        const runtime = createRuntime(stage === 'write' ? { writeError: error } : {});
        const promise = runtime.window.showDirectoryPicker();
        runtime.inputs[0].files = [file('Music/song.mp3', undefined, stage === 'read' ? error : undefined)];
        runtime.inputs[0].dispatchEvent(new Event('change'));
        await expect(promise).rejects.toBe(error);
        expect(runtime.imports()?.entries.size).toBe(0);
        expect(runtime.inputs[0].removed).toBe(true);
    });

    it('keeps repeated directory names in separate snapshots', async () => {
        const runtime = createRuntime();
        const roots = [];
        for (let index = 0; index < 2; index++) {
            const promise = runtime.window.showDirectoryPicker();
            runtime.inputs[index].files = [file('Music/song.mp3')];
            runtime.inputs[index].dispatchEvent(new Event('change'));
            roots.push(await promise);
        }
        expect(roots[0]).not.toBe(roots[1]);
        expect(roots.map(root => root.name)).toEqual(['Music', 'Music']);
        expect(runtime.imports()?.entries.size).toBe(2);
    });

    it('aborts an active stream and cleans up the incomplete directory', async () => {
        const runtime = createRuntime();
        const controller = new AbortController();
        const cancelled = vi.fn();
        const pending = file('Music/long.mp3');
        pending.stream = () => new ReadableStream({ cancel: cancelled });
        const promise = runtime.window.showDirectoryPicker({ signal: controller.signal });
        runtime.inputs[0].files = [pending];
        runtime.inputs[0].dispatchEvent(new Event('change'));
        await vi.waitFor(() => expect(runtime.events).toContain('open:long.mp3'));
        controller.abort();
        await expect(promise).rejects.toMatchObject({ name: 'AbortError' });
        expect(cancelled).toHaveBeenCalledOnce();
        expect(runtime.imports()?.entries.size).toBe(0);
    });

    it('rejects an aborted chooser without opening it', async () => {
        const runtime = createRuntime();
        const controller = new AbortController();
        controller.abort();
        await expect(runtime.window.showDirectoryPicker({ signal: controller.signal })).rejects.toMatchObject({ name: 'AbortError' });
        expect(runtime.inputs[0].click).not.toHaveBeenCalled();
        expect(runtime.inputs[0].removed).toBe(true);
    });
});
