//! Google sign-in and the few Drive calls sync needs. Everything here blocks, so it runs on worker threads.
//!
//! Sign-in is the installed-app flow: the browser opens Google's consent page, which redirects to a one-shot
//! server on 127.0.0.1 with a code; the code and a PKCE verifier buy a refresh token, kept in the system
//! keyring (or, where there is none, a file only this user can read).

use std::fmt;
use std::fs;
use std::io::{BufRead, BufReader, Write};
use std::net::TcpListener;
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex, OnceLock};
use std::time::{Duration, Instant};

use base64::Engine;
use base64::engine::general_purpose::URL_SAFE_NO_PAD;
use serde_json::{Value, json};
use sha2::{Digest, Sha256};

const AUTH_URL: &str = "https://accounts.google.com/o/oauth2/v2/auth";
const TOKEN_URL: &str = "https://oauth2.googleapis.com/token";
const USERINFO_URL: &str = "https://openidconnect.googleapis.com/v1/userinfo";
const SCOPES: &str = "https://www.googleapis.com/auth/drive.appdata email";
const FILES_URL: &str = "https://www.googleapis.com/drive/v3/files";
const UPLOAD_URL: &str = "https://www.googleapis.com/upload/drive/v3/files";
const FILE_FIELDS: &str = "id,name,version,size";
const KEYRING_SERVICE: &str = "io.github.gsweya.GBook";
const KEYRING_USER: &str = "google-drive";

#[derive(Debug)]
pub enum Error {
    /// The refresh token is gone or was revoked: the user has to sign in again.
    SignedOut,
    /// Signed in, but without the Drive permission: Google's consent page lets people untick it.
    NoDriveAccess,
    Failed(String),
}

impl fmt::Display for Error {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Error::SignedOut => {
                f.write_str("Google Drive signed GBook out. Sign in again to keep syncing.")
            }
            Error::NoDriveAccess => f.write_str(
                "GBook wasn't allowed to use Google Drive. Sign in again and tick the Google Drive box.",
            ),
            Error::Failed(s) => f.write_str(s),
        }
    }
}

impl From<ureq::Error> for Error {
    fn from(e: ureq::Error) -> Self {
        Error::Failed(match e {
            ureq::Error::Io(e) => format!("No connection ({e})"),
            ureq::Error::HostNotFound => "No connection".into(),
            ureq::Error::Timeout(_) => "Google Drive didn't answer in time".into(),
            e => e.to_string(),
        })
    }
}

impl From<std::io::Error> for Error {
    fn from(e: std::io::Error) -> Self {
        Error::Failed(e.to_string())
    }
}

pub type Result<T> = std::result::Result<T, Error>;

/// The desktop OAuth client: built in with `GBOOK_GOOGLE_CLIENT_ID` and `GBOOK_GOOGLE_CLIENT_SECRET`, or
/// given in the same variables at run time. Google treats a desktop client's secret as public.
pub fn client() -> Option<(String, String)> {
    let var = |k: &str, built: Option<&str>| {
        std::env::var(k)
            .ok()
            .or(built.map(String::from))
            .filter(|s| !s.is_empty())
    };
    let id = var(
        "GBOOK_GOOGLE_CLIENT_ID",
        option_env!("GBOOK_GOOGLE_CLIENT_ID"),
    )?;
    let secret = var(
        "GBOOK_GOOGLE_CLIENT_SECRET",
        option_env!("GBOOK_GOOGLE_CLIENT_SECRET"),
    )
    .unwrap_or_default();
    Some((id, secret))
}

/// `real`, or the same path on `$GBOOK_GOOGLE_API` when that is set: a stand-in server for testing sync.
fn url(real: &str) -> String {
    match std::env::var("GBOOK_GOOGLE_API") {
        Ok(base) if !base.is_empty() => {
            let path = real
                .strip_prefix("https://")
                .and_then(|r| r.find('/').map(|i| &r[i..]))
                .unwrap_or("");
            format!("{}{path}", base.trim_end_matches('/'))
        }
        _ => real.to_string(),
    }
}

fn agent() -> &'static ureq::Agent {
    static AGENT: OnceLock<ureq::Agent> = OnceLock::new();
    AGENT.get_or_init(|| {
        ureq::Agent::config_builder()
            // The system's TLS: OpenSSL on Linux, Schannel on Windows.
            .tls_config(
                ureq::tls::TlsConfig::builder()
                    .provider(ureq::tls::TlsProvider::NativeTls)
                    .build(),
            )
            .http_status_as_error(false)
            .timeout_connect(Some(Duration::from_secs(20)))
            .timeout_recv_response(Some(Duration::from_secs(60)))
            .build()
            .into()
    })
}

