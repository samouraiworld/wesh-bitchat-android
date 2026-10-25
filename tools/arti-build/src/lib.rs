use jni::objects::{GlobalRef, JClass, JObject, JString};
use jni::sys::{jint, jlong, jstring};
use jni::JNIEnv;
use jni::JavaVM;

use arti_client::config::{BoolOrAuto, TorClientConfigBuilder};
use arti_client::{IsolationToken, StreamPrefs, TorClient, TorClientConfig};
use tor_rtcompat::PreferredRuntime;

use anyhow::Result;
use std::collections::HashMap;
use std::path::PathBuf;
use std::sync::{Arc, Mutex};
use std::time::Duration;

// ============================================================================
// Global State
// ============================================================================

/// Everything one start owns. Stopping drops all of it: no client, no runtime, no task.
struct Session {
    runtime: tokio::runtime::Runtime,
    client: Arc<TorClient<PreferredRuntime>>,
    isolation: Arc<DestinationIsolation>,
    socks_task: Option<tokio::task::JoinHandle<()>>,
}

/// Per-Arti-session circuit-isolation groups. Every normalized destination host gets one stable
/// token, so reconnects to one relay may reuse circuits while distinct relays never share one.
/// The whole registry is dropped with the session and is therefore reset on every restart.
struct DestinationIsolation {
    tokens: Mutex<HashMap<String, IsolationToken>>,
}

impl DestinationIsolation {
    fn new() -> Self {
        Self {
            tokens: Mutex::new(HashMap::new()),
        }
    }

    /// Fail closed if the registry cannot be used. There is deliberately no unisolated token.
    fn token_for(&self, normalized_host: &str) -> Result<IsolationToken> {
        let mut tokens = self
            .tokens
            .lock()
            .map_err(|_| anyhow::anyhow!("destination isolation registry unavailable"))?;
        if !tokens.contains_key(normalized_host) && tokens.len() >= MAX_DESTINATION_ISOLATION_GROUPS
        {
            return Err(anyhow::anyhow!("destination isolation registry full"));
        }
        Ok(*tokens
            .entry(normalized_host.to_owned())
            .or_insert_with(IsolationToken::new))
    }
}

/// The current session, if any. A new start or a stop always replaces it.
static SESSION: lifecycle::Lifecycle<Session> = lifecycle::Lifecycle::new();

/// Global JavaVM reference (cached on first JNI call)
static JAVA_VM: Mutex<Option<JavaVM>> = Mutex::new(None);

/// Global log callback reference
static LOG_CALLBACK: Mutex<Option<GlobalRef>> = Mutex::new(None);

/// How long a stop waits for the runtime's worker and blocking threads.
const SHUTDOWN_TIMEOUT: Duration = Duration::from_secs(3);

/// How often an in-progress bootstrap checks whether a stop cancelled it.
const CANCEL_POLL: Duration = Duration::from_millis(50);

/// Longest a SOCKS client may take for its greeting, authentication and request. Our own
/// clients answer at once over loopback; a short limit keeps stalled sockets from holding places.
const SOCKS_HANDSHAKE_TIMEOUT: Duration = Duration::from_secs(3);

/// Concurrent authenticated SOCKS connections. More are refused, so no local app can exhaust
/// our file descriptors.
const MAX_SOCKS_CONNECTIONS: usize = 64;

/// Concurrent connections still in their handshake. Kept apart and small, so sockets that never
/// authenticate cannot take the places of authenticated ones.
const MAX_SOCKS_HANDSHAKES: usize = 16;

/// Bound the per-session relay registry. Exceeding it is refused rather than routed without
/// isolation or allowed to grow native memory without limit.
const MAX_DESTINATION_ISOLATION_GROUPS: usize = 256;

/// Pause after a failed accept (for example out of file descriptors) before accepting again.
const ACCEPT_RETRY: Duration = Duration::from_millis(500);

/// At most one log line per interval for refused connections, so a burst cannot flood the logs.
const REFUSAL_LOG_INTERVAL: Duration = Duration::from_secs(1);

/// Wesh is a Tor client, with no provisioned Tor private keys. The upstream `keymgr`
/// feature exposes the storage setter; it does not mean the store should be opened.
fn client_config(state_dir: PathBuf, cache_dir: PathBuf) -> Result<TorClientConfig> {
    let mut builder = TorClientConfigBuilder::from_directories(state_dir, cache_dir);
    builder
        .storage()
        .keystore()
        .enabled(BoolOrAuto::Explicit(false));
    let config = builder.build()?;
    require_disabled_keystore(&config)?;
    Ok(config)
}

/// Refuse both implicit defaults and an enabled store before any bootstrap can start.
fn require_disabled_keystore(config: &TorClientConfig) -> Result<()> {
    let keystore = config.keystore();
    if keystore.enabled().as_bool() != Some(false) || keystore.is_enabled() {
        anyhow::bail!("Tor keystore must be explicitly disabled");
    }
    Ok(())
}

/// Aborts the SOCKS task, drops the client inside its runtime, then shuts the runtime down,
/// which ends every Arti background task (circuits, guards, directory fetches). False when the
/// runtime did not finish in time.
fn teardown(session: Session) -> bool {
    let Session {
        runtime,
        client,
        isolation,
        socks_task,
    } = session;
    if let Some(task) = socks_task {
        task.abort();
    }
    drop(isolation);
    {
        let _context = runtime.enter();
        drop(client);
    }
    shut_down(runtime)
}

/// Dropping a runtime waits for every task and thread it owns. False when that did not finish
/// within SHUTDOWN_TIMEOUT: Tor work may still run, so the stop must be reported as failed.
fn shut_down(runtime: tokio::runtime::Runtime) -> bool {
    deadline::finishes_within(SHUTDOWN_TIMEOUT, move || drop(runtime))
}

/// Records a teardown that did not finish; every later stop then reports a failure.
fn note_teardown(clean: bool) {
    if !clean {
        SESSION.mark_unclean();
    }
}

// ============================================================================
// Logging Integration
// ============================================================================

/// Send log message to Java callback
fn send_log_to_java(message: String) {
    let vm_opt = JAVA_VM.lock().unwrap();
    let callback_opt = LOG_CALLBACK.lock().unwrap();

    if let (Some(vm), Some(callback)) = (vm_opt.as_ref(), callback_opt.as_ref()) {
        if let Ok(mut env) = vm.attach_current_thread() {
            if let Ok(jmessage) = env.new_string(&message) {
                let _ = env.call_method(
                    callback.as_obj(),
                    "onLogLine",
                    "(Ljava/lang/String;)V",
                    &[(&jmessage).into()],
                );
            }
        }
    }
}

