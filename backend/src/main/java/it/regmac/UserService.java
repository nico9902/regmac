package it.regmac;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

import static it.regmac.Domain.id;
import static it.regmac.Domain.now;
import static it.regmac.RequirementCompiler.require;

/** Utenti individuali, ruoli, sessioni e verifica della password (firma delle chiusure). */
@Service
public class UserService {

    public enum Role { ADMIN, ASSESSOR, READER }

    /** Record persistito: l'hash della password non esce mai dal servizio. */
    public record StoredUser(String id, String username, String displayName, Role role, boolean active,
                             String salt, String hash, int iterations, String createdAt, String lastLoginAt) {
        public User view() { return new User(id, username, displayName, role, active, createdAt, lastLoginAt); }
    }

    public record User(String id, String username, String displayName, Role role, boolean active, String createdAt, String lastLoginAt) {}

    public record Session(String token, String expiresAt, User user) {}

    /** Utente della richiesta corrente (impostato dal filtro di accesso). */
    public static final ThreadLocal<User> CURRENT = new ThreadLocal<>();

    /** Utente tecnico usato quando si accede con API_TOKEN (script e automazioni). */
    public static final User SERVICE = new User("service", "servizio", "Servizio (API_TOKEN)", Role.ADMIN, true, null, null);

    static final Duration SESSION_TTL = Duration.ofHours(12);
    private static final int ITERATIONS = 210_000;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcTemplate jdbc;
    private final Store store;

    public UserService(JdbcTemplate jdbc, Store store) {
        this.jdbc = jdbc;
        this.store = store;
    }

    public static User current() {
        User u = CURRENT.get();
        if (u == null) throw new SecurityException("Accesso richiesto");
        return u;
    }

    public static String actor() {
        User u = CURRENT.get();
        return u == null ? null : u.displayName() + " (" + u.username() + ")";
    }

    // ------------------------------------------------------------------ setup e utenti
    public boolean hasUsers() { return jdbc.queryForObject("SELECT COUNT(*) FROM app_user", Integer.class) > 0; }

    @Transactional
    public User setup(String username, String displayName, String password) {
        require(!hasUsers(), "Configurazione iniziale già eseguita");
        User u = create(username, displayName, password, Role.ADMIN);
        store.audit("USER_SETUP_ADMIN", u.id(), Map.of("username", u.username()));
        return u;
    }

    @Transactional
    public User create(String username, String displayName, String password, Role role) {
        require(username != null && username.matches("[a-zA-Z0-9._-]{3,60}"), "Nome utente: 3-60 caratteri tra lettere, numeri, punto, trattino");
        require(displayName != null && !displayName.isBlank() && displayName.length() <= 120, "Nome e cognome richiesti");
        require(role != null, "Ruolo richiesto");
        checkPassword(password);
        String uname = username.toLowerCase(Locale.ROOT);
        require(jdbc.queryForObject("SELECT COUNT(*) FROM app_user WHERE username=?", Integer.class, uname) == 0, "Nome utente già in uso");
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        StoredUser s = new StoredUser(id(), uname, displayName.trim(), role, true, HexFormat.of().formatHex(salt),
                hash(password, salt, ITERATIONS), ITERATIONS, now(), null);
        jdbc.update("INSERT INTO app_user (id,username,payload) VALUES (?,?,?)", s.id(), s.username(), store.json(s));
        if (CURRENT.get() != null) store.audit("USER_CREATED", s.id(), Map.of("username", s.username(), "role", role));
        return s.view();
    }

    public List<User> list() {
        return jdbc.query("SELECT payload FROM app_user ORDER BY username", (rs, n) -> store.decode(rs.getString(1), StoredUser.class).view());
    }

    @Transactional
    public User update(String id, String displayName, Role role, Boolean active, String newPassword) {
        StoredUser s = stored(id);
        User me = current();
        if (me.id().equals(id)) {
            require(active == null || active, "Non puoi disattivare il tuo utente");
            require(role == null || role == s.role(), "Non puoi cambiare il tuo ruolo");
        }
        Role r = role == null ? s.role() : role;
        boolean act = active == null ? s.active() : active;
        if (s.role() == Role.ADMIN && (r != Role.ADMIN || !act))
            require(countActiveAdmins() > 1, "Deve restare almeno un amministratore attivo");
        String salt = s.salt(), hash = s.hash();
        int it = s.iterations();
        if (newPassword != null && !newPassword.isEmpty()) {
            checkPassword(newPassword);
            byte[] b = new byte[16];
            RANDOM.nextBytes(b);
            salt = HexFormat.of().formatHex(b);
            hash = hash(newPassword, b, ITERATIONS);
            it = ITERATIONS;
        }
        StoredUser u = new StoredUser(s.id(), s.username(), displayName == null || displayName.isBlank() ? s.displayName() : displayName.trim(),
                r, act, salt, hash, it, s.createdAt(), s.lastLoginAt());
        jdbc.update("UPDATE app_user SET payload=? WHERE id=?", store.json(u), id);
        if (!act || newPassword != null && !newPassword.isEmpty()) jdbc.update("DELETE FROM user_session WHERE user_id=?", id);
        store.audit("USER_UPDATED", id, Map.of("username", u.username(), "role", r, "active", act, "passwordReset", newPassword != null && !newPassword.isEmpty()));
        return u.view();
    }

