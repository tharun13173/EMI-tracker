package branch;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Branch. - Micro-Finance Loan & EMI Tracker. A self-hosted web application. */
public class Main {
    private static final Map<String, String> TYPES = Map.of(
            "html", "text/html; charset=utf-8", "css", "text/css; charset=utf-8", "js", "text/javascript; charset=utf-8",
            "svg", "image/svg+xml", "png", "image/png", "ico", "image/x-icon", "json", "application/json");

    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));
        String dbPath = System.getenv().getOrDefault("BRANCH_DB", "data/branch.db");
        Path web = Path.of(System.getenv().getOrDefault("BRANCH_WEB", "web")).toAbsolutePath().normalize();

        Db.init(dbPath);
        scheduleBackups(dbPath);

        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/", ex -> {
            try {
                secure(ex);
                String p = ex.getRequestURI().getPath();
                if (p.startsWith("/api/")) Api.handle(ex); else serveStatic(ex, web, p);
            } catch (Throwable t) {
                t.printStackTrace();
                try { ex.sendResponseHeaders(500, -1); } catch (IOException ignored) {}
            } finally { ex.close(); }
        });
        server.setExecutor(Executors.newFixedThreadPool(16));
        server.start();
        System.out.println("\n  Branch. is running  ->  http://localhost:" + port + "\n  Data: " + new File(dbPath).getAbsolutePath() + "\n");
    }

    private static void secure(HttpExchange ex) {
        var h = ex.getResponseHeaders();
        h.set("X-Content-Type-Options", "nosniff");
        h.set("X-Frame-Options", "DENY");
        h.set("Referrer-Policy", "same-origin");
        h.set("Content-Security-Policy", "default-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; frame-ancestors 'none'");
    }

    private static void serveStatic(HttpExchange ex, Path web, String p) throws IOException {
        if (!ex.getRequestMethod().equals("GET") && !ex.getRequestMethod().equals("HEAD")) { ex.sendResponseHeaders(405, -1); return; }
        if (p.equals("/")) p = "/index.html";
        Path f = web.resolve(p.substring(1)).normalize();
        if (!f.startsWith(web) || !Files.isRegularFile(f)) { ex.sendResponseHeaders(404, -1); return; }
        String name = f.getFileName().toString();
        byte[] data = Files.readAllBytes(f);
        ex.getResponseHeaders().set("Content-Type", TYPES.getOrDefault(name.substring(name.lastIndexOf('.') + 1), "application/octet-stream"));
        ex.getResponseHeaders().set("Cache-Control", "no-cache");
        ex.sendResponseHeaders(200, data.length);
        ex.getResponseBody().write(data);
    }

    /** Nightly safety copy of the database; keeps the latest 14. */
    private static void scheduleBackups(String dbPath) {
        File dir = new File(new File(dbPath).getAbsoluteFile().getParentFile(), "backups");
        ScheduledExecutorService s = Executors.newSingleThreadScheduledExecutor(r -> { Thread t = new Thread(r); t.setDaemon(true); return t; });
        s.scheduleAtFixedRate(() -> {
            try {
                dir.mkdirs();
                File f = new File(dir, "branch-" + LocalDate.now() + ".db");
                if (!f.exists()) Db.backupTo(f.getPath());
                File[] all = dir.listFiles((d, n) -> n.endsWith(".db"));
                if (all != null && all.length > 14) {
                    Arrays.sort(all);
                    for (int i = 0; i < all.length - 14; i++) all[i].delete();
                }
            } catch (Exception e) { System.err.println("Backup failed: " + e.getMessage()); }
        }, 5, 6 * 3600, TimeUnit.SECONDS);
    }
}