/// Macro for logging to both Android logcat and Java callback
macro_rules! log_info {
    ($($arg:tt)*) => {{
        let msg = format!($($arg)*);
        android_logger::log(&format!("Arti: {}", msg));
        send_log_to_java(msg);
    }};
}

macro_rules! log_error {
    ($($arg:tt)*) => {{
        let msg = format!("ERROR: {}", format!($($arg)*));
        android_logger::log(&format!("Arti: {}", msg));
        send_log_to_java(msg);
    }};
}

// ============================================================================
// JNI Functions
// ============================================================================

/// Get Arti version string
#[no_mangle]
pub extern "C" fn Java_org_torproject_arti_ArtiNative_getVersion(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    // Cache JavaVM on first call
    if JAVA_VM.lock().unwrap().is_none() {
        if let Ok(vm) = env.get_java_vm() {
            *JAVA_VM.lock().unwrap() = Some(vm);
        }
    }

    let version = format!(
        "Arti {} (custom build with rustls)",
        env!("CARGO_PKG_VERSION")
    );
    let output = env
        .new_string(version)
        .expect("Couldn't create java string!");
    output.into_raw()
}

/// Set log callback for Arti logs
#[no_mangle]
pub extern "C" fn Java_org_torproject_arti_ArtiNative_setLogCallback(
    env: JNIEnv,
    _class: JClass,
    callback: JObject,
) {
    // Cache JavaVM if not already cached
    if JAVA_VM.lock().unwrap().is_none() {
        if let Ok(vm) = env.get_java_vm() {
            *JAVA_VM.lock().unwrap() = Some(vm);
        }
    }

    // Store global reference to callback
    if let Ok(global_ref) = env.new_global_ref(callback) {
        *LOG_CALLBACK.lock().unwrap() = Some(global_ref);
        log_info!("Log callback registered");
    }
}

/// The stop generation. A start reads it before its first native call and passes it to
/// initialize(), so a stop that completes in between still cancels that start.
#[no_mangle]
pub extern "C" fn Java_org_torproject_arti_ArtiNative_generation(
    _env: JNIEnv,
    _class: JClass,
) -> jlong {
    SESSION.generation() as jlong
}

/// Initialize Arti: a fresh runtime and a freshly bootstrapped client on every call.
///
/// Returns 0 on success, -4 when a stop came after `generation` was read or cancelled the bootstrap,
/// -5 when an earlier runtime did not stop in time: Tor work may still run, so nothing starts.
#[no_mangle]
pub extern "C" fn Java_org_torproject_arti_ArtiNative_initialize(
    mut env: JNIEnv,
    _class: JClass,
    data_dir: JString,
    generation: jlong,
) -> jint {
    // Cache JavaVM if not already cached
    if JAVA_VM.lock().unwrap().is_none() {
        if let Ok(vm) = env.get_java_vm() {
            *JAVA_VM.lock().unwrap() = Some(vm);
        }
    }

    let data_dir_str: String = match env.get_string(&data_dir) {
        Ok(s) => s.into(),
        Err(e) => {
            log_error!("Failed to convert data_dir: {:?}", e);
            return -1;
        }
    };

    log_info!("AMEx: state changed to Initialized");
    log_info!("Initializing Arti with data directory: {}", data_dir_str);

    // Waits out a stop in progress. A client left by an earlier start is released first:
    // a start never reuses guards or circuits from before.
    let (start, previous) = SESSION.begin_start(generation as u64);
    if let Some(session) = previous {
        log_info!("Releasing the previous Tor client");
        note_teardown(teardown(session));
    }
    if SESSION.is_unclean() {
        log_error!(
            "A Tor runtime did not stop within {:?}; not starting",
            SHUTDOWN_TIMEOUT
        );
        return -5;
    }
    if start.is_cancelled() {
        log_info!("Arti start cancelled by an earlier stop");
        return -4;
    }

    let runtime = match tokio::runtime::Builder::new_multi_thread()
        .enable_all()
        .build()
    {
        Ok(rt) => rt,
        Err(e) => {
            log_error!("Failed to create Tokio runtime: {:?}", e);
            return -2;
        }
    };

    // Create config with explicit Android paths
    let data_path = PathBuf::from(data_dir_str);
    let cache_dir = data_path.join("cache");
    let state_dir = data_path.join("state");

    // Create directories if they don't exist
    std::fs::create_dir_all(&cache_dir).ok();
    std::fs::create_dir_all(&state_dir).ok();

    let bootstrap = async {
        log_info!("Creating Arti client...");
        log_info!("Cache dir: {:?}", cache_dir);
        log_info!("State dir: {:?}", state_dir);

        // Create config with Android-specific directories
        let config = client_config(state_dir, cache_dir)?;

        // Create client with Android-specific config
        let client = TorClient::create_bootstrapped(config).await?;
        Ok::<_, anyhow::Error>(client)
    };

    // A stop() during bootstrap wins: the bootstrap future is dropped within CANCEL_POLL.
    let outcome = runtime.block_on(async {
        tokio::select! {
            result = bootstrap => Some(result),
            _ = wait_until_cancelled(&start) => None,
        }
    });

    // `start` stays held until the end of this function, so a waiting stop() returns only
    // once the runtime below is gone.
    match outcome {
        Some(Ok(client)) => {
            let session = Session {
                runtime,
                client: Arc::new(client),
                isolation: Arc::new(DestinationIsolation::new()),
                socks_task: None,
            };
            match start.install(session) {
                Ok(()) => {
                    log_info!("Arti client created successfully");
                    log_info!("Arti initialized successfully");
                    0
                }
                Err(session) => {
                    log_info!("Arti stopped during bootstrap; client released");
                    note_teardown(teardown(session));
                    -4
                }
            }
        }
        Some(Err(e)) => {
            log_error!("Failed to initialize Arti: {:?}", e);
            note_teardown(shut_down(runtime));
            -3
        }
        None => {
            log_info!("Arti bootstrap cancelled by stop");
            note_teardown(shut_down(runtime));
            -4
        }
    }
}

async fn wait_until_cancelled(start: &lifecycle::Start<'_, Session>) {
    while !start.is_cancelled() {
        tokio::time::sleep(CANCEL_POLL).await;
    }
}

