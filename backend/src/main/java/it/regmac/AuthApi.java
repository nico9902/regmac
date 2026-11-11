package it.regmac;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

import static it.regmac.UserService.Role;
import static it.regmac.UserService.User;

/** Accesso, configurazione iniziale e gestione utenti. */
@RestController
@RequestMapping("/api")
public class AuthApi {
    private final UserService users;

    public AuthApi(UserService users) { this.users = users; }

    public record Credentials(String username, String password) {}
    public record SetupInput(String username, String displayName, String password) {}
    public record UserInput(String username, String displayName, String password, Role role, Boolean active) {}
    public record PasswordInput(String current, String next) {}

    /** Indica se serve la configurazione iniziale (nessun utente ancora creato). */
    @GetMapping("/auth/status")
    public Map<String, Object> status() { return Map.of("setupRequired", !users.hasUsers()); }

    @PostMapping("/auth/setup")
    public UserService.Session setup(@RequestBody SetupInput in) {
        users.setup(in.username(), in.displayName(), in.password());
        return users.login(in.username(), in.password());
    }

    @PostMapping("/auth/login")
    public UserService.Session login(@RequestBody Credentials c) { return users.login(c.username(), c.password()); }

    @PostMapping("/auth/logout")
    public Map<String, String> logout(@RequestHeader("Authorization") String auth) {
        users.logout(auth.substring(7).trim());
        return Map.of("status", "ok");
    }

    @GetMapping("/auth/me")
    public User me() { return UserService.current(); }

    @PostMapping("/auth/password")
    public Map<String, String> password(@RequestBody PasswordInput in) {
        users.changeOwnPassword(in.current(), in.next());
        return Map.of("status", "ok");
    }

    @GetMapping("/users")
    public List<User> list() { return users.list(); }

    @PostMapping("/users")
    public User create(@RequestBody UserInput in) { return users.create(in.username(), in.displayName(), in.password(), in.role()); }

    @PutMapping("/users/{id}")
    public User update(@PathVariable String id, @RequestBody UserInput in) {
        return users.update(id, in.displayName(), in.role(), in.active(), in.password());
    }

    @ExceptionHandler(SecurityException.class) @ResponseStatus(HttpStatus.UNAUTHORIZED)
    public Map<String, String> unauthorized(Exception e) { return Map.of("error", e.getMessage()); }

    @ExceptionHandler(NoSuchElementException.class) @ResponseStatus(HttpStatus.NOT_FOUND)
    public Map<String, String> missing(Exception e) { return Map.of("error", e.getMessage()); }

    @ExceptionHandler(IllegalArgumentException.class) @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> invalid(Exception e) { return Map.of("error", e.getMessage()); }
}