type Response = ureq::http::Response<ureq::Body>;

/// The response, or Google's error message for a failed request.
fn ok(mut r: Response) -> Result<Response> {
    let status = r.status().as_u16();
    if status < 300 {
        return Ok(r);
    }
    let body = r.body_mut().read_to_string().unwrap_or_default();
    if status == 403 && body.contains("insufficient") {
        return Err(Error::NoDriveAccess);
    }
    let msg = serde_json::from_str::<Value>(&body)
        .ok()
        .and_then(|v| {
            v["error"]["message"]
                .as_str()
                .or(v["error_description"].as_str())
                .or(v["error"].as_str())
                .map(String::from)
        })
        .unwrap_or_else(|| format!("HTTP {status}"));
    Err(Error::Failed(format!("Google Drive: {msg}")))
}

fn json_of(mut r: Response) -> Result<Value> {
    let s = r.body_mut().read_to_string()?;
    serde_json::from_str(&s).map_err(|e| Error::Failed(e.to_string()))
}

fn random_token(bytes: usize) -> String {
    let mut b = vec![0u8; bytes];
    getrandom::fill(&mut b).expect("system random numbers");
    URL_SAFE_NO_PAD.encode(b)
}

fn percent_decode(s: &str) -> String {
    let b = s.as_bytes();
    let mut out = Vec::with_capacity(b.len());
    let mut i = 0;
    while i < b.len() {
        match b[i] {
            b'+' => out.push(b' '),
            b'%' if i + 2 < b.len() => {
                match std::str::from_utf8(&b[i + 1..i + 3])
                    .ok()
                    .and_then(|h| u8::from_str_radix(h, 16).ok())
                {
                    Some(v) => {
                        out.push(v);
                        i += 2;
                    }
                    None => out.push(b'%'),
                }
            }
            c => out.push(c),
        }
        i += 1;
    }
    String::from_utf8_lossy(&out).into_owned()
}

fn percent_encode(s: &str) -> String {
    s.bytes()
        .map(|c| match c {
            b'A'..=b'Z' | b'a'..=b'z' | b'0'..=b'9' | b'-' | b'.' | b'_' | b'~' => {
                (c as char).to_string()
            }
            c => format!("%{c:02X}"),
        })
        .collect()
}

// ---- Sign-in ----

/// A sign-in waiting for the browser to come back.
pub struct SignIn {
    listener: TcpListener,
    verifier: String,
    state: String,
    redirect: String,
    pub url: String,
    pub cancel: Arc<AtomicBool>,
}

pub struct Account {
    pub email: String,
    pub refresh_token: String,
}

/// Starts listening for Google's redirect and returns the consent page to open in the browser.
pub fn begin_sign_in() -> Result<SignIn> {
    let (client_id, _) = client().ok_or_else(|| Error::Failed("No Google client ID".into()))?;
    let listener = TcpListener::bind("127.0.0.1:0")?;
    let redirect = format!("http://127.0.0.1:{}", listener.local_addr()?.port());
    let verifier = random_token(48);
    let challenge = URL_SAFE_NO_PAD.encode(Sha256::digest(verifier.as_bytes()));
    let state = random_token(16);
    let url = format!(
        "{AUTH_URL}?client_id={}&redirect_uri={}&response_type=code&scope={}&code_challenge={challenge}\
         &code_challenge_method=S256&state={state}&access_type=offline&prompt=consent",
        percent_encode(&client_id),
        percent_encode(&redirect),
        percent_encode(SCOPES),
    );
    Ok(SignIn {
        listener,
        verifier,
        state,
        redirect,
        url,
        cancel: Arc::new(AtomicBool::new(false)),
    })
}

const DONE_PAGE: &str = "<!doctype html><meta charset=utf-8><title>GBook</title>\
    <body style=\"font:16px system-ui,sans-serif;text-align:center;padding-top:20vh\">\
    <h2>GBook is connected to Google Drive</h2><p>You can close this tab and go back to GBook.</p>";
const FAILED_PAGE: &str = "<!doctype html><meta charset=utf-8><title>GBook</title>\
    <body style=\"font:16px system-ui,sans-serif;text-align:center;padding-top:20vh\">\
    <h2>GBook wasn't connected</h2><p>You can close this tab and try again from GBook's settings.</p>";

