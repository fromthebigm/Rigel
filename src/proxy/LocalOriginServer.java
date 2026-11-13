package proxy;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.URLDecoder;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class LocalOriginServer {
    public static final int FIXED_PORT = 47653;
    private static final int CONNECT_TIMEOUT_MS = 15_000;
    private static final int READ_TIMEOUT_MS = 60_000;
    private static final int MAX_TEXT_BYTES = 16 * 1024 * 1024;

    private volatile HttpServer server;
    private volatile int port = FIXED_PORT;
    private volatile boolean degraded;
    private volatile String originHost;
    private ExecutorService executor;

    public LocalOriginServer() {
        this(System.getProperty("rigel.local.origin.host", ""));
    }

    public LocalOriginServer(String originHost) {
        this.originHost = originHost == null ? "" : originHost.trim();
    }

    public synchronized void start() {
        if (server != null) return;
        Path portFile = portFile();
        int desired = FIXED_PORT;
        if (Files.isRegularFile(portFile)) {
            try {
                int saved = Integer.parseInt(Files.readString(portFile).trim());
                if (saved >= 1 && saved <= 65535) desired = saved;
            } catch (Exception ignored) {
            }
        }
        HttpServer created = tryBind(FIXED_PORT);
        if (created == null) created = tryBind(desired);
        if (created == null) {
            created = bindNextFree();
            degraded = true;
        }
        if (created == null) {
            throw new IllegalStateException("Could not start local origin server");
        }
        server = created;
        port = created.getAddress().getPort();
        if (port != FIXED_PORT) degraded = true;
        executor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "rigel-local-origin");
            t.setDaemon(true);
            return t;
        });
        server.setExecutor(executor);
        server.createContext("/", this::handle);
        server.start();
        persistPort(port);
        System.out.println("[Rigel] Local origin server running on 127.0.0.1:" + port
                + (degraded ? " (degraded: not the fixed port)" : ""));
    }

    private HttpServer tryBind(int p) {
        try {
            return HttpServer.create(new InetSocketAddress("127.0.0.1", p), 0);
        } catch (IOException e) {
            return null;
        }
    }

    private HttpServer bindNextFree() {
        for (int p = FIXED_PORT + 1; p <= 65535; p++) {
            HttpServer s = tryBind(p);
            if (s != null) return s;
        }
        try {
            return HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            return null;
        }
    }

    private void persistPort(int p) {
        try {
            Path f = portFile();
            Files.createDirectories(f.getParent());
            Files.writeString(f, Integer.toString(p));
        } catch (Exception ignored) {
        }
    }

    private Path portFile() {
        try {
            return config.Settings.DIR.resolve("local-origin.port");
        } catch (Throwable t) {
            return Path.of("local-origin.port");
        }
    }

    public int getPort() {
        return port;
    }

    public boolean isDegraded() {
        return degraded;
    }

    public synchronized void stop() {
        HttpServer running = server;
        server = null;
        if (running != null) running.stop(0);
        if (executor != null) executor.shutdownNow();
        executor = null;
    }

    private void handle(HttpExchange exchange) {
        try {
            String method = exchange.getRequestMethod();
            URI uri = exchange.getRequestURI();
            String path = uri.getRawPath();
            if (path == null || path.isEmpty()) path = "/";
            String query = uri.getRawQuery();

            Target target = resolveTarget(path);
            if (target == null) {
                byte[] body = "<!doctype html><title>Rigel local origin</title>".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream out = exchange.getResponseBody()) { out.write(body); }
                return;
            }

            String upstreamPath = target.path;
            if (query != null && !query.isEmpty()) upstreamPath += "?" + query;

            InetAddress address;
            try {
                address = resolveHost(target.host);
            } catch (UnknownHostException e) {
                sendError(exchange, 502, "DNS resolution failed: " + e.getMessage());
                return;
            }

            int upstreamPort = target.https ? 443 : 80;
            Socket socket = target.https
                    ? openTlsSocket(target.host, address, upstreamPort)
                    : openPlainSocket(address, upstreamPort);
            if (socket == null) {
                sendError(exchange, 502, "Could not connect to upstream");
                return;
            }

            try (socket) {
                socket.setSoTimeout(READ_TIMEOUT_MS);
                OutputStream upstreamOut = socket.getOutputStream();
                InputStream upstreamIn = socket.getInputStream();

                StringBuilder head = new StringBuilder();
                head.append(method).append(' ').append(upstreamPath).append(" HTTP/1.1\r\n");
                head.append("Host: ").append(target.host).append("\r\n");
                Headers inHeaders = exchange.getRequestHeaders();
                List<String> skip = List.of("host", "connection", "proxy-connection",
                        "transfer-encoding", "keep-alive", "origin", "referer");
                for (String name : inHeaders.keySet()) {
                    if (skip.contains(name.toLowerCase())) continue;
                    for (String value : inHeaders.get(name)) {
                        head.append(name).append(": ").append(value).append("\r\n");
                    }
                }
                String origin = inHeaders.getFirst("Origin");
                if (origin != null && !origin.isBlank()) {
                    head.append("Origin: ").append(rewriteLocalOriginToReal(origin, target.host)).append("\r\n");
                }
                String referer = inHeaders.getFirst("Referer");
                if (referer != null && !referer.isBlank()) {
                    head.append("Referer: ").append(rewriteLocalOriginToReal(referer, target.host)).append("\r\n");
                }
                head.append("Connection: close\r\n");

                java.io.ByteArrayOutputStream reqBuf = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[64 * 1024];
                int n;
                InputStream reqBody = exchange.getRequestBody();
                while ((n = reqBody.read(buf)) >= 0) reqBuf.write(buf, 0, n);
                byte[] requestBody = reqBuf.toByteArray();
                if (requestBody.length > 0) {
                    head.append("Content-Length: ").append(requestBody.length).append("\r\n");
                }
                head.append("\r\n");
                upstreamOut.write(head.toString().getBytes(StandardCharsets.ISO_8859_1));
                if (requestBody.length > 0) upstreamOut.write(requestBody);
                upstreamOut.flush();

                String statusLine = readLine(upstreamIn);
                if (statusLine == null || statusLine.isEmpty()) {
                    sendError(exchange, 502, "Empty upstream response");
                    return;
                }
                String[] parts = statusLine.split(" ", 3);
                int statusCode;
                try { statusCode = Integer.parseInt(parts[1]); }
                catch (Exception e) { statusCode = 502; }

                Headers respHeaders = exchange.getResponseHeaders();
                int contentLength = -1;
                boolean chunked = false;
                String contentType = "";
                List<String[]> headerLines = new ArrayList<>();
                String line;
                while ((line = readLine(upstreamIn)) != null && !line.isEmpty()) {
                    int colon = line.indexOf(':');
                    if (colon < 0) continue;
                    headerLines.add(new String[]{line.substring(0, colon).trim(),
                            line.substring(colon + 1).trim()});
                }
                for (String[] h : headerLines) {
                    String name = h[0];
                    String value = h[1];
                    String lower = name.toLowerCase();
                    if (lower.equals("transfer-encoding")) { chunked = true; continue; }
                    if (lower.equals("connection") || lower.equals("keep-alive")
                            || lower.equals("proxy-connection")) continue;
                    if (lower.equals("content-length")) {
                        try { contentLength = Integer.parseInt(value.trim()); }
                        catch (NumberFormatException ignored) {}
                    }
                    if (lower.equals("content-type")) contentType = value;
                    if (lower.equals("location")) {
                        value = mapRealUrlToLocal(value, port);
                    }
                    respHeaders.add(name, value);
                }

                boolean text = isTextContentType(contentType);
                InputStream bodyStream = upstreamIn;
                if (chunked) bodyStream = new ChunkedInputStream(upstreamIn);

                if (text) {
                    java.io.ByteArrayOutputStream bufOut = new java.io.ByteArrayOutputStream();
                    byte[] b = new byte[64 * 1024];
                    int total = 0;
                    int rn;
                    while ((rn = bodyStream.read(b)) >= 0) {
                        if (total + rn > MAX_TEXT_BYTES) break;
                        bufOut.write(b, 0, rn);
                        total += rn;
                    }
                    byte[] original = bufOut.toByteArray();
                    String charset = charsetFrom(contentType);
                    String textBody = new String(original, charset);
                    textBody = rewriteBody(textBody);
                    byte[] rewritten = textBody.getBytes(charset);
                    respHeaders.set("Content-Length", Integer.toString(rewritten.length));
                    exchange.sendResponseHeaders(statusCode, rewritten.length);
                    try (OutputStream out = exchange.getResponseBody()) {
                        out.write(rewritten);
                    }
                } else {
                    if (contentLength >= 0) {
                        respHeaders.set("Content-Length", Integer.toString(contentLength));
                        exchange.sendResponseHeaders(statusCode, contentLength);
                    } else {
                        respHeaders.remove("Content-Length");
                        exchange.sendResponseHeaders(statusCode, -1);
                    }
                    try (OutputStream out = exchange.getResponseBody()) {
                        byte[] b = new byte[64 * 1024];
                        int sn;
                        while ((sn = bodyStream.read(b)) >= 0) {
                            out.write(b, 0, sn);
                            out.flush();
                        }
                    }
                }
            } catch (Exception e) {
                try { sendError(exchange, 502, "Upstream error: " + e.getMessage()); } catch (Exception ignored) {}
            }
        } catch (Exception e) {
            try { sendError(exchange, 500, e.getMessage()); } catch (Exception ignored) {}
        } finally {
            try { exchange.close(); } catch (Exception ignored) {}
        }
    }

    private static final class Target {
        final String host;
        final String path;
        final boolean https;
        Target(String host, String path, boolean https) {
            this.host = host;
            this.path = path;
            this.https = https;
        }
    }

    private Target resolveTarget(String path) {
        if (path.startsWith("/cdn/")) {
            String rest = path.substring("/cdn/".length());
            int slash = rest.indexOf('/');
            String host;
            String subpath;
            if (slash < 0) { host = rest; subpath = "/"; }
            else { host = rest.substring(0, slash); subpath = rest.substring(slash); }
            if (host.isEmpty()) return null;
            try { host = URLDecoder.decode(host, StandardCharsets.UTF_8); } catch (Exception ignored) {}
            if (subpath.isEmpty()) subpath = "/";
            return new Target(host, subpath, true);
        }
        if (!originHost.isEmpty()) {
            String host = originHost;
            if (!host.startsWith("www.")) host = "www." + host;
            return new Target(host, path, true);
        }
        return null;
    }

    private static boolean isSink(InetAddress a) {
        byte[] b = a.getAddress();
        return b.length == 4 && (b[0] & 0xff) == 146 && (b[1] & 0xff) == 112;
    }

    private static InetAddress resolveHost(String host) throws UnknownHostException {
        InetAddress[] system = null;
        try { system = InetAddress.getAllByName(host); } catch (UnknownHostException ignored) {}
        boolean sink = false;
        if (system != null) {
            for (InetAddress a : system) if (isSink(a)) { sink = true; break; }
        }
        if (sink) {
            try {
                return SecureDns.resolveWithMode(SecureDns.Mode.CLOUDFLARE, host, 443).getAddress();
            } catch (Exception e) {
                throw new UnknownHostException(host);
            }
        }
        if (system != null && system.length > 0) return system[0];
        try {
            return SecureDns.resolveWithMode(SecureDns.Mode.CLOUDFLARE, host, 443).getAddress();
        } catch (Exception e) {
            throw new UnknownHostException(host);
        }
    }

    private static Socket openPlainSocket(InetAddress address, int port) {
        try {
            Socket s = new Socket();
            s.connect(new InetSocketAddress(address, port), CONNECT_TIMEOUT_MS);
            return s;
        } catch (IOException e) {
            return null;
        }
    }

    private static Socket openTlsSocket(String host, InetAddress address, int port) {
        try {
            SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
            SSLSocket socket = (SSLSocket) factory.createSocket();
            socket.connect(new InetSocketAddress(address, port), CONNECT_TIMEOUT_MS);
            SSLParameters params = socket.getSSLParameters();
            try { params.setServerNames(List.of(new javax.net.ssl.SNIHostName(host))); }
            catch (IllegalArgumentException ignored) {}
            socket.setSSLParameters(params);
            socket.startHandshake();
            return socket;
        } catch (Exception e) {
            return null;
        }
    }

    private static String mapRealUrlToLocal(String url, int port) {
        try {
            boolean https = url.startsWith("https://");
            boolean http = url.startsWith("http://");
            if (!https && !http) return url;
            String rest = url.substring(https ? 8 : 7);
            int slashInRest = rest.indexOf('/');
            String host = slashInRest < 0 ? rest : rest.substring(0, slashInRest);
            String tail = slashInRest < 0 ? "/" : rest.substring(slashInRest);
            int q = tail.indexOf('?');
            String query = "";
            if (q >= 0) { query = tail.substring(q); tail = tail.substring(0, q); }
            int f = query.indexOf('#');
            String frag = "";
            if (f >= 0) { frag = query.substring(f); query = query.substring(0, f); }
            if (host.isEmpty()) return url;
            if (host.startsWith("www.")) {
                return "http://127.0.0.1:" + port + tail + query + frag;
            }
            return "http://127.0.0.1:" + port + "/cdn/" + host + tail + query + frag;
        } catch (Exception e) {
            return url;
        }
    }

    private static String rewriteLocalOriginToReal(String value, String host) {
        return value.replaceAll("http://127\\.0\\.0\\.1:\\d+", "https://" + host);
    }

    private static boolean isTextContentType(String ct) {
        String lower = ct.toLowerCase();
        return lower.contains("text/html") || lower.contains("text/css")
                || lower.contains("javascript") || lower.contains("application/json")
                || lower.contains("text/plain") || lower.contains("application/xml");
    }

    private static String charsetFrom(String ct) {
        String lower = ct.toLowerCase();
        int idx = lower.indexOf("charset=");
        if (idx >= 0) {
            String cs = ct.substring(idx + 8).trim();
            int semi = cs.indexOf(';');
            if (semi >= 0) cs = cs.substring(0, semi).trim();
            cs = cs.replace("\"", "");
            if (!cs.isEmpty()) return cs;
        }
        return "UTF-8";
    }

    private static String rewriteBody(String body) {
        body = body.replaceAll("https://www\\.[A-Za-z0-9.:-]+", "");
        Pattern p = Pattern.compile("https://([A-Za-z0-9-]+\\.[A-Za-z0-9.-]+)(/[^\"'\\s<>)]*)");
        Matcher m = p.matcher(body);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String h = m.group(1);
            String path = m.group(2);
            if (h.startsWith("www.")) {
                m.appendReplacement(sb, Matcher.quoteReplacement(path));
            } else {
                m.appendReplacement(sb, Matcher.quoteReplacement("/cdn/" + h + path));
            }
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private void sendError(HttpExchange exchange, int code, String message) {
        try {
            byte[] body = message.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
            exchange.sendResponseHeaders(code, body.length);
            try (OutputStream out = exchange.getResponseBody()) { out.write(body); }
        } catch (Exception ignored) {}
    }

    private static String readLine(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        while (true) {
            int c = in.read();
            if (c < 0) {
                if (bos.size() == 0) return null;
                break;
            }
            if (c == '\n') break;
            if (c != '\r') bos.write(c);
        }
        return bos.toString(StandardCharsets.ISO_8859_1);
    }

    private static final class ChunkedInputStream extends InputStream {
        private final InputStream in;
        private int remaining = 0;
        private boolean eof = false;

        ChunkedInputStream(InputStream in) { this.in = in; }

        @Override
        public int read() throws IOException {
            byte[] b = new byte[1];
            int r = read(b, 0, 1);
            return r < 0 ? -1 : (b[0] & 0xff);
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (eof) return -1;
            if (remaining == 0) {
                String sizeLine = readLine(in);
                if (sizeLine == null) { eof = true; return -1; }
                int semi = sizeLine.indexOf(';');
                String hex = (semi >= 0 ? sizeLine.substring(0, semi) : sizeLine).trim();
                try { remaining = Integer.parseInt(hex, 16); }
                catch (NumberFormatException e) { eof = true; return -1; }
                if (remaining == 0) {
                    String t;
                    while ((t = readLine(in)) != null && !t.isEmpty()) {}
                    eof = true;
                    return -1;
                }
            }
            int toRead = Math.min(len, remaining);
            int r = in.read(b, off, toRead);
            if (r < 0) { eof = true; return -1; }
            remaining -= r;
            if (remaining == 0) {
                in.read();
                in.read();
            }
            return r;
        }
    }

    public static String documentStartScript(int port) {
        String origin = "http://127.0.0.1:" + port;
        return "(function(){\n"
            + "if (window.__rigelLocalOriginPatched) return;\n"
            + "window.__rigelLocalOriginPatched = true;\n"
            + "var ORIGIN = \"" + origin + "\";\n"
            + "function isLocal(u){try{return typeof u==='string' && (u.indexOf(ORIGIN)===0 || u.indexOf('/')===0);}catch(e){return false;}}\n"
            + "function toLocal(u){\n"
            + "  if (u == null) return u;\n"
            + "  if (typeof u !== 'string') { try { u = u.url || u.href || String(u); } catch(e){ return u; } }\n"
            + "  if (isLocal(u)) return u;\n"
            + "  if (/^https:\\/\\/www\\./.test(u)) return u.replace(/^https:\\/\\/www\\.[^\\/]+/, '');\n"
            + "  var m = u.match(/^https?:\\/\\/([^\\/]+)(\\/.*)?$/);\n"
            + "  if (m) { var host=m[1], path=m[2]||'/'; if (host !== '127.0.0.1') return '/cdn/' + host + path; }\n"
            + "  if (/^wss?:\\/\\//.test(u)) {\n"
            + "    var w = u.match(/^wss?:\\/\\/([^\\/]+)(\\/.*)?$/);\n"
            + "    if (w) { var wh=w[1], wp=w[2]||'/'; var proto = 'ws';\n"
            + "      if (wh !== '127.0.0.1') return proto + '://127.0.0.1:' + " + port + " + '/cdn/' + wh + wp; }\n"
            + "  }\n"
            + "  return u;\n"
            + "}\n"
            + "function patchFetch(){\n"
            + "  if (typeof window.fetch !== 'function' || window.fetch.__rigelPatched) return;\n"
            + "  var orig = window.fetch.bind(window);\n"
            + "  function wrapped(input, init){\n"
            + "    try {\n"
            + "      if (typeof input === 'string') input = toLocal(input);\n"
            + "      else if (input && typeof input === 'object' && typeof input.url === 'string') {\n"
            + "        var nu = toLocal(input.url); if (nu !== input.url) { try { input = new Request(nu, input); } catch(e){} }\n"
            + "      }\n"
            + "    } catch(e){}\n"
            + "    return orig(input, init);\n"
            + "  }\n"
            + "  wrapped.__rigelPatched = true;\n"
            + "  window.fetch = wrapped;\n"
            + "}\n"
            + "function patchXHR(){\n"
            + "  if (!window.XMLHttpRequest || !XMLHttpRequest.prototype || !XMLHttpRequest.prototype.open) return;\n"
            + "  if (XMLHttpRequest.prototype.open.__rigelPatched) return;\n"
            + "  var orig = XMLHttpRequest.prototype.open;\n"
            + "  function wrapped(method, url){\n"
            + "    try { arguments[1] = toLocal(url); } catch(e){}\n"
            + "    return orig.apply(this, arguments);\n"
            + "  }\n"
            + "  wrapped.__rigelPatched = true;\n"
            + "  XMLHttpRequest.prototype.open = wrapped;\n"
            + "}\n"
            + "function patchWS(){\n"
            + "  if (typeof window.WebSocket === 'undefined') return;\n"
            + "  if (window.WebSocket.__rigelPatched) return;\n"
            + "  var Orig = window.WebSocket;\n"
            + "  function Wrapped(url, protocols){\n"
            + "    try { url = toLocal(url); } catch(e){}\n"
            + "    return protocols === undefined ? new Orig(url) : new Orig(url, protocols);\n"
            + "  }\n"
            + "  Wrapped.__rigelPatched = true;\n"
            + "  window.WebSocket = Wrapped;\n"
            + "}\n"
            + "function patchES(){\n"
            + "  if (typeof window.EventSource === 'undefined') return;\n"
            + "  if (window.EventSource.__rigelPatched) return;\n"
            + "  var Orig = window.EventSource;\n"
            + "  function Wrapped(url, cfg){\n"
            + "    try { url = toLocal(url); } catch(e){}\n"
            + "    return cfg === undefined ? new Orig(url) : new Orig(url, cfg);\n"
            + "  }\n"
            + "  Wrapped.__rigelPatched = true;\n"
            + "  window.EventSource = Wrapped;\n"
            + "}\n"
            + "function patchBeacon(){\n"
            + "  if (!navigator || typeof navigator.sendBeacon !== 'function' || navigator.sendBeacon.__rigelPatched) return;\n"
            + "  var orig = navigator.sendBeacon.bind(navigator);\n"
            + "  function wrapped(url, data){\n"
            + "    try { url = toLocal(url); } catch(e){}\n"
            + "    return orig(url, data);\n"
            + "  }\n"
            + "  wrapped.__rigelPatched = true;\n"
            + "  navigator.sendBeacon = wrapped;\n"
            + "}\n"
            + "function patchSW(){\n"
            + "  try {\n"
            + "    if (navigator.serviceWorker && typeof navigator.serviceWorker.register === 'function' && !navigator.serviceWorker.register.__rigelPatched) {\n"
            + "      var resolved = Promise.resolve({scope: location.href, update: function(){}, unregister: function(){return Promise.resolve(true);}});\n"
            + "      function noop(){ return resolved; }\n"
            + "      noop.__rigelPatched = true;\n"
            + "      navigator.serviceWorker.register = noop;\n"
            + "    }\n"
            + "  } catch(e){}\n"
            + "}\n"
            + "function run(){ patchFetch(); patchXHR(); patchWS(); patchES(); patchBeacon(); patchSW(); }\n"
            + "run();\n"
            + "try { if (document && document.addEventListener) document.addEventListener('DOMContentLoaded', run); } catch(e){}\n"
            + "})();\n";
    }
}
