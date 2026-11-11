package it.regmac.desktop;

import java.io.IOException;
import java.nio.file.Path;

/** Apre file e cartelle con le applicazioni del sistema (funziona anche senza interfaccia AWT). */
final class OsOpen {
  private OsOpen() { }
  private static final String OS = System.getProperty("os.name").toLowerCase();

  static void open(String target) throws IOException {
    if (OS.contains("mac")) new ProcessBuilder("open", target).start();
    else if (OS.contains("win")) new ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", target).start();
    else new ProcessBuilder("xdg-open", target).start();
  }

  /** Mostra il file selezionato nel Finder / Esplora risorse; su Linux apre la cartella. */
  static void reveal(Path file) throws IOException {
    if (OS.contains("mac")) new ProcessBuilder("open", "-R", file.toString()).start();
    else if (OS.contains("win")) new ProcessBuilder("explorer", "/select,", file.toString()).start();
    else new ProcessBuilder("xdg-open", file.getParent().toString()).start();
  }

  static Path downloads() {
    Path d = Path.of(System.getProperty("user.home"), "Downloads");
    return d.toFile().isDirectory() ? d : Path.of(System.getProperty("user.home"));
  }
}
