import { afterEach, describe, expect, it, vi } from 'vitest';
import { areAiFeaturesAvailable, getWebAiProvider, isAndroidApkRuntime } from '../../../src/services/runtimeConfig';

// 当前文件：验证 Docker Web 运行时配置优先级和 provider 归一化。

describe('getWebAiProvider', () => {
    afterEach(() => {
        vi.unstubAllGlobals();
    });

    it('prefers the Docker runtime provider', () => {
        vi.stubGlobal('window', {
            __FOLIA_RUNTIME_CONFIG__: { aiProvider: 'openai' },
        });

        expect(getWebAiProvider()).toBe('openai');
    });

    it('accepts the Docker gemini provider', () => {
        vi.stubGlobal('window', {
            __FOLIA_RUNTIME_CONFIG__: { aiProvider: 'gemini' },
        });

        expect(getWebAiProvider()).toBe('gemini');
    });

    it('defaults to gemini without runtime configuration', () => {
        vi.stubGlobal('window', {});

        expect(getWebAiProvider()).toBe('gemini');
    });
});

describe('Android APK runtime', () => {
    afterEach(() => {
        vi.unstubAllGlobals();
    });

    it('is recognised only from the platform the APK injects', () => {
        vi.stubGlobal('window', { __FOLIA_RUNTIME_CONFIG__: { platform: 'android' } });
        expect(isAndroidApkRuntime()).toBe(true);
        expect(areAiFeaturesAvailable()).toBe(false);
    });

    it('keeps AI features on the web, Docker and desktop builds', () => {
        vi.stubGlobal('window', { __FOLIA_RUNTIME_CONFIG__: { deployment: 'docker' } });
        expect(isAndroidApkRuntime()).toBe(false);
        expect(areAiFeaturesAvailable()).toBe(true);

        vi.stubGlobal('window', { electron: {} });
        expect(areAiFeaturesAvailable()).toBe(true);
    });
});
