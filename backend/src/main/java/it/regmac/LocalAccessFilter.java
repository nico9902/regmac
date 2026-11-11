package it.regmac;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Optional;
import java.util.Set;

import static it.regmac.UserService.Role;
import static it.regmac.UserService.User;

/**
 * Autenticazione e autorizzazione delle API.
 * <ul>
 *   <li>Sessione utente: {@code Authorization: Bearer <token di sessione>} ottenuto da /api/auth/login.</li>
 *   <li>API_TOKEN (facoltativo): accesso tecnico per script e automazioni; se vuoto è disabilitato.</li>
 * </ul>
 * Ruoli: READER sola lettura; ASSESSOR lettura e scrittura; ADMIN anche gestione utenti.
 */
@Component
public class LocalAccessFilter extends OncePerRequestFilter {
    private static final Set<String> PUBLIC = Set.of("/api/health", "/api/auth/status", "/api/auth/login", "/api/auth/setup");
    private static final Set<String> ANY_ROLE_WRITE = Set.of("/api/auth/logout", "/api/auth/password");

    private final String token;
    private final UserService users;

    public LocalAccessFilter(@Value("${regmac.api-token:}") String token, UserService users) {
        this.token = token == null ? "" : token.trim();
        this.users = users;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws ServletException, IOException {
        String uri = req.getRequestURI();
        if (!uri.startsWith("/api/") || PUBLIC.contains(uri)) {
            try { chain.doFilter(req, res); } finally { UserService.CURRENT.remove(); }
            return;
        }
        String auth = req.getHeader("Authorization");
        String bearer = auth != null && auth.startsWith("Bearer ") ? auth.substring(7).trim() : "";
        Optional<User> user = Optional.empty();
        if (!bearer.isEmpty()) {
            if (!token.isEmpty() && MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8), bearer.getBytes(StandardCharsets.UTF_8)))
                user = Optional.of(UserService.SERVICE);
            else
                user = users.session(bearer);
        }
        if (user.isEmpty()) { deny(res, 401, "Sessione scaduta o non valida: accedi di nuovo"); return; }
        User u = user.get();
        boolean read = "GET".equals(req.getMethod()) || "HEAD".equals(req.getMethod());
        if (uri.startsWith("/api/users") && u.role() != Role.ADMIN) { deny(res, 403, "Operazione riservata agli amministratori"); return; }
        if (!read && !ANY_ROLE_WRITE.contains(uri) && u.role() == Role.READER) { deny(res, 403, "Il ruolo Lettore non consente modifiche"); return; }
        UserService.CURRENT.set(u);
        try { chain.doFilter(req, res); } finally { UserService.CURRENT.remove(); }
    }

    private static void deny(HttpServletResponse res, int status, String message) throws IOException {
        res.setStatus(status);
        res.setContentType("application/json;charset=UTF-8");
        res.getWriter().write("{\"error\":\"" + message.replace("\"", "'") + "\"}");
    }
}