/// Start the SOCKS proxy on 127.0.0.1:`port`, accepting only RFC 1929 username/password
/// authentication with the given per-start credentials.
#[no_mangle]
pub extern "C" fn Java_org_torproject_arti_ArtiNative_startSocksProxyWithAuth(
    mut env: JNIEnv,
    _class: JClass,
    port: jint,
    username: JString,
    password: JString,
) -> jint {
    log_info!("AMEx: state changed to Starting");
    log_info!("Starting SOCKS proxy on port {}", port);

    let username: Option<String> = env.get_string(&username).ok().map(Into::into);
    let password: Option<String> = env.get_string(&password).ok().map(Into::into);
    let credentials = match username
        .zip(password)
        .and_then(|(u, p)| socks_auth::Credentials::new(u.into_bytes(), p.into_bytes()))
    {
        Some(c) => Arc::new(c),
        None => {
            log_error!("SOCKS credentials missing or invalid");
            return -4;
        }
    };

    // Try to bind IMMEDIATELY to detect port conflicts before returning
    let addr = format!("127.0.0.1:{}", port);

    let started = SESSION.with_current(|session| {
        // Stop any existing SOCKS server first
        if let Some(handle) = session.socks_task.take() {
            log_info!("Aborting previous SOCKS server task");
            handle.abort();
        }

        let listener = match session
            .runtime
            .block_on(tokio::net::TcpListener::bind(&addr))
        {
            Ok(l) => {
                log_info!("SOCKS proxy bound to {}", addr);
                l
            }
            Err(e) => {
                log_error!("Failed to bind SOCKS proxy to {}: {:?}", addr, e);
                return -3;
            }
        };

        let client = Arc::clone(&session.client);
        let isolation = Arc::clone(&session.isolation);
        let addr = addr.clone();
        // Now spawn the background task with the already-bound listener
        let handle = session.runtime.spawn(async move {
            log_info!("SOCKS proxy listening on {}", addr);
            log_info!("Sufficiently bootstrapped; system SOCKS now functional");

            // Signal bootstrap completion to bitchat-android (expected by ArtiTorManager)
            // This sets bootstrapPercent to 100% and stops inactivity restarts
            tokio::time::sleep(tokio::time::Duration::from_millis(500)).await;
            log_info!("We have found that guard [scrubbed] is usable.");

            // Accept connections. Logs name a connection by a counter only, never its destination.
            let handshakes = Arc::new(tokio::sync::Semaphore::new(MAX_SOCKS_HANDSHAKES));
            let slots = Arc::new(tokio::sync::Semaphore::new(MAX_SOCKS_CONNECTIONS));
            let mut next_id: u64 = 0;
            let mut refused: u64 = 0;
            let mut last_refusal_log: Option<std::time::Instant> = None;
            loop {
                match listener.accept().await {
                    Ok((stream, _peer_addr)) => {
                        next_id += 1;
                        let id = next_id;
                        let Ok(handshake) = Arc::clone(&handshakes).try_acquire_owned() else {
                            drop(stream);
                            refused += 1;
                            if last_refusal_log
                                .map_or(true, |t| t.elapsed() >= REFUSAL_LOG_INTERVAL)
                            {
                                log_error!(
                                    "SOCKS: {} connections refused, too many handshakes",
                                    refused
                                );
                                refused = 0;
                                last_refusal_log = Some(std::time::Instant::now());
                            }
                            continue;
                        };
                        let client_clone = Arc::clone(&client);
                        let isolation = Arc::clone(&isolation);
                        let credentials = Arc::clone(&credentials);
                        let slots = Arc::clone(&slots);

                        tokio::spawn(async move {
                            if let Err(e) = handle_socks_connection(
                                id,
                                stream,
                                client_clone,
                                isolation,
                                credentials,
                                handshake,
                                slots,
                            )
                            .await
                            {
                                log_error!("SOCKS connection {}: {}", id, e);
                            }
                        });
                    }
                    Err(e) => {
                        // Keep serving: a transient error such as EMFILE must not end the proxy.
                        log_error!("Failed to accept SOCKS connection: {:?}", e);
                        tokio::time::sleep(ACCEPT_RETRY).await;
                    }
                }
            }
        });

        // Store handle for cleanup
        session.socks_task = Some(handle);
        0
    });

    match started {
        Some(0) => {
            log_info!("SOCKS proxy started on port {}", port);
            0
        }
        Some(code) => code,
        None => {
            log_error!("Arti client not initialized - call initialize() first");
            -1
        }
    }
}

/// Handle a single SOCKS connection. `handshake` is held until the request is read; a place
/// in `slots` is taken only after authentication.
async fn handle_socks_connection(
    id: u64,
    mut stream: tokio::net::TcpStream,
    client: Arc<TorClient<PreferredRuntime>>,
    isolation: Arc<DestinationIsolation>,
    credentials: Arc<socks_auth::Credentials>,
    handshake: tokio::sync::OwnedSemaphorePermit,
    slots: Arc<tokio::sync::Semaphore>,
) -> Result<()> {
    use tokio::io::AsyncWriteExt;

    // A client that stalls before its request never holds a handshake place for long.
    let (target_host, target_port) = match tokio::time::timeout(
        SOCKS_HANDSHAKE_TIMEOUT,
        socks_handshake(&mut stream, &credentials),
    )
    .await
    {
        Ok(target) => target?,
        Err(_) => return Err(anyhow::anyhow!("SOCKS handshake timed out")),
    };
    drop(handshake);

    let Ok(_slot) = slots.try_acquire_owned() else {
        // General failure: the client may retry once a connection closes.
        stream
            .write_all(&[0x05, 0x01, 0x00, 0x01, 0, 0, 0, 0, 0, 0])
            .await?;
        return Err(anyhow::anyhow!("SOCKS connection limit reached"));
    };

    log_info!("SOCKS connection {}: CONNECT", id);

    // Reconnects to one normalized relay share a token; distinct relays can never share a circuit.
    // Registry failure is fatal: never fall back to an unisolated or direct connection.
    let isolation_token = match isolation.token_for(&target_host) {
        Ok(token) => token,
        Err(error) => {
            stream
                .write_all(&[0x05, 0x01, 0x00, 0x01, 0, 0, 0, 0, 0, 0])
                .await?;
            return Err(error);
        }
    };
    let mut prefs = StreamPrefs::new();
    prefs.set_isolation(isolation_token);

    // Establish Tor connection. The Arti error may name the destination, so it is not logged.
    // `connect_with_prefs` is the only outbound path in this proxy; failure stays fail-closed.
    let tor_stream = match client
        .connect_with_prefs((target_host.as_str(), target_port), &prefs)
        .await
    {
        Ok(s) => s,
        Err(_) => {
            // Send SOCKS5 error: general failure
            stream
                .write_all(&[0x05, 0x05, 0x00, 0x01, 0, 0, 0, 0, 0, 0])
                .await?;
            return Err(anyhow::anyhow!("Failed to connect through Tor"));
        }
    };

    log_info!("SOCKS connection {}: Tor stream established", id);

    // Send SOCKS5 success response
    stream
        .write_all(&[0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0])
        .await?;

    // Bidirectional data forwarding
    let (mut client_read, mut client_write) = stream.split();
    let (mut tor_read, mut tor_write) = tor_stream.split();

    let client_to_tor = async { tokio::io::copy(&mut client_read, &mut tor_write).await };

    let tor_to_client = async { tokio::io::copy(&mut tor_read, &mut client_write).await };

    // Run both directions concurrently, exit when either completes
    tokio::select! {
        result = client_to_tor => {
            if let Err(ref e) = result {
                log_error!("Client->Tor copy error: {:?}", e.kind());
            }
        }
        result = tor_to_client => {
            if let Err(ref e) = result {
                log_error!("Tor->Client copy error: {:?}", e.kind());
            }
        }
    };

    log_info!("SOCKS connection {}: closed", id);

    Ok(())
}

