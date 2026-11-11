package it.regmac.desktop;

import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.parser.PdfTextExtractor;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipInputStream;

/** Estrae il testo dei documenti in brani brevi (una frase o una riga), con pagina e titolo di sezione. */
public final class TextExtractor {
  private TextExtractor() { }

  public record Passage(String text, int page, String section) { }
  public record Result(List<Passage> passages, int pages, String warning) { }

  public static Result extract(Path file) throws Exception {
    String name = file.getFileName().toString().toLowerCase();
    if (name.endsWith(".pdf")) return pdf(file);
    if (name.endsWith(".docx")) return lines(docx(Files.readAllBytes(file)), 1);
    if (name.endsWith(".txt") || name.endsWith(".md") || name.endsWith(".csv")) return lines(Files.readString(file, StandardCharsets.UTF_8), 1);
    throw new IllegalArgumentException("Formato non supportato: usa PDF, DOCX, TXT o MD");
  }

  static Result pdf(Path file) throws Exception {
    List<Passage> out = new ArrayList<>();
    int pages, empty = 0;
    try (PdfReader r = new PdfReader(Files.readAllBytes(file))) {
      pages = r.getNumberOfPages();
      PdfTextExtractor ex = new PdfTextExtractor(r);
      for (int p = 1; p <= pages; p++) {
        String t = ex.getTextFromPage(p);
        if (t == null || t.isBlank()) { empty++; continue; }
        out.addAll(split(t, p, ""));
      }
    }
    String warn = empty == pages ? "PDF senza testo (scansione): non leggibile in questa versione" : empty > 0 ? empty + " pagine senza testo (scansioni) non lette" : null;
    return new Result(out, pages, warn);
  }

  static String docx(byte[] bytes) throws Exception {
    try (ZipInputStream z = new ZipInputStream(new ByteArrayInputStream(bytes))) {
      for (var e = z.getNextEntry(); e != null; e = z.getNextEntry()) {
        if (!e.getName().equals("word/document.xml")) continue;
        String xml = new String(z.readAllBytes(), StandardCharsets.UTF_8);
        StringBuilder sb = new StringBuilder();
        for (String para : xml.split("</w:p>")) {
          Matcher m = Pattern.compile("<w:t[^>]*>([^<]*)</w:t>").matcher(para);
          StringBuilder line = new StringBuilder();
          while (m.find()) line.append(m.group(1));
          if (!line.isEmpty()) sb.append(unescape(line.toString())).append('\n');
        }
        return sb.toString();
      }
    }
    throw new IllegalArgumentException("DOCX non valido");
  }

  static String unescape(String s) { return s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&"); }

  static Result lines(String text, int page) {
    List<Passage> out = new ArrayList<>();
    String section = "";
    StringBuilder block = new StringBuilder();
    for (String line : text.split("\n")) {
      String l = line.strip();
      if (l.startsWith("#")) { flush(block, out, page, section); section = l.replaceFirst("^#+\\s*", ""); continue; }
      if (l.isEmpty()) { flush(block, out, page, section); continue; }
      block.append(l).append('\n');
    }
    flush(block, out, page, section);
    return new Result(out, page, null);
  }

  private static void flush(StringBuilder block, List<Passage> out, int page, String section) {
    if (!block.isEmpty()) { out.addAll(split(block.toString(), page, section)); block.setLength(0); }
  }

  /** Brani: righe e frasi; le frasi brevissime si uniscono alla successiva, quelle lunghe si spezzano. */
  static List<Passage> split(String text, int page, String section) {
    List<Passage> out = new ArrayList<>();
    String carry = "";
    for (String line : text.split("\n")) {
      for (String s : line.strip().split("(?<=[.;!?])\\s+(?=[A-ZÀ-Ú0-9«\"(])")) {
        String t = (carry + " " + s).strip().replaceAll("\\s+", " ");
        if (t.isEmpty()) continue;
        if (t.length() < 30) { carry = t; continue; }
        carry = "";
        while (t.length() > 450) { int cut = t.lastIndexOf(' ', 420); if (cut < 200) cut = 420; out.add(new Passage(t.substring(0, cut), page, section)); t = t.substring(cut).strip(); }
        out.add(new Passage(t, page, section));
      }
    }
    if (!carry.isBlank()) out.add(new Passage(carry, page, section));
    return out;
  }
}
