package it.regmac.desktop;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;

import java.awt.Desktop;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Map;

/**
 * Avvio dell'applicazione desktop: una sola istanza, server su 127.0.0.1 con porta libera e token casuale,
 * apertura del browser e piccola finestra di controllo.
 */
@SpringBootApplication
public class DesktopApp {
  static String token;
  static int port;

  public static void main(String[] args) throws Exception {
    Path data = Paths.dataDir();
    Files.createDirectories(data);
    Path lockFile = data.resolve("instance.lock"), info = data.resolve("instance.json");
    FileChannel ch = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
    FileLock lock = ch.tryLock();
    if (lock == null) { // già in esecuzione: apre il browser sull'istanza esistente ed esce
      try {
        Map<?, ?> m = new ObjectMapper().readValue(info.toFile(), Map.class);
        if (alive((Integer) m.get("port"))) { browse("http://127.0.0.1:" + m.get("port") + "/?t=" + m.get("token")); System.exit(0); }
      } catch (Exception ignored) { }
      System.err.println("RegMac è già in esecuzione."); System.exit(1);
    }
    token = HexFormat.of().formatHex(new SecureRandom().generateSeed(24));
    try (ServerSocket s = new ServerSocket(0, 0, java.net.InetAddress.getLoopbackAddress())) { port = s.getLocalPort(); }
    new ObjectMapper().writeValue(info.toFile(), Map.of("port", port, "token", token, "pid", ProcessHandle.current().pid()));

    SpringApplication app = new SpringApplication(DesktopApp.class);
    app.setDefaultProperties(Map.of(
        "server.address", "127.0.0.1", "server.port", String.valueOf(port),
        "spring.servlet.multipart.max-file-size", "200MB", "spring.servlet.multipart.max-request-size", "400MB",
        "spring.main.banner-mode", "off", "logging.file.name", data.resolve("logs/regmac.log").toString(),
        "server.error.include-message", "always"));
    ConfigurableApplicationContext ctx = app.run(args);
    String url = "http://127.0.0.1:" + port + "/?t=" + token;
    Runtime.getRuntime().addShutdownHook(new Thread(() -> { try { Files.deleteIfExists(info); lock.release(); } catch (IOException ignored) { } }));
    if (!Boolean.getBoolean("regmac.nobrowser")) browse(url);
    ControlWindow.show(url, () -> { ctx.close(); System.exit(0); });
  }

  static boolean alive(int p) {
    try {
      HttpURLConnection c = (HttpURLConnection) URI.create("http://127.0.0.1:" + p + "/api/ping").toURL().openConnection();
      c.setConnectTimeout(800); c.setReadTimeout(800);
      return c.getResponseCode() == 200;
    } catch (Exception e) { return false; }
  }

  static void browse(String url) {
    try {
      if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) { Desktop.getDesktop().browse(URI.create(url)); return; }
    } catch (Exception ignored) { }
    try {
      String os = System.getProperty("os.name").toLowerCase();
      new ProcessBuilder(os.contains("mac") ? new String[]{"open", url} : os.contains("win") ? new String[]{"rundll32", "url.dll,FileProtocolHandler", url} : new String[]{"xdg-open", url}).start();
    } catch (IOException e) { System.err.println("Apri nel browser: " + url); }
  }
}
