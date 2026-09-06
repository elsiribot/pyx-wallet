use std::ffi::c_void;
use std::panic::{AssertUnwindSafe, catch_unwind};
use std::sync::Arc;
use zeroize::Zeroizing;

use jni::JNIEnv;
use jni::objects::{JClass, JObject, JString};
use jni::sys::{JNI_FALSE, JNI_TRUE, JNI_VERSION_1_6, JavaVM, jboolean, jint, jlong, jstring};

use super::error::{AndroidError, AndroidErrorCode};
use super::{
    async_requests, catalog, ecash_codec, fiat, input, reconciliation, runtime, seed,
    subscriptions, transfers, transient,
};

const NATIVE_VERSION: &str = env!("CARGO_PKG_VERSION");

/// Install a process-wide panic hook before any JNI entry point can handle
/// wallet data. `catch_unwind` prevents a Rust panic from crossing JNI, but the
/// default hook runs *before* the panic is caught and formats its payload to
/// stderr/logcat. Upstream panic payloads can contain invoices, invite codes,
/// ecash, or recovery words, so Android builds must never format them.
///
/// Do not include `PanicHookInfo`, source locations, or thread names here.
#[unsafe(no_mangle)]
pub extern "system" fn JNI_OnLoad(_vm: *mut JavaVM, _reserved: *mut c_void) -> jint {
    std::panic::set_hook(Box::new(|_| {}));
    JNI_VERSION_1_6
}

/// Bounded, pure version getter used to prove library loading and symbol wiring.
#[unsafe(no_mangle)]
pub extern "system" fn Java_cash_pyx_app_nativeapi_NativeBindings_nativeVersion<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
) -> jstring {
    let result = catch_unwind(AssertUnwindSafe(|| env.new_string(NATIVE_VERSION)));
    match result {
        Ok(Ok(value)) => value.into_raw(),
        Ok(Err(_)) | Err(_) => {
            let _ = env.throw_new(
                "java/lang/IllegalStateException",
                "The native wallet version is unavailable.",
            );
            JString::default().into_raw()
        }
    }
}

