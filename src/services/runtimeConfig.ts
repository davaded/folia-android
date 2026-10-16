// 当前文件：读取 Docker 注入的 Web 运行时配置，并兼容既有 Vite 构建变量。

export type WebAiProvider = 'gemini' | 'openai';

const normalizeAiProvider = (value: unknown): WebAiProvider => (
    value === 'openai' ? 'openai' : 'gemini'
);

export const getWebAiProvider = (): WebAiProvider => {
    if (typeof window !== 'undefined') {
        const runtimeValue = window.__FOLIA_RUNTIME_CONFIG__?.aiProvider;
        if (runtimeValue) return normalizeAiProvider(runtimeValue);
    }

    return normalizeAiProvider(import.meta.env.VITE_AI_PROVIDER);
};

/** Android 车机 APK：packaging/android/build-apk.mjs 只往 APK 内的 runtime-config.js 写入 platform。 */
export const isAndroidApkRuntime = (): boolean => (
    typeof window !== 'undefined' && window.__FOLIA_RUNTIME_CONFIG__?.platform === 'android'
);

/**
 * AI 主题推断与 AI 歌词分词是否提供。车机 APK 不提供：不发请求，相关入口也不显示。
 * 命令面板用 `platform: ['web', 'electron']` 表达同一件事，两处都落在 isAndroidApkRuntime 上。
 */
export const areAiFeaturesAvailable = (): boolean => !isAndroidApkRuntime();
