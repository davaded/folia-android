package top.izuna.foliamajor.android;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.content.res.AssetManager;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

// Serves the bundled Web build on a stable loopback origin and optionally forwards its API.
public final class LocalAssetServer {
    public static final String ORIGIN = "http://127.0.0.1:18739";
    private static final int PORT = 18739;
    private static final int MAX_LINE_BYTES = 8192;
    private static final int MAX_HEADER_BYTES = 32768;
    private static final long MAX_API_BODY_BYTES = 16L * 1024 * 1024;
    private static final Set<String> API_NAMES = immutableSet("api", "netease", "kugou", "qq");
    private static final Set<String> ROOT_API_NAMES = immutableSet(
            "generate-theme", "generate-theme_openai", "lyric-proxy", "segment-lyrics");
    private static final Set<String> PROXY_METHODS = immutableSet("GET", "HEAD", "POST", "OPTIONS");
    private static final Set<String> HOP_HEADERS = immutableSet(
            "connection", "keep-alive", "proxy-authenticate", "proxy-authorization",
            "te", "trailer", "transfer-encoding", "upgrade");

    private final AssetManager assets;
    private final Map<String, Long> assetLengths = new ConcurrentHashMap<>();
    private final Set<Socket> clients = ConcurrentHashMap.newKeySet();
    private ServerSocket server;
    private ThreadPoolExecutor workers;

    public LocalAssetServer(Context context) {
        assets = context.getApplicationContext().getAssets();
    }