/// Greeting, username/password authentication and CONNECT request; returns the target.
async fn socks_handshake<S>(
    stream: &mut S,
    credentials: &socks_auth::Credentials,
) -> Result<(String, u16)>
where
    S: tokio::io::AsyncRead + tokio::io::AsyncWrite + Unpin,
{
    use tokio::io::{AsyncReadExt, AsyncWriteExt};

    // Greeting: VER NMETHODS METHODS. Only username/password is accepted, so another app
    // on the phone cannot use this port without the per-start credentials.
    let mut head = [0u8; 2];
    stream.read_exact(&mut head).await?;
    if head[0] != 0x05 {
        return Err(anyhow::anyhow!("Unsupported SOCKS version: {}", head[0]));
    }
    let mut methods = vec![0u8; head[1] as usize];
    stream.read_exact(&mut methods).await?;
    let method = socks_auth::choose_method(&methods);
    stream.write_all(&[0x05, method]).await?;
    if method != socks_auth::USER_PASS {
        return Err(anyhow::anyhow!("SOCKS client offered no username/password"));
    }

    // RFC 1929: VER(1)=1 ULEN UNAME PLEN PASSWD
    let mut auth_head = [0u8; 2];
    stream.read_exact(&mut auth_head).await?;
    if auth_head[0] != 0x01 {
        return Err(anyhow::anyhow!(
            "Unsupported SOCKS auth version: {}",
            auth_head[0]
        ));
    }
    let mut user = vec![0u8; auth_head[1] as usize];
    stream.read_exact(&mut user).await?;
    let mut pass_len = [0u8; 1];
    stream.read_exact(&mut pass_len).await?;
    let mut pass = vec![0u8; pass_len[0] as usize];
    stream.read_exact(&mut pass).await?;
    let accepted = credentials.accepts(&user, &pass);
    stream
        .write_all(&[0x01, if accepted { 0x00 } else { 0x01 }])
        .await?;
    if !accepted {
        return Err(anyhow::anyhow!("SOCKS authentication failed"));
    }

    socks_request(stream).await
}

/// Read one SOCKS5 CONNECT request without assuming any TCP packet boundaries.
/// Only domain-name targets are accepted: IP literals would create aliases for one relay and
/// invite callers to resolve names outside Tor before reaching this proxy.
async fn socks_request<S>(stream: &mut S) -> Result<(String, u16)>
where
    S: tokio::io::AsyncRead + tokio::io::AsyncWrite + Unpin,
{
    use tokio::io::{AsyncReadExt, AsyncWriteExt};

    let mut header = [0u8; 4];
    stream.read_exact(&mut header).await?;
    let [version, cmd, reserved, atyp] = header;

    if version != 0x05 || reserved != 0x00 {
        return Err(anyhow::anyhow!("Unsupported SOCKS version: {}", version));
    }

    if cmd != 0x01 {
        // Only support CONNECT command
        stream
            .write_all(&[0x05, 0x07, 0x00, 0x01, 0, 0, 0, 0, 0, 0])
            .await?;
        return Err(anyhow::anyhow!("Unsupported SOCKS command: {}", cmd));
    }

    match atyp {
        0x03 => {
            let mut length = [0u8; 1];
            stream.read_exact(&mut length).await?;
            let mut domain = vec![0u8; length[0] as usize];
            stream.read_exact(&mut domain).await?;
            let mut port = [0u8; 2];
            stream.read_exact(&mut port).await?;
            let Some(domain) = socks_auth::normalized_domain(&domain) else {
                stream
                    .write_all(&[0x05, 0x01, 0x00, 0x01, 0, 0, 0, 0, 0, 0])
                    .await?;
                return Err(anyhow::anyhow!("Invalid domain name"));
            };
            let port = u16::from_be_bytes(port);
            if port == 0 {
                stream
                    .write_all(&[0x05, 0x01, 0x00, 0x01, 0, 0, 0, 0, 0, 0])
                    .await?;
                return Err(anyhow::anyhow!("Port zero is not allowed"));
            }
            Ok((domain, port))
        }
        // Refuse IPv4 and IPv6, including textual IP aliases sent as domain names below.
        0x01 | 0x04 => {
            stream
                .write_all(&[0x05, 0x08, 0x00, 0x01, 0, 0, 0, 0, 0, 0])
                .await?;
            Err(anyhow::anyhow!("IP address targets are not allowed"))
        }
        _ => {
            stream
                .write_all(&[0x05, 0x08, 0x00, 0x01, 0, 0, 0, 0, 0, 0])
                .await?;
            Err(anyhow::anyhow!("Unsupported address type: {}", atyp))
        }
    }
}

/// Stop Arti: cancels a bootstrap in progress, then drops the client and shuts its runtime
/// down. When this returns 0 no Tor task is left, so the data directory can be deleted and the
/// next initialize() builds a new client with new guards. Returns -5 when a runtime did not
/// stop in time, now or earlier in this process: Tor work may still run.
#[no_mangle]
pub extern "C" fn Java_org_torproject_arti_ArtiNative_stop(_env: JNIEnv, _class: JClass) -> jint {
    log_info!("AMEx: state changed to Stopping");
    log_info!("Stopping Arti...");

    let mut stop = SESSION.begin_stop();
    if let Some(session) = stop.take() {
        log_info!("Releasing the Tor client");
        note_teardown(teardown(session));
    }
    drop(stop);

    log_info!("AMEx: state changed to Stopped");
    if SESSION.is_unclean() {
        log_error!("A Tor runtime did not stop within {:?}", SHUTDOWN_TIMEOUT);
        return -5;
    }
    log_info!("Arti stopped successfully");

    0
}

// ============================================================================
// Android Logger (simple implementation)
// ============================================================================

mod android_logger {
    use std::ffi::CString;

    #[allow(non_camel_case_types)]
    type c_int = i32;

