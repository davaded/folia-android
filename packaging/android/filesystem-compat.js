// packaging/android/filesystem-compat.js
(() => {
    if (typeof window.showDirectoryPicker === 'function' || typeof navigator.storage?.getDirectory !== 'function') return;

    const storage = navigator.storage;
    const fail = (message, name = 'OperationError') => new DOMException(message, name);
    const checkAbort = signal => {
        if (signal?.aborted) throw fail('Directory import was cancelled.', 'AbortError');
    };

    // Only origin-private handles receive the permission API expected by the upstream library.
    const addPermissions = prototype => {
        if (!prototype || prototype === Object.prototype) return;
        for (const method of ['queryPermission', 'requestPermission']) {
            if (typeof prototype[method] === 'function') continue;
            Object.defineProperty(prototype, method, {
                configurable: true,
                value: async function () {
                    const root = await storage.getDirectory();
                    return await root.resolve(this) === null ? 'denied' : 'granted';
                },
            });
        }
    };
    addPermissions(window.FileSystemHandle?.prototype);
    addPermissions(window.FileSystemDirectoryHandle?.prototype);
    addPermissions(window.FileSystemFileHandle?.prototype);

    // Validate the whole selection before creating any files; never flatten or normalize unsafe paths.
    const inspectFiles = files => {
        let rootName;
        const paths = new Set();
        const entries = files.map(file => {
            const path = file.webkitRelativePath;
            const parts = typeof path === 'string' ? path.split('/') : [];
            if (parts.length < 2 || parts.some(part => !part || part === '.' || part === '..' || /[\\\0]/.test(part))
                || parts.at(-1) !== file.name) {
                throw fail(`Invalid directory entry: ${path || file.name}`, 'DataError');
            }
            rootName ??= parts[0];
            if (parts[0] !== rootName || paths.has(path)) throw fail(`Conflicting directory entry: ${path}`, 'DataError');
            paths.add(path);
            return { file, parts: parts.slice(1) };
        });
        return { rootName, entries };
    };

    // Keep a separate snapshot per import so a failed copy cannot corrupt an existing library.
    const copySelection = async (files, signal) => {
        checkAbort(signal);
        const { rootName, entries } = inspectFiles(files);
        const total = files.reduce((sum, file) => sum + file.size, 0);
        const estimate = await storage.estimate?.();
        if (Number.isFinite(estimate?.quota) && Number.isFinite(estimate?.usage) && total > estimate.quota - estimate.usage) {
            throw fail('Not enough app storage to copy this music directory.', 'QuotaExceededError');
        }
        const originRoot = await storage.getDirectory();
        const imports = await originRoot.getDirectoryHandle('folia-android-imports', { create: true });
        const id = window.crypto?.randomUUID?.() || `${Date.now()}-${Math.random().toString(36).slice(2)}`;
        const snapshot = await imports.getDirectoryHandle(id, { create: true });
        try {
            const root = await snapshot.getDirectoryHandle(rootName, { create: true });
            addPermissions(Object.getPrototypeOf(root));
            for (const { file, parts } of entries) {
                checkAbort(signal);
                let directory = root;
                for (const part of parts.slice(0, -1)) directory = await directory.getDirectoryHandle(part, { create: true });
                const handle = await directory.getFileHandle(parts.at(-1), { create: true });
                addPermissions(Object.getPrototypeOf(handle));
                if (typeof handle.createWritable !== 'function') throw fail('This runtime cannot write music into app storage.', 'NotSupportedError');
                const writable = await handle.createWritable();
                // pipeTo bounds memory to a single stream and propagates read, write and quota errors.
                await file.stream().pipeTo(writable, signal ? { signal } : undefined);
            }
            checkAbort(signal);
            return root;
        } catch (error) {
            try {
                await imports.removeEntry(id, { recursive: true });
            } catch (cleanupError) {
                console.error('[Folia Android] Could not remove incomplete directory import:', cleanupError);
            }
            throw error;
        }
    };

    // The input is clicked synchronously so Android retains the initiating user gesture.
    window.showDirectoryPicker = (options = {}) => new Promise((resolve, reject) => {
        const input = document.createElement('input');
        input.type = 'file';
        input.multiple = true;
        input.webkitdirectory = true;
        input.setAttribute('webkitdirectory', '');
        input.hidden = true;
        const signal = options.signal;
        let completed = false;
        const cleanup = () => {
            input.removeEventListener('change', changed);
            input.removeEventListener('cancel', cancelled);
            signal?.removeEventListener('abort', cancelled);
            input.remove();
        };
        const finish = (error, root) => {
            if (completed) return;
            completed = true;
            cleanup();
            if (error) reject(error);
            else resolve(root);
        };
        const cancelled = () => finish(fail('Directory selection was cancelled.', 'AbortError'));
        const changed = async () => {
            const files = Array.from(input.files || []);
            cleanup();
            if (!files.length) return cancelled();
            try {
                finish(null, await copySelection(files, signal));
            } catch (error) {
                finish(error);
            }
        };
        input.addEventListener('change', changed, { once: true });
        input.addEventListener('cancel', cancelled, { once: true });
        signal?.addEventListener('abort', cancelled, { once: true });
        try {
            checkAbort(signal);
            document.body.appendChild(input);
            input.click();
        } catch (error) {
            finish(error);
        }
    });
})();
