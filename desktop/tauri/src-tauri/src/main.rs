// RegMac: finestra nativa. Avvia il motore Java incluso nel pacchetto (porta e token scelti qui),
// mostra una schermata di caricamento, poi apre l'interfaccia; alla chiusura spegne il motore in modo ordinato.
#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

use rand::RngCore;
use std::io::{Read, Write};
use std::net::{TcpListener, TcpStream};
use std::path::PathBuf;
use std::process::{Child, Command, Stdio};
use std::sync::Mutex;
use std::time::{Duration, Instant};
use tauri::{Manager, RunEvent, WebviewUrl, WebviewWindowBuilder};

struct Backend {
    child: Mutex<Option<Child>>,
    port: u16,
    token: String,
}

fn free_port() -> u16 {
    TcpListener::bind("127.0.0.1:0").and_then(|l| l.local_addr()).map(|a| a.port()).unwrap_or(48231)
}

fn new_token() -> String {
    let mut b = [0u8; 24];
    rand::thread_rng().fill_bytes(&mut b);
    b.iter().map(|x| format!("{:02x}", x)).collect()
}

/// Richiesta HTTP minimale al motore locale (nessuna dipendenza esterna).
fn http(port: u16, method: &str, path: &str, token: &str) -> Option<u16> {
    let addr = format!("127.0.0.1:{port}").parse().ok()?;
    let mut s = TcpStream::connect_timeout(&addr, Duration::from_millis(400)).ok()?;
    s.set_read_timeout(Some(Duration::from_secs(5))).ok()?;
    write!(s, "{method} {path} HTTP/1.1\r\nHost: 127.0.0.1:{port}\r\nCookie: regmac={token}\r\nContent-Length: 0\r\nConnection: close\r\n\r\n").ok()?;
    let mut buf = [0u8; 32];
    let n = s.read(&mut buf).ok()?;
    std::str::from_utf8(&buf[..n]).ok()?.split_whitespace().nth(1)?.parse().ok()
}

fn java_path(res: &PathBuf) -> PathBuf {
    let bin = res.join("backend").join("runtime").join("bin");
    if cfg!(windows) { bin.join("java.exe") } else { bin.join("java") }
}

fn start_backend(res: &PathBuf, port: u16, token: &str, log: Option<PathBuf>) -> std::io::Result<Child> {
    let backend = res.join("backend");
    let mut cmd = Command::new(java_path(res));
    // si avvia dalla cartella del motore e si passa il jar per nome: su Windows i percorsi estesi (\\?\C:\...)
    // fanno fallire il caricatore delle classi di Java («impossibile trovare la classe principale»)
    cmd.current_dir(&backend);
    cmd.args([
        "-Xmx2g",
        "-Dfile.encoding=UTF-8",
        "-Djava.awt.headless=true",
        "-Dregmac.embedded=true",
        &format!("-Dregmac.port={port}"),
        &format!("-Dregmac.token={token}"),
        &format!("-Dregmac.parent={}", std::process::id()),
        "-jar",
    ])
    .arg("regmac-desktop.jar")
    .stdin(Stdio::null());
    if let Some(p) = log.and_then(|p| std::fs::File::create(p).ok()) {
        cmd.stdout(p.try_clone().map(Stdio::from).unwrap_or(Stdio::null())).stderr(Stdio::from(p));
    } else {
        cmd.stdout(Stdio::null()).stderr(Stdio::null());
    }
    #[cfg(windows)]
    {
        use std::os::windows::process::CommandExt;
        cmd.creation_flags(0x0800_0000); // CREATE_NO_WINDOW
    }
    cmd.spawn()
}

/// Ultime righe del registro del motore, da mostrare nella schermata d'errore.
fn log_tail(log: &Option<PathBuf>) -> String {
    let Some(p) = log else { return String::new() };
    let text = std::fs::read_to_string(p).unwrap_or_default();
    let lines: Vec<&str> = text.lines().filter(|l| !l.trim().is_empty()).collect();
    let start = lines.len().saturating_sub(14);
    format!("{}\n\nRegistro completo: {}", lines[start..].join("\n"), p.display())
}