    #[allow(non_camel_case_types)]
    type c_char = i8;

    extern "C" {
        fn __android_log_write(prio: c_int, tag: *const c_char, text: *const c_char) -> c_int;
    }

    const ANDROID_LOG_INFO: c_int = 4;

    pub fn log(message: &str) {
        unsafe {
            let tag = CString::new("ArtiNative").unwrap();
            // A NUL byte (from a peer-supplied host name, say) must never panic across JNI.
            let Ok(text) = CString::new(message.replace('\0', "\\0")) else {
                return;
            };
            __android_log_write(
                ANDROID_LOG_INFO,
                tag.as_ptr() as *const c_char,
                text.as_ptr() as *const c_char,
            );
        }
    }
}

// The host tests never run Android logging. Keep the Android symbol shim test-only;
// it is absent from every packaged library and does not add a JNI entry point.
#[cfg(all(test, not(target_os = "android")))]
#[export_name = "__android_log_write"]
pub extern "C" fn host_test_android_log_write(
    _priority: std::ffi::c_int,
    _tag: *const std::ffi::c_char,
    _text: *const std::ffi::c_char,
) -> std::ffi::c_int {
    0
}

#[cfg(test)]
mod keystore_policy_tests {
    use super::{client_config, require_disabled_keystore};
    use arti_client::{
        config::{BoolOrAuto, TorClientConfigBuilder},
        TorClient,
    };
    use std::path::PathBuf;
    use std::sync::atomic::{AtomicU64, Ordering};

    struct SyntheticDirectory(PathBuf);

    impl SyntheticDirectory {
        fn new() -> Self {
            static NEXT: AtomicU64 = AtomicU64::new(0);
            let path = std::env::temp_dir().join(format!(
                "wesh-keystore-policy-{}-{}",
                std::process::id(),
                NEXT.fetch_add(1, Ordering::Relaxed),
            ));
            std::fs::create_dir(&path).unwrap();
            Self(path)
        }
    }

    impl Drop for SyntheticDirectory {
        fn drop(&mut self) {
            std::fs::remove_dir_all(&self.0).unwrap();
        }
    }

    #[tokio::test]
    async fn inert_client_does_not_create_a_keystore() {
        let directory = SyntheticDirectory::new();
        let state = directory.0.join("state");
        let cache = directory.0.join("cache");
        let config = client_config(state.clone(), cache).unwrap();
        assert_eq!(config.keystore().enabled().as_bool(), Some(false));
        assert!(!config.keystore().is_enabled());
        assert!(config.keystore().primary_kind().is_none());

        // Upstream guarantees this public API never connects to the network.
        let client = TorClient::builder().config(config).create_inert().unwrap();
        assert!(!state.join("keystore").exists());
        drop(client);
        assert!(!state.join("keystore").exists());
    }

    #[tokio::test]
    async fn inert_client_ignores_an_unusable_synthetic_keystore_path() {
        let directory = SyntheticDirectory::new();
        let state = directory.0.join("state");
        let cache = directory.0.join("cache");
        std::fs::create_dir(&state).unwrap();
        let sentinel = state.join("keystore");
        let contents = b"synthetic sentinel, not a keystore or private key";
        std::fs::write(&sentinel, contents).unwrap();
        let config = client_config(state, cache).unwrap();

        // Opening this path as a native keystore would fail because it is a file.
        let client = TorClient::builder().config(config).create_inert().unwrap();
        drop(client);
        assert!(sentinel.is_file());
        assert_eq!(std::fs::read(sentinel).unwrap(), contents);
    }

    #[test]
    fn implicit_default_is_refused_before_bootstrap() {
        let config = TorClientConfigBuilder::default().build().unwrap();
        assert!(require_disabled_keystore(&config).is_err());
    }

    #[test]
    fn explicitly_enabled_keystore_is_refused_before_bootstrap() {
        let mut builder = TorClientConfigBuilder::default();
        builder
            .storage()
            .keystore()
            .enabled(BoolOrAuto::Explicit(true));
        let config = builder.build().unwrap();
        assert!(require_disabled_keystore(&config).is_err());
    }
}

// ============================================================================
// Start/stop bookkeeping (std only: no Arti, Tokio or JNI, so it is testable alone)
// ============================================================================

mod lifecycle {
    use std::sync::{Condvar, Mutex, MutexGuard, PoisonError};

    struct Inner<R> {
        /// Bumped by every stop; a start that sees it change has been cancelled.
        generation: u64,
        /// A start or stop is in progress; the other side waits.
        busy: bool,
        current: Option<R>,
        /// A teardown did not finish in time. Never cleared: its threads may still run.
        unclean: bool,
    }

    /// Holds at most one session. Starts and stops never overlap, and a stop cancels a start
    /// in progress instead of waiting for it to finish.
    pub struct Lifecycle<R> {
        inner: Mutex<Inner<R>>,
        changed: Condvar,
    }

    impl<R> Lifecycle<R> {
        pub const fn new() -> Self {
            Self {
                inner: Mutex::new(Inner {
                    generation: 0,
                    busy: false,
                    current: None,
                    unclean: false,
                }),
                changed: Condvar::new(),
            }
        }

        fn lock(&self) -> MutexGuard<'_, Inner<R>> {
            self.inner.lock().unwrap_or_else(PoisonError::into_inner)
        }

