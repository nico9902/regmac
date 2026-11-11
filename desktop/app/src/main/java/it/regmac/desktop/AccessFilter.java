package it.regmac.desktop;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;

/**
 * Solo il browser aperto dall'app può usare le API: il token casuale passato all'avvio diventa un cookie di sessione.
 * Il controllo dell'header Host impedisce gli attacchi di DNS rebinding da pagine web esterne.
 */
@Component @Order(1)
public class AccessFilter extends OncePerRequestFilter {
  private static final String COOKIE = "regmac";

  @Override
  protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws ServletException, IOException {
    String host = req.getHeader("Host");
    if (host == null || !(host.equals("127.0.0.1:" + DesktopApp.port) || host.equals("localhost:" + DesktopApp.port))) { res.sendError(403); return; }
    String path = req.getRequestURI();
    String t = req.getParameter("t");
    if (t != null && same(t, DesktopApp.token)) {
      Cookie c = new Cookie(COOKIE, DesktopApp.token);
      c.setHttpOnly(true); c.setPath("/"); c.setAttribute("SameSite", "Strict");
      res.addCookie(c);
      if ("GET".equals(req.getMethod()) && !path.startsWith("/api/")) { res.sendRedirect("/"); return; }
    }
    if (path.equals("/api/ping")) { res.setStatus(200); return; }
    boolean ok = false;
    if (req.getCookies() != null) for (Cookie c : req.getCookies()) if (COOKIE.equals(c.getName()) && same(c.getValue(), DesktopApp.token)) ok = true;
    if (!ok && path.startsWith("/api/")) { res.sendError(401, "Apri RegMac dalla sua icona"); return; }
    if (!ok && !path.startsWith("/assets/") && !path.endsWith(".png") && !path.endsWith(".ico")) {
      res.setContentType("text/html;charset=UTF-8");
      res.getWriter().write("<!doctype html><meta charset=utf-8><title>RegMac</title><body style='font-family:sans-serif;padding:40px;color:#1E293B'><h2>Sessione non valida</h2><p>Riapri RegMac dalla sua icona oppure dalla finestra «RegMac è in esecuzione».</p></body>");
      return;
    }
    res.setHeader("X-Content-Type-Options", "nosniff");
    res.setHeader("X-Frame-Options", "DENY");
    res.setHeader("Referrer-Policy", "no-referrer");
    chain.doFilter(req, res);
  }

  private static boolean same(String a, String b) {
    return a != null && b != null && MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
  }
}