/// Synchronously checks that the one managed async runtime can be initialized.
/// It does not open a database or touch wallet state.
#[unsafe(no_mangle)]
pub extern "system" fn Java_cash_pyx_app_nativeapi_NativeBindings_bootstrapReady(
    mut env: JNIEnv,
    _class: JClass,
) -> jboolean {
    match catch_unwind(AssertUnwindSafe(runtime::is_ready)) {
        Ok(true) => JNI_TRUE,
        Ok(false) => JNI_FALSE,
        Err(_) => {
            let _ = env.throw_new(
                "java/lang/IllegalStateException",
                "The native wallet runtime is unavailable.",
            );
            JNI_FALSE
        }
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_cash_pyx_app_nativeapi_NativeBindings_seedWordSuggestions<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    prefix: JString<'local>,
) -> jstring {
    let result = catch_unwind(AssertUnwindSafe(|| {
        let prefix = env.get_string(&prefix).map_err(|_| invalid_argument())?;
        seed::suggestions(&prefix.to_string_lossy())
    }));
    result_to_jstring(&mut env, result)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_cash_pyx_app_nativeapi_NativeBindings_validateSeedPhrase<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    words_json: JString<'local>,
) -> jstring {
    let result = catch_unwind(AssertUnwindSafe(|| {
        let words_json = env
            .get_string(&words_json)
            .map_err(|_| invalid_argument())?;
        seed::validate(&words_json.to_string_lossy())
    }));
    result_to_jstring(&mut env, result)
}

fn checked_handle(handle: jlong) -> Result<u64, AndroidError> {
    (handle > 0)
        .then_some(handle as u64)
        .ok_or_else(AndroidError::invalid_handle)
}

fn invalid_argument() -> AndroidError {
    AndroidError::new(
        AndroidErrorCode::InvalidArgument,
        "The recovery phrase is invalid.",
        false,
    )
}

fn result_to_jstring(
    env: &mut JNIEnv,
    result: std::thread::Result<Result<String, AndroidError>>,
) -> jstring {
    let json = match result {
        Ok(Ok(json)) => json,
        Ok(Err(error)) => {
            throw_android_error(env, &error);
            return JString::default().into_raw();
        }
        Err(panic) => {
            let error = AndroidError::from_panic(panic);
            throw_android_error(env, &error);
            return JString::default().into_raw();
        }
    };
    match env.new_string(json) {
        Ok(value) => value.into_raw(),
        Err(_) => {
            let _ = env.throw_new(
                "java/lang/IllegalStateException",
                "The native wallet result is unavailable.",
            );
            JString::default().into_raw()
        }
    }
}

fn throw_android_error(env: &mut JNIEnv, error: &AndroidError) {
    let exception = match error.code {
        AndroidErrorCode::InvalidArgument => "java/lang/IllegalArgumentException",
        _ => "java/lang/IllegalStateException",
    };
    let _ = env.throw_new(exception, error.user_message);
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_cash_pyx_app_nativeapi_NativeBindings_createEcashEncoder<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    payload: JString<'local>,
) -> jlong {
    let result = catch_unwind(AssertUnwindSafe(|| {
        let payload = env
            .get_string(&payload)
            .map_err(|_| invalid_payment_input())?;
        ecash_codec::create_encoder(&payload.to_string_lossy())
    }));
    handle_result(&mut env, result)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_cash_pyx_app_nativeapi_NativeBindings_nextEcashFragment<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    encoder_handle: jlong,
) -> jstring {
    let result = catch_unwind(AssertUnwindSafe(|| {
        ecash_codec::next_fragment(checked_handle(encoder_handle)?)
    }));
    result_to_jstring(&mut env, result)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_cash_pyx_app_nativeapi_NativeBindings_createEcashDecoder<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
) -> jlong {
    let result = catch_unwind(AssertUnwindSafe(ecash_codec::create_decoder));
    handle_result(&mut env, result)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_cash_pyx_app_nativeapi_NativeBindings_addEcashFragment<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    decoder_handle: jlong,
    fragment: JString<'local>,
) -> jstring {
    let result = catch_unwind(AssertUnwindSafe(|| {
        let fragment = env
            .get_string(&fragment)
            .map_err(|_| invalid_payment_input())?;
        ecash_codec::add_fragment(checked_handle(decoder_handle)?, &fragment.to_string_lossy())
    }));
    result_to_jstring(&mut env, result)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_cash_pyx_app_nativeapi_NativeBindings_closeEcashCodec<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    kind: JString<'local>,
) -> jstring {
    let result = catch_unwind(AssertUnwindSafe(|| {
        let kind = env.get_string(&kind).map_err(|_| invalid_payment_input())?;
        ecash_codec::close(checked_handle(handle)?, &kind.to_string_lossy())
    }));
    result_to_jstring(&mut env, result)
}

fn handle_result(
    env: &mut JNIEnv,
    result: std::thread::Result<Result<u64, AndroidError>>,
) -> jlong {
    match result {
        Ok(Ok(handle)) => handle as jlong,
        Ok(Err(error)) => {
            throw_android_error(env, &error);
            0
        }
        Err(panic) => {
            throw_android_error(env, &AndroidError::from_panic(panic));
            0
        }
    }
}

fn invalid_payment_input() -> AndroidError {
    AndroidError::new(
        AndroidErrorCode::InvalidArgument,
        "The payment request is invalid.",
        false,
    )
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_cash_pyx_app_nativeapi_NativeBindings_listFiatCurrencies<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
) -> jstring {
    let result = catch_unwind(AssertUnwindSafe(catalog::currencies));
    result_to_jstring(&mut env, result)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_cash_pyx_app_nativeapi_NativeBindings_classifyInput<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    payload: JString<'local>,
) -> jstring {
    let result = catch_unwind(AssertUnwindSafe(|| {
        let payload = env
            .get_string(&payload)
            .map_err(|_| invalid_classification_input())?;
        input::classify(&payload.to_string_lossy())
    }));
    result_to_jstring(&mut env, result)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_cash_pyx_app_nativeapi_NativeBindings_parseBitcoinPayment<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    payload: JString<'local>,
) -> jstring {
    let result = catch_unwind(AssertUnwindSafe(|| {
        let payload = env
            .get_string(&payload)
            .map_err(|_| invalid_classification_input())?;
        input::parse_bitcoin_payment(&payload.to_string_lossy())
    }));
    result_to_jstring(&mut env, result)
}

fn invalid_classification_input() -> AndroidError {
    AndroidError::new(
        AndroidErrorCode::InvalidArgument,
        "The input could not be classified.",
        false,
    )
}

/// Closes only flow-scoped native state. Supported kinds are `quote` and
/// `lnurl`; process-wide database/factory/client handles are intentionally not
/// accepted by this API.
#[unsafe(no_mangle)]
pub extern "system" fn Java_cash_pyx_app_nativeapi_NativeBindings_closeTransientHandle<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    kind: JString<'local>,
) -> jstring {
    let result = catch_unwind(AssertUnwindSafe(|| {
        let kind = env
            .get_string(&kind)
            .map_err(|_| invalid_transient_kind())?;
        transient::close(checked_handle(handle)?, &kind.to_string_lossy())
    }));
    result_to_jstring(&mut env, result)
}