        fn wait_idle<'a>(
            &'a self,
            mut inner: MutexGuard<'a, Inner<R>>,
        ) -> MutexGuard<'a, Inner<R>> {
            while inner.busy {
                inner = self
                    .changed
                    .wait(inner)
                    .unwrap_or_else(PoisonError::into_inner);
            }
            inner
        }

        fn release(&self) {
            self.lock().busy = false;
            self.changed.notify_all();
        }

        /// The stop generation, read by a start before it waits for anything.
        pub fn generation(&self) -> u64 {
            self.lock().generation
        }

        /// Waits out any other start or stop, then returns the previous session for teardown:
        /// every start builds a fresh one. `generation` was read before the caller began, so any
        /// stop since then, even one this start queued behind, leaves it cancelled.
        pub fn begin_start(&self, generation: u64) -> (Start<'_, R>, Option<R>) {
            let mut inner = self.wait_idle(self.lock());
            inner.busy = true;
            let previous = inner.current.take();
            (
                Start {
                    owner: self,
                    generation,
                },
                previous,
            )
        }

        pub fn mark_unclean(&self) {
            self.lock().unclean = true;
        }

        pub fn is_unclean(&self) -> bool {
            self.lock().unclean
        }

        /// Cancels a start in progress, waits for it to return, and takes the current session.
        pub fn begin_stop(&self) -> Stop<'_, R> {
            let mut inner = self.lock();
            inner.generation = inner.generation.wrapping_add(1);
            let mut inner = self.wait_idle(inner);
            inner.busy = true;
            let current = inner.current.take();
            Stop {
                owner: self,
                current,
            }
        }

        /// Runs `f` on the current session; None when there is none.
        pub fn with_current<T>(&self, f: impl FnOnce(&mut R) -> T) -> Option<T> {
            self.lock().current.as_mut().map(f)
        }
    }

    /// A start in progress. Dropping it lets a waiting stop proceed.
    pub struct Start<'a, R> {
        owner: &'a Lifecycle<R>,
        generation: u64,
    }

    impl<R> Start<'_, R> {
        pub fn is_cancelled(&self) -> bool {
            self.owner.lock().generation != self.generation
        }

        /// Publishes the session unless a stop came in meanwhile; then it is handed back.
        pub fn install(&self, session: R) -> Result<(), R> {
            let mut inner = self.owner.lock();
            if inner.generation != self.generation {
                return Err(session);
            }
            inner.current = Some(session);
            Ok(())
        }
    }

    impl<R> Drop for Start<'_, R> {
        fn drop(&mut self) {
            self.owner.release();
        }
    }

    /// A stop in progress: holds the session it took until torn down.
    pub struct Stop<'a, R> {
        owner: &'a Lifecycle<R>,
        current: Option<R>,
    }

    impl<R> Stop<'_, R> {
        pub fn take(&mut self) -> Option<R> {
            self.current.take()
        }
    }

    impl<R> Drop for Stop<'_, R> {
        fn drop(&mut self) {
            self.owner.release();
        }
    }

    #[cfg(test)]
    mod tests {
        use super::Lifecycle;
        use std::sync::mpsc;
        use std::sync::Arc;
        use std::thread;
        use std::time::{Duration, Instant};

        #[test]
        fn start_stop_start_builds_a_new_session() {
            let life = Lifecycle::new();
            let (start, previous) = life.begin_start(life.generation());
            assert!(previous.is_none());
            assert!(start.install(1).is_ok());
            drop(start);

            let mut stop = life.begin_stop();
            assert_eq!(stop.take(), Some(1));
            drop(stop);
            assert_eq!(life.with_current(|s| *s), None);

            let (start, previous) = life.begin_start(life.generation());
            assert!(
                previous.is_none(),
                "the stopped session is never handed to the next start"
            );
            assert!(!start.is_cancelled());
            assert!(start.install(2).is_ok());
            drop(start);
            assert_eq!(life.with_current(|s| *s), Some(2));
        }

        #[test]
        fn a_start_without_stop_still_releases_the_previous_session() {
            let life = Lifecycle::new();
            let (start, _) = life.begin_start(life.generation());
            start.install(1).unwrap();
            drop(start);
            let (start, previous) = life.begin_start(life.generation());
            assert_eq!(previous, Some(1));
            start.install(2).unwrap();
            drop(start);
            assert_eq!(life.with_current(|s| *s), Some(2));
        }

        #[test]
        fn stop_during_start_returns_promptly_and_the_start_is_refused() {
            let life = Arc::new(Lifecycle::new());
            let (started_tx, started_rx) = mpsc::channel();
            let worker = {
                let life = Arc::clone(&life);
                thread::spawn(move || {
                    let (start, _) = life.begin_start(life.generation());
                    started_tx.send(()).unwrap();
                    // A bootstrap that would take a minute unless cancelled.
                    let deadline = Instant::now() + Duration::from_secs(60);
                    while !start.is_cancelled() && Instant::now() < deadline {
                        thread::sleep(Duration::from_millis(5));
                    }
                    start.install(7)
                })
            };
            started_rx.recv().unwrap();
            let began = Instant::now();
            let mut stop = life.begin_stop();
            assert!(
                began.elapsed() < Duration::from_secs(2),
                "stop waited for the whole bootstrap"
            );
            assert_eq!(stop.take(), None);
            drop(stop);
            assert_eq!(
                worker.join().unwrap(),
                Err(7),
                "a cancelled start must hand its session back"
            );
            assert_eq!(life.with_current(|s| *s), None);
        }

        #[test]
        fn stop_waits_for_a_cancelled_start_to_finish_its_teardown() {
            let life = Arc::new(Lifecycle::<u32>::new());
            let (started_tx, started_rx) = mpsc::channel();
            let (torn_down_tx, torn_down_rx) = mpsc::channel();
            let worker = {
                let life = Arc::clone(&life);
                thread::spawn(move || {
                    let (start, _) = life.begin_start(life.generation());
                    started_tx.send(()).unwrap();
                    while !start.is_cancelled() {
                        thread::sleep(Duration::from_millis(5));
                    }
                    thread::sleep(Duration::from_millis(50));
                    torn_down_tx.send(()).unwrap();
                })
            };
            started_rx.recv().unwrap();
            let stop = life.begin_stop();
            assert!(
                torn_down_rx.try_recv().is_ok(),
                "stop returned before the start released its runtime"
            );
            drop(stop);
            worker.join().unwrap();
        }

        #[test]
        fn a_stop_after_the_generation_was_read_cancels_the_start() {
            let life = Lifecycle::<u32>::new();
            let generation = life.generation();
            drop(life.begin_stop());
            let (start, _) = life.begin_start(generation);
            assert!(start.is_cancelled());
            assert_eq!(start.install(1), Err(1));
        }

        #[test]
        fn a_start_queued_behind_another_cannot_escape_a_stop() {
            for _ in 0..50 {
                let life = Arc::new(Lifecycle::<u32>::new());
                let (a_tx, a_rx) = mpsc::channel();
                let a = {
                    let life = Arc::clone(&life);
                    thread::spawn(move || {
                        let (start, _) = life.begin_start(life.generation());
                        a_tx.send(()).unwrap();
                        while !start.is_cancelled() {
                            thread::sleep(Duration::from_millis(1));
                        }
                    })
                };
                a_rx.recv().unwrap();
                let (b_tx, b_rx) = mpsc::channel();
                let b = {
                    let life = Arc::clone(&life);
                    thread::spawn(move || {
                        let generation = life.generation();
                        b_tx.send(()).unwrap();
                        // Queued behind A until the stop below.
                        let (start, _) = life.begin_start(generation);
                        let began = Instant::now();
                        while !start.is_cancelled() && began.elapsed() < Duration::from_secs(2) {
                            thread::sleep(Duration::from_millis(1));
                        }
                        start.is_cancelled()
                    })
                };
                b_rx.recv().unwrap();
                thread::sleep(Duration::from_millis(5));
                let began = Instant::now();
                drop(life.begin_stop());
                assert!(
                    began.elapsed() < Duration::from_secs(1),
                    "the stop waited for B's bootstrap"
                );
                a.join().unwrap();
                assert!(b.join().unwrap(), "a queued start escaped the stop");
            }
        }

        #[test]
        fn an_unclean_teardown_is_remembered() {
            let life = Lifecycle::<u32>::new();
            assert!(!life.is_unclean());
            life.mark_unclean();
            drop(life.begin_stop());
            assert!(life.is_unclean());
        }

        #[test]
        fn a_start_after_a_stop_is_not_cancelled() {
            let life = Lifecycle::new();
            drop(life.begin_stop());
            drop(life.begin_stop());
            let (start, _) = life.begin_start(life.generation());
            assert!(!start.is_cancelled());
            assert!(start.install(3).is_ok());
        }
    }
}

