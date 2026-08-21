import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import proxy.Blocklist;
import proxy.ProxyServer;
import ui.RigelBrowser;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Verifies that a real WKWebView loads through Rigel's SOCKS5 browser ingress. */
public final class RigelWKProxyIntegrationTest {
    private static final InetAddress LOOPBACK = loopback();
    private static final String TEST_HOST = "example.com";
    private static final String EXPECTED_TITLE = "Example Domain";

    public static void main(String[] args) throws Exception {
        System.setProperty("rigel.browser.backend", "wk");
        int preferredPort = availableTcpPort();
        try (MockConnectProxy upstream = new MockConnectProxy()) {
            ProxyServer proxy = new ProxyServer(new Blocklist(false), preferredPort);
            Display display = null;
            Shell shell = null;
            try {
                display = new Display();
                proxy.start();
                proxy.setUpstream(ProxyServer.UpstreamType.HTTP,
                        "127.0.0.1", upstream.port());
                RigelBrowser.configureProxy(
                        proxy.getBrowserProxyPort(), 2);

                shell = new Shell(display);
                shell.setLayout(new FillLayout());
                shell.setSize(640, 420);
                RigelBrowser browser = new RigelBrowser(shell, SWT.NONE);
                require("wkwebview".equals(browser.getBackendName()),
                        "WKWebView was not selected");
                AtomicBoolean titleSeen = new AtomicBoolean();
                AtomicBoolean webRtcFinished = new AtomicBoolean();
                AtomicBoolean waitingForTikTok = new AtomicBoolean();
                AtomicBoolean tikTokCompleted = new AtomicBoolean();
                AtomicReference<String> tikTokResult = new AtomicReference<>("");
                browser.addTitleListener(title -> {
                    if (EXPECTED_TITLE.equals(title)) titleSeen.set(true);
                    if (title.startsWith("RIGEL_WEBRTC_")) webRtcFinished.set(true);
                    if (title.startsWith("RIGEL_TIKTOK_")) tikTokResult.set(title);
                });
                browser.addProgressListener(new RigelBrowser.ProgressListener() {
                    @Override public void completed() {
                        if (waitingForTikTok.get()) tikTokCompleted.set(true);
                    }
                });
                shell.open();
                require(browser.setUrl("https://" + TEST_HOST + "/"),
                        "test URL was rejected");

                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                while (!titleSeen.get() && System.nanoTime() < deadline) {
                    while (display.readAndDispatch()) { }
                    Thread.sleep(10);
                }
                if (!titleSeen.get()) {
                    System.err.println("WK test state: url=" + browser.getUrl()
                            + " title=" + browser.getTitle());
                }
                upstream.verify();
                require(titleSeen.get(),
                        "WKWebView did not render the response through SOCKS5");
                proxy.clearUpstream();
                long udpBefore = proxy.getBrowserUdpAssociationRequestCount();
                require(browser.execute("(function(){try{"
                                + "var pc=new RTCPeerConnection({iceServers:[{urls:'stun:stun.l.google.com:19302'}]});"
                                + "window.__rigelWebRtcProxyProbe=pc;pc.createDataChannel('probe');"
                                + "var done=false,seen=[];function finish(v){if(done)return;done=true;document.title='RIGEL_WEBRTC_'+v+'_'+seen.join('-');}"
                                + "pc.onicecandidate=function(e){if(e.candidate){seen.push((e.candidate.protocol||'unknown')+':'"
                                + "+(e.candidate.type||'unknown'));}else finish('DONE');};"
                                + "pc.createOffer().then(function(o){return pc.setLocalDescription(o);})"
                                + ".catch(function(){finish('ERROR');});setTimeout(function(){finish('TIMEOUT');},8000);"
                                + "}catch(e){document.title='RIGEL_WEBRTC_ERROR';}})();"),
                        "WebRTC UDP probe script was rejected");
                long rtcDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                while (!webRtcFinished.get() && System.nanoTime() < rtcDeadline) {
                    while (display.readAndDispatch()) { }
                    Thread.sleep(10);
                }
                long udpAfter = proxy.getBrowserUdpAssociationRequestCount();
                System.out.println("Rigel WK proxy integration passed: backend=wkwebview"
                        + " browserProxy=socks5 tcpPath=LittleProxy upstream=http");
                System.out.println("Rigel WK WebRTC probe: finished=" + webRtcFinished.get()
                        + " result=" + browser.getTitle()
                        + " socks5UdpAssociations=" + (udpAfter - udpBefore));

                long tikTokConnectsBefore = proxy.getBrowserConnectRequestCount();
                waitingForTikTok.set(true);
                require(browser.setUrl("https://www.tiktok.com/"),
                        "TikTok URL was rejected");
                long tikTokLoadDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
                while (!tikTokCompleted.get() && System.nanoTime() < tikTokLoadDeadline) {
                    while (display.readAndDispatch()) { }
                    Thread.sleep(10);
                }
                require(browser.execute("(function(){var end=Date.now()+10000;function check(){"
                                + "var c=window.__rigelTikTokCompatibility,v=!!document.querySelector('video');"
                                + "if(v||Date.now()>=end){document.title='RIGEL_TIKTOK_'"
                                + "+!!(c&&c.version===3)+'_video_'+v+'_'+document.readyState;}"
                                + "else setTimeout(check,100);}check();})();"),
                        "TikTok compatibility probe script was rejected");
                long tikTokProbeDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(12);
                while (tikTokResult.get().isEmpty()
                        && System.nanoTime() < tikTokProbeDeadline) {
                    while (display.readAndDispatch()) { }
                    Thread.sleep(10);
                }
                require(tikTokResult.get().startsWith("RIGEL_TIKTOK_true_"),
                        "TikTok compatibility script was not installed in the live page");
                require(tikTokResult.get().contains("_video_true_"),
                        "TikTok did not create an HTML video element");
                require(proxy.getBrowserConnectRequestCount() > tikTokConnectsBefore,
                        "TikTok did not use the SOCKS5 browser route");
                System.out.println("Rigel TikTok WK smoke passed: " + tikTokResult.get());
            } finally {
                if (shell != null && !shell.isDisposed()) shell.dispose();
                if (display != null && !display.isDisposed()) display.dispose();
                proxy.stop();
            }
        }
    }