fn invalid_transient_kind() -> AndroidError {
    AndroidError::new(
        AndroidErrorCode::InvalidArgument,
        "The transient handle type is invalid.",
        false,
    )
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_cash_pyx_app_nativeapi_NativeBindings_shutdownAndroidSession<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    callback: JObject<'local>,
) -> jlong {
    start_request_result(
        &mut env,
        callback,
        Ok(Ok(async_requests::SnapshotRequest::ShutdownAndroidSession)),
    )
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_cash_pyx_app_nativeapi_NativeBindings_walletSnapshotAsync<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    factory_handle: jlong,
    callback: JObject<'local>,
) -> jlong {
    start_async_request(
        &mut env,
        callback,
        async_requests::SnapshotRequest::Wallet,
        factory_handle,
    )
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_cash_pyx_app_nativeapi_NativeBindings_connectionStatusAsync<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    client_handle: jlong,
    callback: JObject<'local>,
) -> jlong {
    start_async_request(
        &mut env,
        callback,
        async_requests::SnapshotRequest::Connection,
        client_handle,
    )
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_cash_pyx_app_nativeapi_NativeBindings_recoveryExpirySnapshotAsync<
    'local,
>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    client_handle: jlong,
    callback: JObject<'local>,
) -> jlong {
    start_async_request(
        &mut env,
        callback,
        async_requests::SnapshotRequest::RecoveryExpiry,
        client_handle,
    )
}

fn start_async_request(
    env: &mut JNIEnv,
    callback: JObject,
    request: fn(u64) -> async_requests::SnapshotRequest,
    source_handle: jlong,
) -> jlong {
    let result = catch_unwind(AssertUnwindSafe(|| {
        if callback.is_null() {
            return Err(AndroidError::new(
                AndroidErrorCode::InvalidArgument,
                "The native callback is invalid.",
                false,
            ));
        }
        let source_handle = checked_handle(source_handle)?;
        let vm = env.get_java_vm().map_err(|_| AndroidError::internal())?;
        let callback = env
            .new_global_ref(callback)
            .map_err(|_| AndroidError::internal())?;
        let sink = Arc::new(async_requests::jni_sink::JniTerminalSink::new(vm, callback));
        async_requests::start(request(source_handle), sink)
    }));
    match result {
        Ok(Ok(request_id)) => request_id as jlong,
        Ok(Err(error)) => {
            throw_android_error(env, &error);
            0
        }
        Err(panic) => {
            throw_android_error(env, &AndroidError::from_panic(panic));
            0
        }
    }
}

fn start_owned_async_request(
    env: &mut JNIEnv,
    callback: JObject,
    request: async_requests::SnapshotRequest,
) -> jlong {
    let result = catch_unwind(AssertUnwindSafe(|| {
        if callback.is_null() {
            return Err(AndroidError::new(
                AndroidErrorCode::InvalidArgument,
                "The native callback is invalid.",
                false,
            ));
        }
        let vm = env.get_java_vm().map_err(|_| AndroidError::internal())?;
        let callback = env
            .new_global_ref(callback)
            .map_err(|_| AndroidError::internal())?;
        let sink = Arc::new(async_requests::jni_sink::JniTerminalSink::new(vm, callback));
        async_requests::start(request, sink)
    }));
    match result {
        Ok(Ok(request_id)) => request_id as jlong,
        Ok(Err(error)) => {
            throw_android_error(env, &error);
            0
        }
        Err(panic) => {
            throw_android_error(env, &AndroidError::from_panic(panic));
            0
        }
    }
}

fn owned_payment_input(env: &mut JNIEnv, value: &JString) -> Result<String, AndroidError> {
    let value = env
        .get_string(value)
        .map_err(|_| invalid_payment_input())?
        .to_string_lossy()
        .into_owned();
    if value.is_empty() || value.len() > transfers::MAX_PAYMENT_INPUT_BYTES {
        return Err(invalid_payment_input());
    }
    Ok(value)
}

fn owned_payment_input_allow_empty(
    env: &mut JNIEnv,
    value: &JString,
) -> Result<String, AndroidError> {
    let value = env
        .get_string(value)
        .map_err(|_| invalid_payment_input())?
        .to_string_lossy()
        .into_owned();
    if value.len() > 128 {
        return Err(invalid_payment_input());
    }
    Ok(value)
}