impl SignIn {
    /// Waits up to five minutes for the browser, then trades the code for tokens.
    pub fn finish(self) -> Result<Account> {
        self.listener.set_nonblocking(true)?;
        let deadline = Instant::now() + Duration::from_secs(300);
        let code = loop {
            if self.cancel.load(Ordering::Relaxed) {
                return Err(Error::Failed("Sign-in cancelled".into()));
            }
            if Instant::now() > deadline {
                return Err(Error::Failed("Sign-in timed out".into()));
            }
            let mut stream = match self.listener.accept() {
                Ok((s, _)) => s,
                Err(e) if e.kind() == std::io::ErrorKind::WouldBlock => {
                    std::thread::sleep(Duration::from_millis(150));
                    continue;
                }
                Err(e) => return Err(e.into()),
            };
            stream.set_nonblocking(false)?;
            stream.set_read_timeout(Some(Duration::from_secs(5)))?;
            let mut line = String::new();
            BufReader::new(&stream).read_line(&mut line)?;
            // "GET /?state=…&code=…&scope=… HTTP/1.1"
            let target = line.split_whitespace().nth(1).unwrap_or("");
            let query = target.split_once('?').map(|(_, q)| q).unwrap_or("");
            let param = |k: &str| {
                query
                    .split('&')
                    .filter_map(|kv| kv.split_once('='))
                    .find(|(key, _)| *key == k)
                    .map(|(_, v)| percent_decode(v))
            };
            if param("state").is_none() && param("error").is_none() {
                // The browser asking for a favicon, say.
                let _ = stream.write_all(b"HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\n\r\n");
                continue;
            }
            let good = param("state").as_deref() == Some(self.state.as_str());
            let code = param("code").filter(|_| good);
            let page = if code.is_some() {
                DONE_PAGE
            } else {
                FAILED_PAGE
            };
            let _ = write!(
                stream,
                "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{page}",
                page.len()
            );
            match code {
                Some(c) => break c,
                None if param("error").as_deref() == Some("access_denied") => {
                    return Err(Error::Failed("Google Drive access wasn't allowed".into()));
                }
                None => return Err(Error::Failed("Sign-in failed".into())),
            }
        };
        let (client_id, secret) = client().unwrap_or_default();
        let r = agent().post(url(TOKEN_URL)).send_form([
            ("code", code.as_str()),
            ("client_id", client_id.as_str()),
            ("client_secret", secret.as_str()),
            ("code_verifier", self.verifier.as_str()),
            ("redirect_uri", self.redirect.as_str()),
            ("grant_type", "authorization_code"),
        ])?;
        let v = json_of(ok(r)?)?;
        if !v["scope"].as_str().unwrap_or("").contains("drive.appdata") {
            return Err(Error::NoDriveAccess);
        }
        let refresh = v["refresh_token"]
            .as_str()
            .ok_or_else(|| Error::Failed("Google didn't grant offline access".into()))?
            .to_string();
        let access = v["access_token"].as_str().unwrap_or_default().to_string();
        remember_access(&access, v["expires_in"].as_i64().unwrap_or(3600));
        let info = json_of(ok(agent()
            .get(url(USERINFO_URL))
            .header("Authorization", format!("Bearer {access}"))
            .call()?)?)?;
        Ok(Account {
            email: info["email"].as_str().unwrap_or_default().to_string(),
            refresh_token: refresh,
        })
    }
}

// ---- Tokens ----

static ACCESS: Mutex<Option<(String, Instant)>> = Mutex::new(None);

fn remember_access(token: &str, expires_in: i64) {
    let until = Instant::now() + Duration::from_secs(expires_in.max(120) as u64 - 60);
    *ACCESS.lock().unwrap() = Some((token.to_string(), until));
}

fn token_file(sync_dir: &Path) -> PathBuf {
    sync_dir.join("token")
}

/// Keeps the refresh token in the keyring, or failing that in a private file.
pub fn save_refresh_token(sync_dir: &Path, token: &str) -> Result<()> {
    let keyring =
        keyring::Entry::new(KEYRING_SERVICE, KEYRING_USER).and_then(|e| e.set_password(token));
    if keyring.is_ok() {
        let _ = fs::remove_file(token_file(sync_dir));
        return Ok(());
    }
    fs::create_dir_all(sync_dir)?;
    let f = token_file(sync_dir);
    gbook_core::store::write_atomic(&f, token.as_bytes())?;
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        fs::set_permissions(&f, fs::Permissions::from_mode(0o600))?;
    }
    Ok(())
}