    private static int availableTcpPort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0, 1, LOOPBACK)) {
            return socket.getLocalPort();
        }
    }

    private static String readHeaders(InputStream input) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] ending = {'\r', '\n', '\r', '\n'};
        int matched = 0;
        while (matched < ending.length) {
            int value = input.read();
            if (value < 0) throw new EOFException();
            bytes.write(value);
            matched = value == ending[matched] ? matched + 1
                    : (value == '\r' ? 1 : 0);
            if (bytes.size() > 64 * 1024) throw new IllegalStateException("headers too large");
        }
        return bytes.toString(StandardCharsets.ISO_8859_1);
    }

    private static InetAddress loopback() {
        try {
            return InetAddress.getByAddress(new byte[] {127, 0, 0, 1});
        } catch (Exception impossible) {
            throw new ExceptionInInitializerError(impossible);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static final class MockConnectProxy implements AutoCloseable {
        private final ServerSocket server;
        private final Set<Socket> clients = ConcurrentHashMap.newKeySet();
        private final CountDownLatch tunnelSeen = new CountDownLatch(1);
        private final Thread listener;
        private volatile boolean running = true;
        private volatile Throwable failure;

        MockConnectProxy() throws Exception {
            server = new ServerSocket(0, 16, LOOPBACK);
            listener = Thread.ofPlatform().daemon(true).name("mock-wk-http-upstream")
                    .start(this::acceptLoop);
        }

        int port() { return server.getLocalPort(); }

        private void acceptLoop() {
            while (running) {
                try {
                    Socket client = server.accept();
                    clients.add(client);
                    Thread.ofVirtual().name("mock-wk-http-client")
                            .start(() -> serve(client));
                } catch (Exception error) {
                    if (running) failure = error;
                }
            }
        }

        private void serve(Socket client) {
            try (client; Socket target = new Socket()) {
                client.setSoTimeout(10_000);
                String connect = readHeaders(client.getInputStream());
                require(connect.startsWith("CONNECT " + TEST_HOST + ":443 "),
                        "unexpected upstream CONNECT: " + connect.lines().findFirst().orElse(""));
                target.connect(new InetSocketAddress(TEST_HOST, 443), 10_000);
                target.setSoTimeout(0);
                client.getOutputStream().write(
                        "HTTP/1.1 200 Connection Established\r\n\r\n"
                                .getBytes(StandardCharsets.ISO_8859_1));
                client.getOutputStream().flush();
                tunnelSeen.countDown();

                Thread back = Thread.ofVirtual().name("mock-wk-tunnel-back")
                        .start(() -> copy(target, client));
                copy(client, target);
                try {
                    target.shutdownOutput();
                } catch (Exception ignored) {
                }
                back.join();
            } catch (Throwable error) {
                failure = error;
                tunnelSeen.countDown();
            } finally {
                clients.remove(client);
            }
        }

        void verify() throws Exception {
            require(tunnelSeen.await(5, TimeUnit.SECONDS),
                    "WKWebView did not reach the HTTP upstream");
            if (failure != null) throw new AssertionError("WK proxy integration failed", failure);
        }

        private static void copy(Socket source, Socket destination) {
            try {
                InputStream input = source.getInputStream();
                byte[] buffer = new byte[64 * 1024];
                for (int count; (count = input.read(buffer)) >= 0; ) {
                    destination.getOutputStream().write(buffer, 0, count);
                    destination.getOutputStream().flush();
                }
            } catch (Exception ignored) {
            }
        }

        @Override public void close() {
            running = false;
            try {
                server.close();
            } catch (Exception ignored) {
            }
            for (Socket client : clients) {
                try {
                    client.close();
                } catch (Exception ignored) {
                }
            }
            try {
                listener.join(2_000);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
