package wang.robby.console.android;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.json.JSONException;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

/** Loopback-only protocol checks: synthetic bytes and credentials; no provider or real recording is used. */
public final class MindBallReplyTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private String transcribeReply(String response) throws Exception {
        File audio = temp.newFile(); Files.write(audio.toPath(), new byte[] {1, 2, 3});
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            server.setSoTimeout(5000);
            Future<?> reply = worker.submit(() -> {
                try (Socket socket = server.accept()) {
                    socket.setSoTimeout(5000);
                    InputStream in = socket.getInputStream(); ByteArrayOutputStream headers = new ByteArrayOutputStream();
                    int next; String header = "";
                    while ((next = in.read()) >= 0) {
                        headers.write(next); header = headers.toString("US-ASCII");
                        if (header.endsWith("\r\n\r\n")) break;
                        if (headers.size() > 16384) throw new AssertionError("oversized request headers");
                    }
                    int length = -1;
                    for (String line : header.split("\r\n")) if (line.toLowerCase(java.util.Locale.ROOT).startsWith("content-length:")) length = Integer.parseInt(line.substring(line.indexOf(':') + 1).trim());
                    if (length < 0) throw new AssertionError("fixed content length required");
                    for (int i = 0; i < length; i++) if (in.read() < 0) throw new AssertionError("incomplete request body");
                    byte[] body = response.getBytes(StandardCharsets.UTF_8);
                    OutputStream out = socket.getOutputStream();
                    out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: " + body.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                    out.write(body); out.flush();
                } catch (Exception e) { throw new AssertionError(e); }
            });
            try { return MindBall.transcribe("http://127.0.0.1:" + server.getLocalPort(), "synthetic-model", "synthetic-key", audio); }
            finally { reply.get(10, TimeUnit.SECONDS); }
        } finally { worker.shutdownNow(); }
    }
    @Test(expected = JSONException.class) public void missingTextCannotBeMistakenForSilence() throws Exception {
        transcribeReply("{\"error\":\"synthetic incompatible response\"}");
    }
    @Test(expected = JSONException.class) public void nonStringTextCannotBecomeARecordedTranscript() throws Exception {
        transcribeReply("{\"text\":17}");
    }
    @Test public void explicitEmptyTextAndRealTextRemainValid() throws Exception {
        assertEquals("", transcribeReply("{\"text\":\"\"}"));
        assertEquals("synthetic transcript", transcribeReply("{\"text\":\"synthetic transcript\"}"));
    }
    @Test(expected = JSONException.class) public void missingTimelineItemsMustNotEraseExistingTasks() throws Exception {
        MindBall.parseTimeline("{}");
    }
    @Test(expected = JSONException.class) public void nonArrayTimelineItemsAreNotAnEmptyTimeline() throws Exception {
        MindBall.parseTimeline("{\"items\":{}}");
    }
}