// ============================================================================
// Teardown deadline (std only)
// ============================================================================

mod deadline {
    use std::sync::mpsc;
    use std::time::Duration;

    /// Runs `work` on its own thread; true only if it finished within `limit`. Work that is
    /// late keeps running on that thread.
    pub fn finishes_within<F: FnOnce() + Send + 'static>(limit: Duration, work: F) -> bool {
        let (done, finished) = mpsc::channel();
        let spawned = std::thread::Builder::new()
            .name("arti-teardown".into())
            .spawn(move || {
                work();
                let _ = done.send(());
            });
        spawned.is_ok() && finished.recv_timeout(limit).is_ok()
    }

    #[cfg(test)]
    mod tests {
        use super::finishes_within;
        use std::time::Duration;

        #[test]
        fn quick_work_finishes() {
            assert!(finishes_within(Duration::from_secs(5), || {}));
        }

        #[test]
        fn late_work_is_reported() {
            assert!(!finishes_within(Duration::from_millis(50), || {
                std::thread::sleep(Duration::from_millis(500))
            }));
        }
    }
}

// ============================================================================
// SOCKS5 username/password and request rules (std only)
// ============================================================================

mod socks_auth {
    pub const USER_PASS: u8 = 0x02;
    pub const NO_ACCEPTABLE: u8 = 0xFF;

    /// Username/password (RFC 1929) is the only method accepted.
    pub fn choose_method(offered: &[u8]) -> u8 {
        if offered.contains(&USER_PASS) {
            USER_PASS
        } else {
            NO_ACCEPTABLE
        }
    }

    /// Compares without stopping at the first difference.
    fn same(expected: &[u8], given: &[u8]) -> bool {
        let mut diff = expected.len() ^ given.len();
        for (i, byte) in expected.iter().enumerate() {
            diff |= usize::from(byte ^ given.get(i).copied().unwrap_or(0));
        }
        diff == 0
    }

    pub struct Credentials {
        username: Vec<u8>,
        password: Vec<u8>,
    }

    impl Credentials {
        /// None for an empty or over-long value: an empty secret would let any local app in.
        pub fn new(username: Vec<u8>, password: Vec<u8>) -> Option<Self> {
            let valid = |v: &Vec<u8>| !v.is_empty() && v.len() <= 255;
            (valid(&username) && valid(&password)).then_some(Self { username, password })
        }

        pub fn accepts(&self, username: &[u8], password: &[u8]) -> bool {
            same(&self.username, username) & same(&self.password, password)
        }
    }

    /// Normalize one DNS destination for both Tor and circuit isolation. IDNA conversion belongs
    /// to the caller; this boundary accepts only canonicalizable ASCII DNS names, never IP text.
    pub fn normalized_domain(bytes: &[u8]) -> Option<String> {
        let name = std::str::from_utf8(bytes).ok()?;
        let without_root = name.strip_suffix('.').unwrap_or(name);
        if without_root.is_empty()
            || without_root.len() > 253
            || without_root.parse::<std::net::IpAddr>().is_ok()
        {
            return None;
        }
        let normalized = without_root.to_ascii_lowercase();
        let valid_labels = normalized.split('.').all(|label| {
            !label.is_empty()
                && label.len() <= 63
                && !label.starts_with('-')
                && !label.ends_with('-')
                && label
                    .bytes()
                    .all(|byte| byte.is_ascii_alphanumeric() || byte == b'-')
        });
        let last_label = normalized.rsplit('.').next()?;
        let has_letter = last_label.bytes().any(|byte| byte.is_ascii_alphabetic());
        let is_hex_number = last_label.strip_prefix("0x").is_some_and(|digits| {
            !digits.is_empty() && digits.bytes().all(|byte| byte.is_ascii_hexdigit())
        });
        (valid_labels && has_letter && !is_hex_number).then_some(normalized)
    }

    #[cfg(test)]
    mod tests {
        use super::*;

        #[test]
        fn only_username_password_is_offered_back() {
            assert_eq!(choose_method(&[0x00, 0x02]), USER_PASS);
            assert_eq!(choose_method(&[0x02]), USER_PASS);
            assert_eq!(choose_method(&[0x00]), NO_ACCEPTABLE);
            assert_eq!(choose_method(&[]), NO_ACCEPTABLE);
        }

        #[test]
        fn only_the_exact_credentials_are_accepted() {
            let c =
                Credentials::new(b"synthetic-user".to_vec(), b"synthetic-pass".to_vec()).unwrap();
            assert!(c.accepts(b"synthetic-user", b"synthetic-pass"));
            assert!(!c.accepts(b"synthetic-user", b"synthetic-pas"));
            assert!(!c.accepts(b"synthetic-user", b"synthetic-pass!"));
            assert!(!c.accepts(b"other-user", b"synthetic-pass"));
            assert!(!c.accepts(b"", b""));
        }

        #[test]
        fn domains_are_normalized_without_aliases() {
            assert_eq!(
                normalized_domain(b"Relay.Example.COM.").as_deref(),
                Some("relay.example.com")
            );
            assert_eq!(
                normalized_domain(b"example.onion").as_deref(),
                Some("example.onion")
            );
            assert_eq!(normalized_domain(b"example.com.."), None);
            assert_eq!(normalized_domain(b"exa\0mple.org"), None);
            assert_eq!(normalized_domain(b"-relay.example"), None);
            assert_eq!(normalized_domain(b"relay-.example"), None);
            assert_eq!(normalized_domain(b"relay..example"), None);
            assert_eq!(normalized_domain(b"127.0.0.1"), None);
            assert_eq!(normalized_domain(b"127.1"), None);
            assert_eq!(normalized_domain(b"2130706433"), None);
            assert_eq!(normalized_domain(b"0x7f.0.0.1"), None);
            assert_eq!(normalized_domain(b"relay.123"), None);
            assert_eq!(normalized_domain(b"relay.0x7f"), None);
            assert_eq!(normalized_domain(b"2001:db8::1"), None);
            assert_eq!(normalized_domain(b""), None);
            assert_eq!(normalized_domain(&[0xff, 0xfe]), None);
        }