fn show_error(handle: &tauri::AppHandle, msg: &str) {
    if let Some(w) = handle.get_webview_window("main") {
        let _ = w.eval(&format!("window.showError({:?})", msg));
    }
}

fn stop_backend(app: &tauri::AppHandle) {
    let state = app.state::<Backend>();
    let mut guard = state.child.lock().unwrap();
    if let Some(mut child) = guard.take() {
        let _ = http(state.port, "POST", "/api/shutdown", &state.token);
        let t = Instant::now();
        while t.elapsed() < Duration::from_secs(6) {
            if let Ok(Some(_)) = child.try_wait() { return; }
            std::thread::sleep(Duration::from_millis(150));
        }
        let _ = child.kill();
    }
}

fn main() {
    let port = free_port();
    let token = new_token();
    let app = tauri::Builder::default()
        .plugin(tauri_plugin_single_instance::init(|app, _args, _cwd| {
            if let Some(w) = app.get_webview_window("main") {
                let _ = w.unminimize();
                let _ = w.set_focus();
            }
        }))
        .manage(Backend { child: Mutex::new(None), port, token: token.clone() })
        .setup(move |app| {
            let window = WebviewWindowBuilder::new(app, "main", WebviewUrl::App("index.html".into()))
                .title("RegMac")
                .inner_size(1360.0, 880.0)
                .min_inner_size(1024.0, 680.0)
                .disable_drag_drop_handler() // il trascinamento dei file va all'interfaccia web
                .build()?;
            let res = dunce::simplified(&app.path().resource_dir()?).to_path_buf();
            let log = app.path().app_log_dir().ok().map(|d| {
                let _ = std::fs::create_dir_all(&d);
                d.join("motore.log")
            });
            let java = java_path(&res);
            match start_backend(&res, port, &token, log.clone()) {
                Ok(child) => {
                    *app.state::<Backend>().child.lock().unwrap() = Some(child);
                }
                Err(e) => {
                    let msg = format!("Impossibile avviare il motore di RegMac ({}): {e}", java.display());
                    let _ = window.eval(&format!("window.showError({:?})", msg));
                    return Ok(());
                }
            }
            let handle = app.handle().clone();
            let token = token.clone();
            let log = log.clone();
            std::thread::spawn(move || {
                let t = Instant::now();
                let mut slow_notice = false;
                let mut exit_info = String::new();
                while t.elapsed() < Duration::from_secs(180) {
                    if !slow_notice && t.elapsed() > Duration::from_secs(12) {
                        slow_notice = true;
                        if let Some(w) = handle.get_webview_window("main") {
                            let _ = w.eval("window.slow && window.slow()");
                        }
                    }
                    if http(port, "GET", "/api/ping", &token) == Some(200) {
                        if let Some(w) = handle.get_webview_window("main") {
                            let url = format!("http://127.0.0.1:{port}/?t={token}");
                            if let Ok(u) = url.parse() { let _ = w.navigate(u); }
                        }
                        return;
                    }
                    // il motore si è chiuso subito (es. un'altra copia di RegMac è già aperta)
                    let exited = handle.state::<Backend>().child.lock().unwrap().as_mut().and_then(|c| c.try_wait().ok().flatten());
                    if let Some(code) = exited { exit_info = format!("Il motore si è chiuso subito ({code})."); break; }
                    std::thread::sleep(Duration::from_millis(250));
                }
                if exit_info.is_empty() { exit_info = "Il motore non ha risposto entro 3 minuti.".into(); }
                let msg = format!("{exit_info}\n\n{}", log_tail(&log));
                show_error(&handle, &msg);
            });
            Ok(())
        })
        .build(tauri::generate_context!())
        .expect("errore durante l'avvio di RegMac");

    app.run(|handle, event| {
        if let RunEvent::Exit = event {
            stop_backend(handle);
        }
    });
}