    @Transactional
    public void changeOwnPassword(String current, String next) {
        User me = current();
        require(!SERVICE.id().equals(me.id()), "Operazione non disponibile per l'utente di servizio");
        StoredUser s = stored(me.id());
        require(verify(s, current), "Password attuale non corretta");
        checkPassword(next);
        byte[] b = new byte[16];
        RANDOM.nextBytes(b);
        StoredUser u = new StoredUser(s.id(), s.username(), s.displayName(), s.role(), s.active(), HexFormat.of().formatHex(b),
                hash(next, b, ITERATIONS), ITERATIONS, s.createdAt(), s.lastLoginAt());
        jdbc.update("UPDATE app_user SET payload=? WHERE id=?", store.json(u), s.id());
        store.audit("USER_PASSWORD_CHANGED", s.id(), Map.of("username", s.username()));
    }

    // ------------------------------------------------------------------ sessioni
    // non transazionale: il tentativo fallito resta nell'audit trail
    public Session login(String username, String password) {
        var rows = jdbc.query("SELECT payload FROM app_user WHERE username=?", (rs, n) -> store.decode(rs.getString(1), StoredUser.class),
                username == null ? "" : username.toLowerCase(Locale.ROOT));
        StoredUser s = rows.isEmpty() ? null : rows.getFirst();
        if (s == null || !s.active() || !verify(s, password)) {
            store.audit("LOGIN_FAILED", "auth", Map.of("username", Objects.toString(username, "")));
            throw new SecurityException("Credenziali non valide");
        }
        byte[] raw = new byte[32];
        RANDOM.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        String expires = Instant.now().plus(SESSION_TTL).toString();
        jdbc.update("DELETE FROM user_session WHERE expires_at<?", now());
        jdbc.update("INSERT INTO user_session (token_sha256,user_id,created_at,expires_at) VALUES (?,?,?,?)", sha(token), s.id(), now(), expires);
        StoredUser u = new StoredUser(s.id(), s.username(), s.displayName(), s.role(), s.active(), s.salt(), s.hash(), s.iterations(), s.createdAt(), now());
        jdbc.update("UPDATE app_user SET payload=? WHERE id=?", store.json(u), s.id());
        CURRENT.set(u.view());
        store.audit("LOGIN", s.id(), Map.of("username", s.username()));
        return new Session(token, expires, u.view());
    }

    public Optional<User> session(String token) {
        var rows = jdbc.queryForList("SELECT user_id,expires_at FROM user_session WHERE token_sha256=?", sha(token));
        if (rows.isEmpty()) return Optional.empty();
        if (String.valueOf(rows.getFirst().get("expires_at")).compareTo(now()) < 0) return Optional.empty();
        StoredUser s = stored(String.valueOf(rows.getFirst().get("user_id")));
        return s.active() ? Optional.of(s.view()) : Optional.empty();
    }

    @Transactional
    public void logout(String token) {
        jdbc.update("DELETE FROM user_session WHERE token_sha256=?", sha(token));
    }

    /** Conferma dell'identità per la firma: verifica la password dell'utente corrente. */
    public User confirm(String password) {
        User me = current();
        require(!SERVICE.id().equals(me.id()), "La firma richiede un utente personale, non l'API_TOKEN");
        require(verify(stored(me.id()), password), "Password non corretta: firma non apposta");
        return me;
    }

    // ------------------------------------------------------------------ supporto
    private int countActiveAdmins() {
        return (int) list().stream().filter(u -> u.active() && u.role() == Role.ADMIN).count();
    }

    private StoredUser stored(String id) {
        var rows = jdbc.query("SELECT payload FROM app_user WHERE id=?", (rs, n) -> store.decode(rs.getString(1), StoredUser.class), id);
        if (rows.isEmpty()) throw new NoSuchElementException("Utente non trovato");
        return rows.getFirst();
    }

    private static void checkPassword(String p) {
        require(p != null && p.length() >= 10 && p.length() <= 200, "La password deve avere almeno 10 caratteri");
    }

    private static boolean verify(StoredUser s, String password) {
        if (password == null) return false;
        String h = hash(password, HexFormat.of().parseHex(s.salt()), s.iterations());
        return MessageDigest.isEqual(h.getBytes(StandardCharsets.UTF_8), s.hash().getBytes(StandardCharsets.UTF_8));
    }

    private static String hash(String password, byte[] salt, int iterations) {
        try {
            var spec = new PBEKeySpec(password.toCharArray(), salt, iterations, 256);
            return HexFormat.of().formatHex(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded());
        } catch (Exception e) {
            throw new IllegalStateException("Hash password non disponibile", e);
        }
    }

    static String sha(String s) { return KnowledgeService.sha(s.getBytes(StandardCharsets.UTF_8)); }
}