fn refresh_token(sync_dir: &Path) -> Option<String> {
    keyring::Entry::new(KEYRING_SERVICE, KEYRING_USER)
        .and_then(|e| e.get_password())
        .ok()
        .or_else(|| fs::read_to_string(token_file(sync_dir)).ok())
        .map(|s| s.trim().to_string())
        .filter(|s| !s.is_empty())
}

/// Signs out: forgets the tokens and asks Google to revoke them.
pub fn sign_out(sync_dir: &Path) {
    if let Some(t) = refresh_token(sync_dir) {
        let _ = agent()
            .post(url("https://oauth2.googleapis.com/revoke"))
            .send_form([("token", t.as_str())]);
    }
    if let Ok(e) = keyring::Entry::new(KEYRING_SERVICE, KEYRING_USER) {
        let _ = e.delete_credential();
    }
    let _ = fs::remove_file(token_file(sync_dir));
    *ACCESS.lock().unwrap() = None;
}

// ---- Drive ----

#[derive(Clone, Debug)]
pub struct DriveFile {
    pub id: String,
    pub name: String,
    pub version: i64,
}

impl DriveFile {
    fn of(v: &Value) -> DriveFile {
        let int = |v: &Value| v.as_i64().or_else(|| v.as_str()?.parse().ok()).unwrap_or(0);
        DriveFile {
            id: v["id"].as_str().unwrap_or_default().to_string(),
            name: v["name"].as_str().unwrap_or_default().to_string(),
            version: int(&v["version"]),
        }
    }
}

/// Signed-in access to the app's hidden Drive folder.
#[derive(Clone)]
pub struct Drive {
    access: String,
}

impl Drive {
    /// Uses the cached access token, or the stored refresh token for a new one.
    pub fn connect(sync_dir: &Path) -> Result<Drive> {
        if let Some((t, until)) = ACCESS.lock().unwrap().clone()
            && Instant::now() < until
        {
            return Ok(Drive { access: t });
        }
        let refresh = refresh_token(sync_dir).ok_or(Error::SignedOut)?;
        let (client_id, secret) = client().ok_or(Error::SignedOut)?;
        let r = agent().post(url(TOKEN_URL)).send_form([
            ("refresh_token", refresh.as_str()),
            ("client_id", client_id.as_str()),
            ("client_secret", secret.as_str()),
            ("grant_type", "refresh_token"),
        ])?;
        if r.status().as_u16() == 400 || r.status().as_u16() == 401 {
            let v = json_of(r).unwrap_or_default();
            if v["error"] == "invalid_grant" {
                return Err(Error::SignedOut);
            }
            return Err(Error::Failed(format!(
                "Google sign-in: {}",
                v["error_description"].as_str().unwrap_or("refused")
            )));
        }
        let v = json_of(ok(r)?)?;
        let access = v["access_token"].as_str().unwrap_or_default().to_string();
        remember_access(&access, v["expires_in"].as_i64().unwrap_or(3600));
        Ok(Drive { access })
    }

    fn auth(&self) -> String {
        format!("Bearer {}", self.access)
    }

    fn check(&self, r: Response) -> Result<Response> {
        if r.status().as_u16() == 401 {
            *ACCESS.lock().unwrap() = None;
        }
        ok(r)
    }

    /// Every file in the app data folder.
    pub fn list(&self) -> Result<Vec<DriveFile>> {
        let mut out = Vec::new();
        let mut page: Option<String> = None;
        loop {
            let mut req = agent()
                .get(url(FILES_URL))
                .header("Authorization", self.auth())
                .query("spaces", "appDataFolder")
                .query("pageSize", "1000")
                .query("fields", format!("nextPageToken,files({FILE_FIELDS})"));
            if let Some(p) = &page {
                req = req.query("pageToken", p);
            }
            let v = json_of(self.check(req.call()?)?)?;
            out.extend(
                v["files"]
                    .as_array()
                    .into_iter()
                    .flatten()
                    .map(DriveFile::of),
            );
            match v["nextPageToken"].as_str() {
                Some(p) => page = Some(p.to_string()),
                None => return Ok(out),
            }
        }
    }

    pub fn download(&self, id: &str) -> Result<Vec<u8>> {
        let r = agent()
            .get(url(&format!("{FILES_URL}/{id}")))
            .header("Authorization", self.auth())
            .query("alt", "media")
            .call()?;
        let mut r = self.check(r)?;
        Ok(r.body_mut()
            .with_config()
            .limit(512 * 1024 * 1024)
            .read_to_vec()?)
    }