fn start_request_result(
    env: &mut JNIEnv,
    callback: JObject,
    request: std::thread::Result<Result<async_requests::SnapshotRequest, AndroidError>>,
) -> jlong {
    match request {
        Ok(Ok(request)) => start_owned_async_request(env, callback, request),
        Ok(Err(error)) => {
            throw_android_error(env, &error);
            0
        }
        Err(panic) => {
            throw_android_error(env, &AndroidError::from_panic(panic));
            0
        }
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_cash_pyx_app_nativeapi_NativeBindings_cancelRequest<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    request_id: jlong,
) -> jstring {
    let result = catch_unwind(AssertUnwindSafe(|| {
        let cancelled = async_requests::cancel(checked_handle(request_id)?)?;
        Ok(format!("{{\"cancelled\":{cancelled}}}"))
    }));
    result_to_jstring(&mut env, result)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_cash_pyx_app_nativeapi_NativeBindings_subscribeBalance<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    client_handle: jlong,
    callback: JObject<'local>,
) -> jlong {
    start_subscription(
        &mut env,
        callback,
        subscriptions::SubscriptionKind::Balance,
        client_handle,
    )
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_cash_pyx_app_nativeapi_NativeBindings_subscribeConnection<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    client_handle: jlong,
    callback: JObject<'local>,
) -> jlong {
    start_subscription(
        &mut env,
        callback,
        subscriptions::SubscriptionKind::Connection,
        client_handle,
    )
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_cash_pyx_app_nativeapi_NativeBindings_subscribeRecovery<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    client_handle: jlong,
    callback: JObject<'local>,
) -> jlong {
    start_subscription(
        &mut env,
        callback,
        subscriptions::SubscriptionKind::Recovery,
        client_handle,
    )
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_cash_pyx_app_nativeapi_NativeBindings_subscribePayments<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    client_handle: jlong,
    callback: JObject<'local>,
) -> jlong {
    start_subscription(
        &mut env,
        callback,
        subscriptions::SubscriptionKind::Payments,
        client_handle,
    )
}

fn start_subscription(
    env: &mut JNIEnv,
    callback: JObject,
    kind: subscriptions::SubscriptionKind,
    client_handle: jlong,
) -> jlong {
    let result = catch_unwind(AssertUnwindSafe(|| {
        if callback.is_null() {
            return Err(AndroidError::new(
                AndroidErrorCode::InvalidArgument,
                "The native callback is invalid.",
                false,
            ));
        }
        let client_handle = checked_handle(client_handle)?;
        let vm = env.get_java_vm().map_err(|_| AndroidError::internal())?;
        let callback = env
            .new_global_ref(callback)
            .map_err(|_| AndroidError::internal())?;
        let sink = Arc::new(subscriptions::jni_sink::JniSubscriptionSink::new(
            vm, callback,
        ));
        subscriptions::subscribe(client_handle, kind, sink)
    }));
    match result {
        Ok(Ok(subscription_handle)) => subscription_handle as jlong,
        Ok(Err(error)) => {
            throw_android_error(env, &error);
            0
        }
        Err(panic) => {
            throw_android_error(env, &AndroidError::from_panic(panic));
            0
        }
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_cash_pyx_app_nativeapi_NativeBindings_closeSubscription<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    subscription_handle: jlong,
) -> jstring {
    let result = catch_unwind(AssertUnwindSafe(|| {
        subscriptions::close(checked_handle(subscription_handle)?)
    }));
    result_to_jstring(&mut env, result)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_cash_pyx_app_nativeapi_NativeBindings_prepareLightningSendAsync<
    'local,
>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    client: jlong,
    invoice: JString<'local>,
    callback: JObject<'local>,
) -> jlong {
    let request = catch_unwind(AssertUnwindSafe(|| {
        Ok(async_requests::SnapshotRequest::PrepareLightning(
            checked_handle(client)?,
            owned_payment_input(&mut env, &invoice)?,
        ))
    }));
    start_request_result(&mut env, callback, request)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_cash_pyx_app_nativeapi_NativeBindings_executeLightningSendAsync<
    'local,
>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    client: jlong,
    quote: jlong,
    correlation: JString<'local>,
    callback: JObject<'local>,
) -> jlong {
    let request = catch_unwind(AssertUnwindSafe(|| {
        Ok(async_requests::SnapshotRequest::ExecuteLightning(
            checked_handle(client)?,
            checked_handle(quote)?,
            owned_payment_input(&mut env, &correlation)?,
        ))
    }));
    start_request_result(&mut env, callback, request)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_cash_pyx_app_nativeapi_NativeBindings_prepareOnchainSendAsync<
    'local,
>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    client: jlong,
    address: JString<'local>,
    amount: jlong,
    callback: JObject<'local>,
) -> jlong {
    let request = catch_unwind(AssertUnwindSafe(|| {
        Ok(async_requests::SnapshotRequest::PrepareOnchain(
            checked_handle(client)?,
            owned_payment_input(&mut env, &address)?,
            amount,
        ))
    }));
    start_request_result(&mut env, callback, request)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_cash_pyx_app_nativeapi_NativeBindings_executeOnchainSendAsync<
    'local,
>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    client: jlong,
    quote: jlong,
    correlation: JString<'local>,
    callback: JObject<'local>,
) -> jlong {
    let request = catch_unwind(AssertUnwindSafe(|| {
        Ok(async_requests::SnapshotRequest::ExecuteOnchain(
            checked_handle(client)?,
            checked_handle(quote)?,
            owned_payment_input(&mut env, &correlation)?,
        ))
    }));
    start_request_result(&mut env, callback, request)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_cash_pyx_app_nativeapi_NativeBindings_receiveLightningAsync<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    client: jlong,
    amount: jlong,
    callback: JObject<'local>,
) -> jlong {
    let request = catch_unwind(AssertUnwindSafe(|| {
        Ok(async_requests::SnapshotRequest::ReceiveLightning(
            checked_handle(client)?,
            amount,
        ))
    }));
    start_request_result(&mut env, callback, request)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_cash_pyx_app_nativeapi_NativeBindings_receiveOnchainAsync<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    client: jlong,
    callback: JObject<'local>,
) -> jlong {
    let request = catch_unwind(AssertUnwindSafe(|| {
        Ok(async_requests::SnapshotRequest::ReceiveOnchain(
            checked_handle(client)?,
        ))
    }));
    start_request_result(&mut env, callback, request)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_cash_pyx_app_nativeapi_NativeBindings_createEcashAsync<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    client: jlong,
    amount: jlong,
    correlation: JString<'local>,
    callback: JObject<'local>,
) -> jlong {
    let request = catch_unwind(AssertUnwindSafe(|| {
        Ok(async_requests::SnapshotRequest::CreateEcash(
            checked_handle(client)?,
            amount,
            owned_payment_input(&mut env, &correlation)?,
        ))
    }));
    start_request_result(&mut env, callback, request)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_cash_pyx_app_nativeapi_NativeBindings_claimEcashAsync<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    client: jlong,
    payload: JString<'local>,
    correlation: JString<'local>,
    callback: JObject<'local>,
) -> jlong {
    let request = catch_unwind(AssertUnwindSafe(|| {
        Ok(async_requests::SnapshotRequest::ClaimEcash(
            checked_handle(client)?,
            owned_payment_input(&mut env, &payload)?,
            owned_payment_input(&mut env, &correlation)?,
        ))
    }));
    start_request_result(&mut env, callback, request)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_cash_pyx_app_nativeapi_NativeBindings_prepareLnurlQuoteAsync<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    client: jlong,
    session: jlong,
    amount: jlong,
    callback: JObject<'local>,
) -> jlong {
    let request = catch_unwind(AssertUnwindSafe(|| {
        Ok(async_requests::SnapshotRequest::PrepareLnurlQuote(
            checked_handle(client)?,
            checked_handle(session)?,
            amount,
        ))
    }));
    start_request_result(&mut env, callback, request)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_cash_pyx_app_nativeapi_NativeBindings_prepareLnurlAsync<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    request: JString<'local>,
    callback: JObject<'local>,
) -> jlong {
    let request = catch_unwind(AssertUnwindSafe(|| {
        Ok(async_requests::SnapshotRequest::PrepareLnurl(
            owned_payment_input(&mut env, &request)?,
        ))
    }));
    start_request_result(&mut env, callback, request)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_cash_pyx_app_nativeapi_NativeBindings_joinFederationAsync<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    factory: jlong,
    invite: JString<'local>,
    recover: jboolean,
    callback: JObject<'local>,
) -> jlong {
    let request = catch_unwind(AssertUnwindSafe(|| {
        Ok(async_requests::SnapshotRequest::JoinFederation(
            checked_handle(factory)?,
            owned_payment_input(&mut env, &invite)?,
            recover == JNI_TRUE,
        ))
    }));
    start_request_result(&mut env, callback, request)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_cash_pyx_app_nativeapi_NativeBindings_walletSnapshotForAsync<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    factory: jlong,
    id: JString<'local>,
    callback: JObject<'local>,
) -> jlong {
    let request = catch_unwind(AssertUnwindSafe(|| {
        Ok(async_requests::SnapshotRequest::SnapshotFederation(
            checked_handle(factory)?,
            owned_payment_input(&mut env, &id)?,
        ))
    }));
    start_request_result(&mut env, callback, request)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_cash_pyx_app_nativeapi_NativeBindings_leaveFederationAsync<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    factory: jlong,
    id: JString<'local>,
    callback: JObject<'local>,
) -> jlong {
    let request = catch_unwind(AssertUnwindSafe(|| {
        Ok(async_requests::SnapshotRequest::LeaveFederation(
            checked_handle(factory)?,
            owned_payment_input(&mut env, &id)?,
        ))
    }));
    start_request_result(&mut env, callback, request)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_cash_pyx_app_nativeapi_NativeBindings_federationDetailsAsync<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    client: jlong,
    callback: JObject<'local>,
) -> jlong {
    let request = catch_unwind(AssertUnwindSafe(|| {
        Ok(async_requests::SnapshotRequest::FederationDetails(
            checked_handle(client)?,
        ))
    }));
    start_request_result(&mut env, callback, request)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_cash_pyx_app_nativeapi_NativeBindings_listContactsAsync<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    factory: jlong,
    callback: JObject<'local>,
) -> jlong {
    let request = catch_unwind(AssertUnwindSafe(|| {
        Ok(async_requests::SnapshotRequest::ListContacts(
            checked_handle(factory)?,
        ))
    }));
    start_request_result(&mut env, callback, request)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_cash_pyx_app_nativeapi_NativeBindings_saveContactAsync<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    factory: jlong,
    lnurl: JString<'local>,
    name: JString<'local>,
    callback: JObject<'local>,
) -> jlong {
    let request = catch_unwind(AssertUnwindSafe(|| {
        Ok(async_requests::SnapshotRequest::SaveContact(
            checked_handle(factory)?,
            owned_payment_input(&mut env, &lnurl)?,
            owned_payment_input(&mut env, &name)?,
        ))
    }));
    start_request_result(&mut env, callback, request)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_cash_pyx_app_nativeapi_NativeBindings_deleteContactAsync<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    factory: jlong,
    lnurl: JString<'local>,
    callback: JObject<'local>,
) -> jlong {
    let request = catch_unwind(AssertUnwindSafe(|| {
        Ok(async_requests::SnapshotRequest::DeleteContact(
            checked_handle(factory)?,
            owned_payment_input(&mut env, &lnurl)?,
        ))
    }));
    start_request_result(&mut env, callback, request)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_cash_pyx_app_nativeapi_NativeBindings_setCurrencyAsync<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    factory: jlong,
    code: JString<'local>,
    callback: JObject<'local>,
) -> jlong {
    let request = catch_unwind(AssertUnwindSafe(|| {
        Ok(async_requests::SnapshotRequest::SetCurrency(
            checked_handle(factory)?,
            owned_payment_input(&mut env, &code)?,
        ))
    }));
    start_request_result(&mut env, callback, request)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_cash_pyx_app_nativeapi_NativeBindings_onchainAddressesAsync<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    client: jlong,
    callback: JObject<'local>,
) -> jlong {
    let request = catch_unwind(AssertUnwindSafe(|| {
        Ok(async_requests::SnapshotRequest::OnchainAddresses(
            checked_handle(client)?,
        ))
    }));
    start_request_result(&mut env, callback, request)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_cash_pyx_app_nativeapi_NativeBindings_recheckOnchainAddressAsync<
    'local,
>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    client: jlong,
    index: jlong,
    callback: JObject<'local>,
) -> jlong {
    let request = catch_unwind(AssertUnwindSafe(|| {
        Ok(async_requests::SnapshotRequest::RecheckOnchainAddress(
            checked_handle(client)?,
            index,
        ))
    }));
    start_request_result(&mut env, callback, request)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_cash_pyx_app_nativeapi_NativeBindings_paymentDetailsAsync<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    client: jlong,
    operation: JString<'local>,
    callback: JObject<'local>,
) -> jlong {
    let request = catch_unwind(AssertUnwindSafe(|| {
        Ok(async_requests::SnapshotRequest::PaymentDetails(
            checked_handle(client)?,
            owned_payment_input(&mut env, &operation)?,
        ))
    }));
    start_request_result(&mut env, callback, request)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_cash_pyx_app_nativeapi_NativeBindings_pendingOperationsAsync<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    client: jlong,
    callback: JObject<'local>,
) -> jlong {
    let request = catch_unwind(AssertUnwindSafe(|| {
        Ok(async_requests::SnapshotRequest::PendingOperations(
            checked_handle(client)?,
        ))
    }));
    start_request_result(&mut env, callback, request)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_cash_pyx_app_nativeapi_NativeBindings_reconcileOperationAsync<
    'local,
>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    client: jlong,
    correlation: JString<'local>,
    kind: JString<'local>,
    callback: JObject<'local>,
) -> jlong {
    let request = catch_unwind(AssertUnwindSafe(|| {
        let kind = owned_payment_input(&mut env, &kind)?;
        Ok(async_requests::SnapshotRequest::ReconcileOperation(
            checked_handle(client)?,
            owned_payment_input(&mut env, &correlation)?,
            reconciliation::OperationKind::from_name(&kind).ok_or_else(|| {
                AndroidError::new(
                    super::error::AndroidErrorCode::InvalidArgument,
                    "The operation kind is invalid.",
                    false,
                )
            })?,
        ))
    }));
    start_request_result(&mut env, callback, request)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_cash_pyx_app_nativeapi_NativeBindings_clearOperationAsync<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    client: jlong,
    correlation: JString<'local>,
    callback: JObject<'local>,
) -> jlong {
    let request = catch_unwind(AssertUnwindSafe(|| {
        Ok(async_requests::SnapshotRequest::ClearOperation(
            checked_handle(client)?,
            owned_payment_input(&mut env, &correlation)?,
        ))
    }));
    start_request_result(&mut env, callback, request)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_cash_pyx_app_nativeapi_NativeBindings_paymentHistoryPageAsync<
    'local,
>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    client: jlong,
    cursor: JString<'local>,
    page_size: jint,
    callback: JObject<'local>,
) -> jlong {
    let request = catch_unwind(AssertUnwindSafe(|| {
        Ok(async_requests::SnapshotRequest::PaymentHistoryPage(
            checked_handle(client)?,
            owned_payment_input_allow_empty(&mut env, &cursor)?,
            page_size,
        ))
    }));
    start_request_result(&mut env, callback, request)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_cash_pyx_app_nativeapi_NativeBindings_bootstrapAsync<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    files_dir: JString<'local>,
    callback: JObject<'local>,
) -> jlong {
    let request = catch_unwind(AssertUnwindSafe(|| {
        Ok(async_requests::SnapshotRequest::Bootstrap(
            owned_payment_input(&mut env, &files_dir)?,
        ))
    }));
    start_request_result(&mut env, callback, request)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_cash_pyx_app_nativeapi_NativeBindings_createWalletAsync<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    database: jlong,
    callback: JObject<'local>,
) -> jlong {
    let request = catch_unwind(AssertUnwindSafe(|| {
        Ok(async_requests::SnapshotRequest::CreateWallet(
            checked_handle(database)?,
        ))
    }));
    start_request_result(&mut env, callback, request)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_cash_pyx_app_nativeapi_NativeBindings_restoreWalletAsync<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    database: jlong,
    words_json: JString<'local>,
    callback: JObject<'local>,
) -> jlong {
    let request = catch_unwind(AssertUnwindSafe(|| {
        Ok(async_requests::SnapshotRequest::RestoreWallet(
            checked_handle(database)?,
            Zeroizing::new(owned_payment_input(&mut env, &words_json)?),
        ))
    }));
    start_request_result(&mut env, callback, request)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_cash_pyx_app_nativeapi_NativeBindings_seedWordsAsync<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    factory: jlong,
    callback: JObject<'local>,
) -> jlong {
    let request = catch_unwind(AssertUnwindSafe(|| {
        Ok(async_requests::SnapshotRequest::SeedWords(checked_handle(
            factory,
        )?))
    }));
    start_request_result(&mut env, callback, request)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_cash_pyx_app_nativeapi_NativeBindings_receiveLnurlAsync<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    client: jlong,
    callback: JObject<'local>,
) -> jlong {
    let request = catch_unwind(AssertUnwindSafe(|| {
        Ok(async_requests::SnapshotRequest::ReceiveLnurl(
            checked_handle(client)?,
        ))
    }));
    start_request_result(&mut env, callback, request)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_cash_pyx_app_nativeapi_NativeBindings_lnaddrSnapshotAsync<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    factory_handle: jlong,
    callback: JObject<'local>,
) -> jlong {
    start_async_request(
        &mut env,
        callback,
        async_requests::SnapshotRequest::LnaddrSnapshot,
        factory_handle,
    )
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_cash_pyx_app_nativeapi_NativeBindings_lnaddrDiscoverAsync<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    factory_handle: jlong,
    callback: JObject<'local>,
) -> jlong {
    start_async_request(
        &mut env,
        callback,
        async_requests::SnapshotRequest::LnaddrDiscover,
        factory_handle,
    )
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_cash_pyx_app_nativeapi_NativeBindings_lnaddrQuoteAsync<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    factory: jlong,
    origin: JString<'local>,
    domain: JString<'local>,
    username: JString<'local>,
    callback: JObject<'local>,
) -> jlong {
    let request = catch_unwind(AssertUnwindSafe(|| {
        Ok(async_requests::SnapshotRequest::LnaddrQuote(
            checked_handle(factory)?,
            owned_payment_input(&mut env, &origin)?,
            owned_payment_input(&mut env, &domain)?,
            owned_payment_input(&mut env, &username)?,
        ))
    }));
    start_request_result(&mut env, callback, request)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_cash_pyx_app_nativeapi_NativeBindings_lnaddrClaimAsync<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    client: jlong,
    origin: JString<'local>,
    domain: JString<'local>,
    username: JString<'local>,
    callback: JObject<'local>,
) -> jlong {
    let request = catch_unwind(AssertUnwindSafe(|| {
        Ok(async_requests::SnapshotRequest::LnaddrClaim(
            checked_handle(client)?,
            owned_payment_input(&mut env, &origin)?,
            owned_payment_input(&mut env, &domain)?,
            owned_payment_input(&mut env, &username)?,
        ))
    }));
    start_request_result(&mut env, callback, request)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_cash_pyx_app_nativeapi_NativeBindings_lnaddrSetPrimaryAsync<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    factory: jlong,
    domain: JString<'local>,
    username: JString<'local>,
    callback: JObject<'local>,
) -> jlong {
    let request = catch_unwind(AssertUnwindSafe(|| {
        Ok(async_requests::SnapshotRequest::LnaddrSetPrimary(
            checked_handle(factory)?,
            owned_payment_input(&mut env, &domain)?,
            owned_payment_input(&mut env, &username)?,
        ))
    }));
    start_request_result(&mut env, callback, request)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_cash_pyx_app_nativeapi_NativeBindings_lnaddrReleaseAsync<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    factory: jlong,
    domain: JString<'local>,
    username: JString<'local>,
    callback: JObject<'local>,
) -> jlong {
    let request = catch_unwind(AssertUnwindSafe(|| {
        Ok(async_requests::SnapshotRequest::LnaddrRelease(
            checked_handle(factory)?,
            owned_payment_input(&mut env, &domain)?,
            owned_payment_input(&mut env, &username)?,
        ))
    }));
    start_request_result(&mut env, callback, request)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_cash_pyx_app_nativeapi_NativeBindings_lnaddrRepointAsync<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    client: jlong,
    domain: JString<'local>,
    username: JString<'local>,
    callback: JObject<'local>,
) -> jlong {
    let request = catch_unwind(AssertUnwindSafe(|| {
        Ok(async_requests::SnapshotRequest::LnaddrRepoint(
            checked_handle(client)?,
            owned_payment_input(&mut env, &domain)?,
            owned_payment_input(&mut env, &username)?,
        ))
    }));
    start_request_result(&mut env, callback, request)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_cash_pyx_app_nativeapi_NativeBindings_lnaddrRecoverAsync<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    factory_handle: jlong,
    callback: JObject<'local>,
) -> jlong {
    start_async_request(
        &mut env,
        callback,
        async_requests::SnapshotRequest::LnaddrRecover,
        factory_handle,
    )
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_cash_pyx_app_nativeapi_NativeBindings_satsToFiat<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    client_handle: jlong,
    amount_sat: jlong,
) -> jstring {
    let result = catch_unwind(AssertUnwindSafe(|| {
        fiat::sats_to_fiat(checked_handle(client_handle)?, amount_sat)
    }));
    match result {
        Ok(Ok(Some(json))) => match env.new_string(json) {
            Ok(value) => value.into_raw(),
            Err(_) => {
                throw_android_error(&mut env, &AndroidError::internal());
                JString::default().into_raw()
            }
        },
        Ok(Ok(None)) => JString::default().into_raw(),
        Ok(Err(error)) => {
            throw_android_error(&mut env, &error);
            JString::default().into_raw()
        }
        Err(panic) => {
            throw_android_error(&mut env, &AndroidError::from_panic(panic));
            JString::default().into_raw()
        }
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_cash_pyx_app_nativeapi_NativeBindings_fiatToSatsAsync<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    client_handle: jlong,
    amount_decimal: JString<'local>,
    callback: JObject<'local>,
) -> jlong {
    let result = catch_unwind(AssertUnwindSafe(|| {
        if callback.is_null() || amount_decimal.is_null() {
            return Err(AndroidError::new(
                AndroidErrorCode::InvalidArgument,
                "The fiat conversion input is invalid.",
                false,
            ));
        }
        let client_handle = checked_handle(client_handle)?;
        let amount_decimal = env
            .get_string(&amount_decimal)
            .map_err(|_| {
                AndroidError::new(
                    AndroidErrorCode::InvalidArgument,
                    "The fiat amount format is invalid.",
                    false,
                )
            })?
            .to_string_lossy()
            .into_owned();
        let vm = env.get_java_vm().map_err(|_| AndroidError::internal())?;
        let callback = env
            .new_global_ref(callback)
            .map_err(|_| AndroidError::internal())?;
        let sink = Arc::new(async_requests::jni_sink::JniTerminalSink::new(vm, callback));
        async_requests::start(
            async_requests::SnapshotRequest::FiatToSats(client_handle, amount_decimal),
            sink,
        )
    }));
    match result {
        Ok(Ok(request_id)) => request_id as jlong,
        Ok(Err(error)) => {
            throw_android_error(&mut env, &error);
            0
        }
        Err(panic) => {
            throw_android_error(&mut env, &AndroidError::from_panic(panic));
            0
        }
    }
}