    /** Starts once per process; a port conflict is reported instead of selecting another origin. */
    public synchronized void start() throws IOException {
        if (server != null && !server.isClosed()) return;
        ServerSocket listener = new ServerSocket();
        try {
            listener.setReuseAddress(true);
            listener.bind(new InetSocketAddress(InetAddress.getByAddress(
                    new byte[]{127, 0, 0, 1}), PORT), 32);
        } catch (IOException error) {
            listener.close();
            throw error;
        }
        ThreadPoolExecutor executor = new ThreadPoolExecutor(4, 4, 0L,
                TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(32), runnable -> {
                    Thread thread = new Thread(runnable, "folia-local-http-worker");
                    thread.setDaemon(true);
                    return thread;
                });
        server = listener;
        workers = executor;
        Thread acceptThread = new Thread(() -> acceptClients(listener, executor),
                "folia-local-http");
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    public synchronized void stop() {
        closeQuietly(server);
        server = null;
        for (Socket client : clients) closeQuietly(client);
        clients.clear();
        if (workers != null) workers.shutdownNow();
        workers = null;
    }

    private void acceptClients(ServerSocket listener, ThreadPoolExecutor executor) {
        try {
            while (!listener.isClosed()) {
                Socket client = listener.accept();
                client.setSoTimeout(10000);
                clients.add(client);
                try {
                    executor.execute(() -> serve(client));
                } catch (RejectedExecutionException error) {
                    clients.remove(client);
                    closeQuietly(client);
                }
            }
        } catch (IOException ignored) {
            // Closing the listening socket interrupts accept during Activity teardown.
        } finally {
            closeQuietly(listener);
            executor.shutdown();
        }
    }

    /** Handles one request per socket so errors and upstream bodies cannot desynchronize requests. */
    private void serve(Socket client) {
        try (Socket ignored = client;
             InputStream input = new BufferedInputStream(client.getInputStream());
             OutputStream output = new BufferedOutputStream(client.getOutputStream())) {
            boolean head = false;
            try {
                Request request = readRequest(input);
                head = request.method.equals("HEAD");
                String path = decodePath(request.target);
                if (path.equals("/__android_diagnostics")) {
                    serveDiagnostics(request, input, output);
                } else if (isApiPath(path)) {
                    if (API_NAMES.contains(path.substring(1).split("/", 2)[0])) {
                        proxyApi(request, input, output);
                    } else {
                        sendError(output, 404, "API_NOT_CONFIGURED", head, null);
                    }
                } else if (!request.method.equals("GET") && !head) {
                    sendError(output, 405, "METHOD_NOT_ALLOWED", false, "Allow: GET, HEAD\r\n");
                } else {
                    serveAsset(request, path, output);
                }
            } catch (HttpError error) {
                sendError(output, error.status, error.code, head, null);
            }
            output.flush();
        } catch (IOException ignored) {
            // Navigation cancellation and WebView media seeks routinely close active sockets.
        } finally {
            clients.remove(client);
        }
    }

    /** Parses bounded HTTP headers and rejects ambiguous framing before forwarding any body. */
    private static Request readRequest(InputStream input) throws IOException, HttpError {
        String first = readLine(input);
        String[] parts = first.split(" ", -1);
        if (parts.length != 3 || parts[0].isEmpty()
                || !(parts[2].equals("HTTP/1.1") || parts[2].equals("HTTP/1.0"))) {
            throw new HttpError(400, "INVALID_REQUEST");
        }
        Map<String, String> headers = new HashMap<>();
        int bytes = first.length() + 2;
        for (int count = 0; ; count++) {
            String line = readLine(input);
            bytes += line.length() + 2;
            if (bytes > MAX_HEADER_BYTES || count > 100) {
                throw new HttpError(431, "HEADERS_TOO_LARGE");
            }
            if (line.isEmpty()) break;
            int colon = line.indexOf(':');
            if (colon < 1 || !line.substring(0, colon).matches("[!#$%&'*+.^_`|~0-9A-Za-z-]+")) {
                throw new HttpError(400, "INVALID_HEADER");
            }
            String name = line.substring(0, colon).toLowerCase(Locale.ROOT);
            String value = line.substring(colon + 1).trim();
            if (headers.put(name, value) != null) {
                throw new HttpError(400, "DUPLICATE_HEADER");
            }
        }
        if (headers.containsKey("transfer-encoding")) {
            throw new HttpError(501, "TRANSFER_ENCODING_NOT_SUPPORTED");
        }
        long length = 0;
        if (headers.containsKey("content-length")) {
            try {
                String value = headers.get("content-length");
                if (!value.matches("[0-9]+")) throw new NumberFormatException();
                length = Long.parseLong(value);
            } catch (NumberFormatException error) {
                throw new HttpError(400, "INVALID_CONTENT_LENGTH");
            }
        }
        return new Request(parts[0], parts[1], headers, length);
    }

    private static String readLine(InputStream input) throws IOException, HttpError {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        for (int count = 0; count <= MAX_LINE_BYTES; count++) {
            int value = input.read();
            if (value == -1) throw new HttpError(400, "INCOMPLETE_REQUEST");
            if (value == '\r') {
                if (input.read() != '\n') throw new HttpError(400, "INVALID_LINE_ENDING");
                return bytes.toString(StandardCharsets.US_ASCII.name());
            }
            if (value < 32 && value != '\t' || value > 126) {
                throw new HttpError(400, "INVALID_HEADER_CHARACTER");
            }
            bytes.write(value);
        }
        throw new HttpError(431, "HEADER_LINE_TOO_LARGE");
    }

    /** Decodes a path exactly once; dot segments, invalid UTF-8 and backslashes fail closed. */
    private static String decodePath(String target) throws HttpError {
        if (!target.startsWith("/") || target.indexOf('#') >= 0) {
            throw new HttpError(400, "INVALID_PATH");
        }
        String raw = target.split("\\?", 2)[0];
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        for (int index = 0; index < raw.length(); index++) {
            char value = raw.charAt(index);
            if (value == '%') {
                if (index + 2 >= raw.length()) throw new HttpError(400, "INVALID_PATH");
                int high = Character.digit(raw.charAt(++index), 16);
                int low = Character.digit(raw.charAt(++index), 16);
                if (high < 0 || low < 0) throw new HttpError(400, "INVALID_PATH");
                bytes.write(high * 16 + low);
            } else {
                if (value > 127) throw new HttpError(400, "INVALID_PATH");
                bytes.write(value);
            }
        }
        final String path;
        try {
            path = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes.toByteArray())).toString();
        } catch (CharacterCodingException error) {
            throw new HttpError(400, "INVALID_PATH");
        }
        for (int index = 0; index < path.length(); index++) {
            char value = path.charAt(index);
            if (value < 32 || value == 127 || value == '\\') {
                throw new HttpError(400, "INVALID_PATH");
            }
        }
        for (String segment : path.split("/", -1)) {
            if (segment.equals(".") || segment.equals("..")) {
                throw new HttpError(403, "PATH_TRAVERSAL_REJECTED");
            }
        }
        return path;
    }

    private static boolean isApiPath(String path) {
        String first = path.substring(1).split("/", 2)[0];
        return API_NAMES.contains(first) || ROOT_API_NAMES.contains(first);
    }

    private void serveAsset(Request request, String path, OutputStream output)
            throws IOException, HttpError {
        String relative = path.equals("/") ? "index.html" : path.substring(1);
        Resource resource = findAsset(relative);
        if (resource == null && canUseSpaFallback(path, request.headers)) {
            resource = findAsset("index.html");
        }
        boolean head = request.method.equals("HEAD");
        if (resource == null) {
            sendError(output, 404, "ASSET_NOT_FOUND", head, null);
            return;
        }
        long start = 0;
        long end = resource.length - 1;
        boolean partial = request.headers.containsKey("range");
        if (partial) {
            try {
                long[] range = parseRange(request.headers.get("range"), resource.length);
                start = range[0];
                end = range[1];
            } catch (HttpError error) {
                sendError(output, 416, "RANGE_NOT_SATISFIABLE", head,
                        "Content-Range: bytes */" + resource.length + "\r\n");
                return;
            }
        }
        long length = partial ? end - start + 1 : resource.length;
        String extra = "Accept-Ranges: bytes\r\nCache-Control: no-cache\r\n";
        if (partial) extra += "Content-Range: bytes " + start + "-" + end + "/" + resource.length + "\r\n";
        writeHeaders(output, partial ? 206 : 200, resource.mime, length, extra);
        if (!head && length > 0) {
            try (InputStream input = assets.open("web/" + resource.path)) {
                skipExactly(input, start);
                copyExactly(input, output, length);
            }
        }
    }

    private static boolean canUseSpaFallback(String path, Map<String, String> headers) {
        if (path.startsWith("/assets/") || path.equals("/assets")) return false;
        for (String segment : path.split("/")) {
            if (segment.contains(".")) return false;
        }
        String accept = headers.get("accept");
        return accept == null || accept.contains("text/html");
    }

    private Resource findAsset(String path) throws IOException {
        Long length = assetLengths.get(path);
        if (length == null) {
            try (AssetFileDescriptor descriptor = assets.openFd("web/" + path)) {
                length = descriptor.getLength();
            } catch (IOException compressedOrMissing) {
                try (InputStream input = assets.open("web/" + path)) {
                    long count = 0;
                    byte[] buffer = new byte[16384];
                    int read;
                    while ((read = input.read(buffer)) != -1) count += read;
                    length = count;
                } catch (FileNotFoundException missing) {
                    return null;
                }
            }
            assetLengths.put(path, length);
        }
        return new Resource(path, length, mimeType(path));
    }

    /** Supports one RFC byte interval, including open-ended and suffix media seeks. */
    private static long[] parseRange(String header, long length) throws HttpError {
        if (!header.startsWith("bytes=") || header.indexOf(',') >= 0 || length == 0) {
            throw new HttpError(416, "RANGE_NOT_SATISFIABLE");
        }
        String value = header.substring(6);
        if (!value.matches("[0-9]*-[0-9]*") || value.equals("-")) {
            throw new HttpError(416, "RANGE_NOT_SATISFIABLE");
        }
        String[] parts = value.split("-", -1);
        try {
            if (parts[0].isEmpty()) {
                long suffix = Long.parseLong(parts[1]);
                if (suffix <= 0) throw new NumberFormatException();
                return new long[]{Math.max(0, length - suffix), length - 1};
            }
            long start = Long.parseLong(parts[0]);
            long end = parts[1].isEmpty() ? length - 1 : Long.parseLong(parts[1]);
            if (start >= length || end < start) throw new NumberFormatException();
            return new long[]{start, Math.min(end, length - 1)};
        } catch (NumberFormatException error) {
            throw new HttpError(416, "RANGE_NOT_SATISFIABLE");
        }
    }

    /** Forwards only explicit API namespaces to the build-time backend origin. */
    private void proxyApi(Request request, InputStream input, OutputStream output)
            throws IOException, HttpError {
        boolean head = request.method.equals("HEAD");
        String configured = BuildConfig.FOLIA_ANDROID_API_ORIGIN.trim();
        if (configured.isEmpty()) {
            sendError(output, 503, "BACKEND_NOT_CONFIGURED", head, null);
            return;
        }
        if (!PROXY_METHODS.contains(request.method)) {
            sendError(output, 405, "METHOD_NOT_ALLOWED", head, "Allow: GET, HEAD, POST, OPTIONS\r\n");
            return;
        }
        if (request.length > MAX_API_BODY_BYTES) throw new HttpError(413, "REQUEST_BODY_TOO_LARGE");
        final URI origin;
        try {
            origin = URI.create(configured);
            if (!(origin.getScheme().equals("https") || origin.getScheme().equals("http"))
                    || origin.getHost() == null || origin.getUserInfo() != null
                    || origin.getQuery() != null || origin.getFragment() != null
                    || !(origin.getPath().isEmpty() || origin.getPath().equals("/"))) {
                throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException | NullPointerException error) {
            sendError(output, 503, "INVALID_API_ORIGIN", head, null);
            return;
        }
        String base = origin.toString().replaceAll("/+$", "");
        HttpURLConnection connection = (HttpURLConnection) new URL(base + request.target).openConnection();
        connection.setConnectTimeout(10000);
        connection.setReadTimeout(30000);
        connection.setInstanceFollowRedirects(false);
        connection.setRequestMethod(request.method);
        connection.setRequestProperty("Accept-Encoding", "identity");
        boolean headersSent = false;
        try {
            for (Map.Entry<String, String> header : request.headers.entrySet()) {
                String name = header.getKey();
                if (!HOP_HEADERS.contains(name) && !name.equals("host")
                        && !name.equals("content-length") && !name.equals("accept-encoding")) {
                    connection.setRequestProperty(name, header.getValue());
                }
            }
            if (request.length > 0) {
                if (request.method.equals("GET") || head) throw new HttpError(400, "UNEXPECTED_REQUEST_BODY");
                connection.setDoOutput(true);
                connection.setFixedLengthStreamingMode(request.length);
                try (OutputStream upstream = connection.getOutputStream()) {
                    copyExactly(input, upstream, request.length);
                }
            }
            int status = connection.getResponseCode();
            StringBuilder headers = new StringBuilder();
            for (Map.Entry<String, List<String>> header : connection.getHeaderFields().entrySet()) {
                if (header.getKey() == null) continue;
                String name = header.getKey().toLowerCase(Locale.ROOT);
                if (HOP_HEADERS.contains(name) || name.equals("content-length")
                        || name.equals("content-type")) continue;
                for (String value : header.getValue()) {
                    if (value.indexOf('\r') < 0 && value.indexOf('\n') < 0) {
                        headers.append(header.getKey()).append(": ").append(value).append("\r\n");
                    }
                }
            }
            long length = connection.getHeaderFieldLong("Content-Length", -1L);
            String type = connection.getContentType();
            writeHeaders(output, status, type == null ? "application/octet-stream" : type,
                    length, headers.toString());
            headersSent = true;
            if (!head && status != 204 && status != 304) {
                InputStream response = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
                if (response != null) {
                    try (InputStream upstream = response) {
                        if (length >= 0) copyExactly(upstream, output, length);
                        else copyToEnd(upstream, output);
                    }
                }
            }
        } catch (IOException error) {
            // Once response headers are committed, closing the socket signals an incomplete body.
            // Before that point callers still receive a deterministic JSON gateway error.
            if (headersSent) throw error;
            sendError(output, 502, "BACKEND_UNREACHABLE", head, null);
        } finally {
            connection.disconnect();
        }
    }

    private static void serveDiagnostics(Request request, InputStream input, OutputStream output)
            throws IOException, HttpError {
        boolean head = request.method.equals("HEAD");
        if (!BuildConfig.DEBUG) {
            sendError(output, 404, "NOT_FOUND", head, null);
            return;
        }
        if (!request.method.equals("POST")) {
            sendError(output, 405, "METHOD_NOT_ALLOWED", head, "Allow: POST\r\n");
            return;
        }
        if (request.length > 16384) throw new HttpError(413, "REQUEST_BODY_TOO_LARGE");
        ByteArrayOutputStream body = new ByteArrayOutputStream((int) request.length);
        copyExactly(input, body, request.length);
        Log.i("FoliaDiagnostics", body.toString(StandardCharsets.UTF_8.name()));
        byte[] response = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
        writeHeaders(output, 200, "application/json; charset=utf-8", response.length,
                "Cache-Control: no-store\r\n");
        output.write(response);
    }

    private static void skipExactly(InputStream input, long count) throws IOException {
        while (count > 0) {
            long skipped = input.skip(count);
            if (skipped == 0) {
                if (input.read() == -1) throw new IOException("Unexpected asset end");
                skipped = 1;
            }
            count -= skipped;
        }
    }

    private static void copyExactly(InputStream input, OutputStream output, long count) throws IOException {
        byte[] buffer = new byte[32768];
        while (count > 0) {
            int read = input.read(buffer, 0, (int) Math.min(buffer.length, count));
            if (read == -1) throw new IOException("Unexpected stream end");
            output.write(buffer, 0, read);
            count -= read;
        }
    }

    private static void copyToEnd(InputStream input, OutputStream output) throws IOException {
        byte[] buffer = new byte[32768];
        int read;
        while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
    }

    private static void sendError(OutputStream output, int status, String code, boolean head,
                                  String extra) throws IOException {
        byte[] body = ("{\"error\":\"" + code + "\"}").getBytes(StandardCharsets.UTF_8);
        writeHeaders(output, status, "application/json; charset=utf-8", body.length,
                "Cache-Control: no-store\r\n" + (extra == null ? "" : extra));
        if (!head) output.write(body);
    }

    private static void writeHeaders(OutputStream output, int status, String type, long length,
                                     String extra) throws IOException {
        String header = "HTTP/1.1 " + status + " " + reason(status) + "\r\n"
                + "Connection: close\r\nX-Content-Type-Options: nosniff\r\n"
                + "Content-Type: " + type + "\r\n"
                + (length >= 0 ? "Content-Length: " + length + "\r\n" : "")
                + extra + "\r\n";
        output.write(header.getBytes(StandardCharsets.ISO_8859_1));
    }

    private static String reason(int status) {
        switch (status) {
            case 200: return "OK";
            case 206: return "Partial Content";
            case 400: return "Bad Request";
            case 403: return "Forbidden";
            case 404: return "Not Found";
            case 405: return "Method Not Allowed";
            case 413: return "Content Too Large";
            case 416: return "Range Not Satisfiable";
            case 431: return "Request Header Fields Too Large";
            case 501: return "Not Implemented";
            case 502: return "Bad Gateway";
            case 503: return "Service Unavailable";
            default: return "Upstream Response";
        }
    }

    private static Set<String> immutableSet(String... values) {
        return Collections.unmodifiableSet(new HashSet<>(Arrays.asList(values)));
    }

    private static String mimeType(String path) {
        String extension = path.substring(path.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        switch (extension) {
            case "html": return "text/html; charset=utf-8";
            case "js": case "mjs": return "text/javascript; charset=utf-8";
            case "css": return "text/css; charset=utf-8";
            case "json": case "map": return "application/json; charset=utf-8";
            case "webmanifest": return "application/manifest+json; charset=utf-8";
            case "svg": return "image/svg+xml";
            case "png": return "image/png";
            case "jpg": case "jpeg": return "image/jpeg";
            case "webp": return "image/webp";
            case "avif": return "image/avif";
            case "gif": return "image/gif";
            case "ico": return "image/x-icon";
            case "woff": return "font/woff";
            case "woff2": return "font/woff2";
            case "ttf": return "font/ttf";
            case "otf": return "font/otf";
            case "wasm": return "application/wasm";
            case "mp3": return "audio/mpeg";
            case "m4a": return "audio/mp4";
            case "aac": return "audio/aac";
            case "flac": return "audio/flac";
            case "wav": return "audio/wav";
            case "ogg": return "audio/ogg";
            case "opus": return "audio/ogg; codecs=opus";
            case "mp4": return "video/mp4";
            case "webm": return "video/webm";
            case "txt": case "lrc": return "text/plain; charset=utf-8";
            case "xml": return "application/xml; charset=utf-8";
            default: return "application/octet-stream";
        }
    }

    private static void closeQuietly(java.io.Closeable closeable) {
        if (closeable == null) return;
        try {
            closeable.close();
        } catch (IOException ignored) {
            // Teardown is idempotent, including sockets already closed by their owner.
        }
    }

    private static final class Request {
        final String method;
        final String target;
        final Map<String, String> headers;
        final long length;

        Request(String method, String target, Map<String, String> headers, long length) {
            this.method = method;
            this.target = target;
            this.headers = headers;
            this.length = length;
        }
    }

    private static final class Resource {
        final String path;
        final long length;
        final String mime;

        Resource(String path, long length, String mime) {
            this.path = path;
            this.length = length;
            this.mime = mime;
        }
    }

    private static final class HttpError extends Exception {
        final int status;
        final String code;

        HttpError(int status, String code) {
            this.status = status;
            this.code = code;
        }
    }
}