    /// Streams a file to `dest`.
    pub fn download_to(&self, id: &str, dest: &Path) -> Result<()> {
        let r = agent()
            .get(url(&format!("{FILES_URL}/{id}")))
            .header("Authorization", self.auth())
            .query("alt", "media")
            .call()?;
        let r = self.check(r)?;
        let tmp = dest.with_extension("partial");
        if let Some(d) = dest.parent() {
            fs::create_dir_all(d)?;
        }
        let mut f = fs::File::create(&tmp)?;
        std::io::copy(&mut r.into_body().into_reader(), &mut f)?;
        f.sync_data()?;
        fs::rename(tmp, dest)?;
        Ok(())
    }

    /// Creates `name` in the app data folder with `bytes`.
    pub fn create(&self, name: &str, bytes: &[u8], mime: &str) -> Result<DriveFile> {
        let boundary = format!("gbook{}", random_token(12).replace(['-', '_'], "x"));
        let meta = json!({ "name": name, "parents": ["appDataFolder"] }).to_string();
        let mut body = Vec::with_capacity(bytes.len() + 512);
        write!(
            body,
            "--{boundary}\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n{meta}\r\n--{boundary}\r\nContent-Type: {mime}\r\n\r\n"
        )?;
        body.extend_from_slice(bytes);
        write!(body, "\r\n--{boundary}--\r\n")?;
        let r = agent()
            .post(url(UPLOAD_URL))
            .header("Authorization", self.auth())
            .header(
                "Content-Type",
                format!("multipart/related; boundary={boundary}"),
            )
            .query("uploadType", "multipart")
            .query("fields", FILE_FIELDS)
            .send(&body)?;
        Ok(DriveFile::of(&json_of(self.check(r)?)?))
    }

    /// Replaces the contents of file `id`.
    pub fn update(&self, id: &str, bytes: &[u8], mime: &str) -> Result<DriveFile> {
        let r = agent()
            .patch(url(&format!("{UPLOAD_URL}/{id}")))
            .header("Authorization", self.auth())
            .header("Content-Type", mime)
            .query("uploadType", "media")
            .query("fields", FILE_FIELDS)
            .send(bytes)?;
        Ok(DriveFile::of(&json_of(self.check(r)?)?))
    }

    /// Uploads the file at `path` as `name`, in one resumable session so large books stream from disk.
    pub fn upload_file(&self, name: &str, path: &Path, mime: &str) -> Result<DriveFile> {
        let f = fs::File::open(path)?;
        let len = f.metadata()?.len();
        let meta = json!({ "name": name, "parents": ["appDataFolder"] }).to_string();
        let r = agent()
            .post(url(UPLOAD_URL))
            .header("Authorization", self.auth())
            .header("Content-Type", "application/json; charset=UTF-8")
            .header("X-Upload-Content-Type", mime)
            .header("X-Upload-Content-Length", len.to_string())
            .query("uploadType", "resumable")
            .query("fields", FILE_FIELDS)
            .send(meta.as_str())?;
        let r = self.check(r)?;
        let session = r
            .headers()
            .get("location")
            .and_then(|v| v.to_str().ok())
            .ok_or_else(|| Error::Failed("Google Drive didn't start the upload".into()))?
            .to_string();
        let r = agent().put(&session).header("Content-Type", mime).send(f)?;
        Ok(DriveFile::of(&json_of(self.check(r)?)?))
    }

    pub fn delete(&self, id: &str) -> Result<()> {
        let r = agent()
            .delete(url(&format!("{FILES_URL}/{id}")))
            .header("Authorization", self.auth())
            .call()?;
        if r.status().as_u16() == 404 {
            return Ok(());
        }
        self.check(r).map(|_| ())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Needs the network: Google answers a bogus token with an error, which shows TLS works.
    #[test]
    #[ignore]
    fn reaches_google() {
        let d = Drive {
            access: "bogus".into(),
        };
        match d.list() {
            Err(Error::Failed(m)) => assert!(m.starts_with("Google Drive:"), "{m}"),
            r => panic!("unexpected {:?}", r.map(|v| v.len())),
        }
    }

    #[test]
    fn percent_coding() {
        assert_eq!(percent_decode("4%2F0Ab_x-y"), "4/0Ab_x-y");
        assert_eq!(percent_decode("a+b%"), "a b%");
        assert_eq!(
            percent_encode("http://127.0.0.1:80"),
            "http%3A%2F%2F127.0.0.1%3A80"
        );
    }
}