        #[test]
        fn empty_or_oversized_credentials_are_rejected() {
            assert!(Credentials::new(Vec::new(), b"p".to_vec()).is_none());
            assert!(Credentials::new(b"u".to_vec(), Vec::new()).is_none());
            assert!(Credentials::new(vec![b'u'; 256], b"p".to_vec()).is_none());
            assert!(Credentials::new(vec![b'u'; 255], vec![b'p'; 255]).is_some());
        }
    }
}

#[cfg(test)]
mod socks_isolation_tests {
    use super::*;
    use tokio::io::{AsyncReadExt, AsyncWriteExt};

    async fn write_fragmented(stream: &mut tokio::io::DuplexStream, bytes: &[u8]) {
        for byte in bytes {
            stream.write_all(&[*byte]).await.unwrap();
            tokio::task::yield_now().await;
        }
    }

    #[test]
    fn one_normalized_host_reuses_one_token_and_hosts_are_separated() {
        let registry = DestinationIsolation::new();
        let first = registry.token_for("relay.example").unwrap();
        let again = registry.token_for("relay.example").unwrap();
        let other = registry.token_for("other.example").unwrap();
        assert_eq!(first, again);
        assert_ne!(first, other);
    }

    #[test]
    fn a_new_session_gets_a_fresh_token_registry() {
        let first = DestinationIsolation::new()
            .token_for("relay.example")
            .unwrap();
        let after_restart = DestinationIsolation::new()
            .token_for("relay.example")
            .unwrap();
        assert_ne!(first, after_restart);
    }

    #[test]
    fn an_unavailable_registry_fails_closed() {
        let registry = Arc::new(DestinationIsolation::new());
        let poison = Arc::clone(&registry);
        let _ = std::thread::spawn(move || {
            let _guard = poison.tokens.lock().unwrap();
            panic!("synthetic registry failure");
        })
        .join();
        assert!(registry.token_for("relay.example").is_err());
    }

    #[test]
    fn a_full_registry_fails_closed() {
        let registry = DestinationIsolation::new();
        for index in 0..MAX_DESTINATION_ISOLATION_GROUPS {
            registry
                .token_for(&format!("relay-{index}.example"))
                .unwrap();
        }
        assert!(registry.token_for("overflow.example").is_err());
        assert!(registry.token_for("relay-0.example").is_ok());
    }

    #[tokio::test]
    async fn fragmented_greeting_auth_and_domain_request_are_read_exactly() {
        let credentials =
            socks_auth::Credentials::new(b"synthetic-user".to_vec(), b"synthetic-pass".to_vec())
                .unwrap();
        let (mut client, mut server) = tokio::io::duplex(256);
        let parser = tokio::spawn(async move { socks_handshake(&mut server, &credentials).await });

        write_fragmented(&mut client, &[0x05, 0x01, 0x02]).await;
        let mut method = [0u8; 2];
        client.read_exact(&mut method).await.unwrap();
        assert_eq!(method, [0x05, 0x02]);

        let mut auth = vec![0x01, 14];
        auth.extend_from_slice(b"synthetic-user");
        auth.push(14);
        auth.extend_from_slice(b"synthetic-pass");
        write_fragmented(&mut client, &auth).await;
        let mut accepted = [0u8; 2];
        client.read_exact(&mut accepted).await.unwrap();
        assert_eq!(accepted, [0x01, 0x00]);

        let host = b"Relay.Example.";
        let mut request = vec![0x05, 0x01, 0x00, 0x03, host.len() as u8];
        request.extend_from_slice(host);
        request.extend_from_slice(&443u16.to_be_bytes());
        write_fragmented(&mut client, &request).await;

        let target = parser.await.unwrap().unwrap();
        assert_eq!(target, ("relay.example".to_owned(), 443));
    }

    #[tokio::test]
    async fn ip_address_types_are_refused_without_reading_or_resolving_them() {
        for atyp in [0x01, 0x04] {
            let (mut client, mut server) = tokio::io::duplex(32);
            let parser = tokio::spawn(async move { socks_request(&mut server).await });
            client.write_all(&[0x05, 0x01, 0x00, atyp]).await.unwrap();
            let mut response = [0u8; 10];
            client.read_exact(&mut response).await.unwrap();
            assert_eq!(response[1], 0x08);
            assert!(parser.await.unwrap().is_err());
        }
    }

    #[tokio::test]
    async fn textual_ip_and_invalid_domain_sizes_are_refused() {
        for host in [
            b"127.0.0.1".as_slice(),
            b"127.1".as_slice(),
            b"2130706433".as_slice(),
            b"0x7f.0.0.1".as_slice(),
            b"2001:db8::1".as_slice(),
            &[b'a'; 64],
        ] {
            let (mut client, mut server) = tokio::io::duplex(128);
            let parser = tokio::spawn(async move { socks_request(&mut server).await });
            let mut request = vec![0x05, 0x01, 0x00, 0x03, host.len() as u8];
            request.extend_from_slice(host);
            request.extend_from_slice(&443u16.to_be_bytes());
            client.write_all(&request).await.unwrap();
            let mut response = [0u8; 10];
            client.read_exact(&mut response).await.unwrap();
            assert_eq!(response[1], 0x01);
            assert!(parser.await.unwrap().is_err());
        }
    }

    #[tokio::test]
    async fn port_zero_is_refused_before_connecting() {
        let (mut client, mut server) = tokio::io::duplex(64);
        let parser = tokio::spawn(async move { socks_request(&mut server).await });
        let host = b"relay.example";
        let mut request = vec![0x05, 0x01, 0x00, 0x03, host.len() as u8];
        request.extend_from_slice(host);
        request.extend_from_slice(&0u16.to_be_bytes());
        client.write_all(&request).await.unwrap();
        let mut response = [0u8; 10];
        client.read_exact(&mut response).await.unwrap();
        assert_eq!(response[1], 0x01);
        assert!(parser.await.unwrap().is_err());
    }

    #[tokio::test]
    async fn truncated_domain_request_is_rejected() {
        let (mut client, mut server) = tokio::io::duplex(32);
        let parser = tokio::spawn(async move { socks_request(&mut server).await });
        client
            .write_all(&[0x05, 0x01, 0x00, 0x03, 0x05, b'a', b'b'])
            .await
            .unwrap();
        client.shutdown().await.unwrap();
        assert!(parser.await.unwrap().is_err());
    }
}
