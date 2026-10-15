// packaging/android/runtime-diagnostics.js
// Debug-only runtime evidence; no settings, credentials, metadata or music bytes are collected.
(() => {
  if (new URLSearchParams(location.search).get('androidDebug') !== '1') return;
  const errors = [];
  const reportError = error => {
    errors.push(String(error?.message || error || 'Unknown script error').slice(0, 700));
    if (errors.length > 6) errors.shift();
  };
  window.addEventListener('error', event => reportError(event.error || event.message));
  window.addEventListener('unhandledrejection', event => reportError(event.reason));
  let frames = 0;
  let last = performance.now();
  let request = 0;
  const count = () => { frames++; request = requestAnimationFrame(count); };
  request = requestAnimationFrame(count);
  const timer = setInterval(() => {
    const now = performance.now();
    const sample = {
      visible: document.visibilityState,
      fps: Math.round(frames * 1000 / (now - last)),
      viewport: [innerWidth, innerHeight, devicePixelRatio],
      secureContext: isSecureContext,
      webAudio: typeof AudioContext === 'function',
      opfs: typeof navigator.storage?.getDirectory === 'function',
      directoryPicker: typeof window.showDirectoryPicker === 'function',
      audio: Array.from(document.querySelectorAll('audio')).map(audio => ({
        paused: audio.paused,
        readyState: audio.readyState,
        currentTime: Math.round(audio.currentTime * 10) / 10,
        error: audio.error?.code || null,
      })),
      canvases: document.querySelectorAll('canvas').length,
      errors: errors.splice(0),
    };
    frames = 0;
    last = now;
    void fetch('/__android_diagnostics', {
      method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(sample),
    }).catch(() => {});
  }, 5000);
  window.addEventListener('pagehide', () => {
    cancelAnimationFrame(request);
    clearInterval(timer);
  }, { once: true });
})();
