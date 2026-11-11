package it.regmac.desktop;

import java.nio.file.Path;

/** Cartella dati dell'utente, secondo le convenzioni di ciascun sistema operativo. */
public final class Paths {
  private Paths() { }

  public static Path dataDir() {
    String custom = System.getProperty("regmac.data", System.getenv("REGMAC_DATA"));
    if (custom != null && !custom.isBlank()) return Path.of(custom);
    String os = System.getProperty("os.name").toLowerCase(), home = System.getProperty("user.home");
    if (os.contains("mac")) return Path.of(home, "Library", "Application Support", "RegMac");
    if (os.contains("win")) {
      String appData = System.getenv("APPDATA");
      return Path.of(appData != null ? appData : home, "RegMac");
    }
    String xdg = System.getenv("XDG_DATA_HOME");
    return xdg != null && !xdg.isBlank() ? Path.of(xdg, "regmac") : Path.of(home, ".local", "share", "regmac");
  }
}
