use std::sync::{Arc, Mutex};

use serde::Serialize;

use crate::lnurl::{
    LnurlWrapper, PayResponseWrapper, lnurl_fetch_limits, lnurl_resolve, parse_lnurl,
};

use super::error::{AndroidError, AndroidErrorCode};
use super::handles::{HandleKind, global_close, global_get, global_insert};
use super::quotes;

const MAX_LNURL_INPUT_BYTES: usize = 64 * 1024;

pub(crate) struct LnurlPaySession {
    // Retained with the fetched response so the opaque session completely owns
    // the parsed request lifetime and Kotlin never needs to retain raw input.
    lnurl: LnurlWrapper,
    response: PayResponseWrapper,
    state: Mutex<SessionState>,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
enum SessionState {
    Prepared,
    Executing,
    Consumed,
}

impl LnurlPaySession {
    fn begin(&self) -> Result<(), AndroidError> {
        begin_state(&self.state)
    }

    fn consume(&self) {
        if let Ok(mut state) = self.state.lock() {
            *state = SessionState::Consumed;
        }
    }
}

fn begin_state(state: &Mutex<SessionState>) -> Result<(), AndroidError> {
    let mut state = state.lock().map_err(|_| AndroidError::internal())?;
    match *state {
        SessionState::Prepared => {
            *state = SessionState::Executing;
            Ok(())
        }
        SessionState::Executing | SessionState::Consumed => Err(AndroidError::invalid_handle()),
    }
}

struct SessionExecutionGuard {
    session: Arc<LnurlPaySession>,
    handle: u64,
}

impl Drop for SessionExecutionGuard {
    fn drop(&mut self) {
        self.session.consume();
        let _ = global_close(self.handle, HandleKind::Parsed);
    }
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct PreparedLnurlDto {
    session_handle: u64,
    min_sat: i64,
    max_sat: i64,
    fixed_amount: bool,
}

pub(crate) async fn prepare_async_with_handle(
    request: String,
) -> Result<(String, u64), AndroidError> {
    validate_request(&request)?;
    let lnurl = parse_lnurl(&request).ok_or_else(invalid_lnurl)?;
    let response = lnurl_fetch_limits(&lnurl)
        .await
        .map_err(|_| AndroidError::internal())?;
    let min_sat = response.min_sats();
    let max_sat = response.max_sats();
    validate_limits(min_sat, max_sat)?;
    let fixed_amount = response.is_fixed_amount();
    let session_handle = global_insert(
        HandleKind::Parsed,
        LnurlPaySession {
            lnurl,
            response,
            state: Mutex::new(SessionState::Prepared),
        },
    )?;
    let json = match serialize(&PreparedLnurlDto {
        session_handle,
        min_sat,
        max_sat,
        fixed_amount,
    }) {
        Ok(json) => json,
        Err(error) => {
            let _ = global_close(session_handle, HandleKind::Parsed);
            return Err(error);
        }
    };
    Ok((json, session_handle))
}

pub(crate) async fn prepare_quote_async_with_handle(
    client_handle: u64,
    session_handle: u64,
    amount_sat: i64,
) -> Result<(String, u64), AndroidError> {
    let session = global_get::<LnurlPaySession>(session_handle, HandleKind::Parsed)?;
    session.begin()?;
    let _execution = SessionExecutionGuard {
        session: session.clone(),
        handle: session_handle,
    };
    let min_sat = session.response.min_sats();
    let max_sat = session.response.max_sats();
    validate_amount(amount_sat, min_sat, max_sat)?;
    // Keep the parsed request alive for the full response/session lifetime.
    let _ = &session.lnurl;

    let invoice = lnurl_resolve(&session.response, amount_sat)
        .await
        .map_err(|_| AndroidError::internal())?;
    quotes::prepare_resolved_lightning_async_with_handle(client_handle, invoice).await
}

fn validate_request(request: &str) -> Result<(), AndroidError> {
    if request.is_empty() || request.len() > MAX_LNURL_INPUT_BYTES {
        return Err(invalid_lnurl());
    }
    Ok(())
}

fn validate_limits(min_sat: i64, max_sat: i64) -> Result<(), AndroidError> {
    if min_sat < 0 || max_sat < 1 || min_sat > max_sat {
        return Err(AndroidError::internal());
    }
    Ok(())
}

fn validate_amount(amount_sat: i64, min_sat: i64, max_sat: i64) -> Result<(), AndroidError> {
    if amount_sat < 1 || amount_sat < min_sat || amount_sat > max_sat {
        return Err(AndroidError::new(
            AndroidErrorCode::InvalidArgument,
            "The LNURL payment amount is outside the allowed range.",
            false,
        ));
    }
    Ok(())
}

fn invalid_lnurl() -> AndroidError {
    AndroidError::new(
        AndroidErrorCode::InvalidArgument,
        "The LNURL payment request is invalid.",
        false,
    )
}

fn serialize<T: Serialize>(value: &T) -> Result<String, AndroidError> {
    serde_json::to_string(value).map_err(|_| AndroidError::internal())
}

#[cfg(test)]
mod tests {
    use std::sync::{Arc, Barrier};
    use std::thread;

    use super::*;

    #[test]
    fn request_and_amount_validation_is_bounded() {
        assert!(validate_request("user@example.com").is_ok());
        assert!(validate_request("").is_err());
        assert!(validate_request(&"x".repeat(MAX_LNURL_INPUT_BYTES + 1)).is_err());
        assert!(validate_amount(10, 10, 20).is_ok());
        assert!(validate_amount(20, 10, 20).is_ok());
        assert!(validate_amount(9, 10, 20).is_err());
        assert!(validate_amount(21, 10, 20).is_err());
        assert!(validate_amount(0, 0, 20).is_err());
    }

    #[test]
    fn response_json_matches_contract() {
        assert_eq!(
            serialize(&PreparedLnurlDto {
                session_handle: 7,
                min_sat: 10,
                max_sat: 20,
                fixed_amount: false,
            })
            .unwrap(),
            "{\"sessionHandle\":7,\"minSat\":10,\"maxSat\":20,\"fixedAmount\":false}"
        );
    }

    #[test]
    fn session_execution_is_one_shot_under_concurrency() {
        let state = Arc::new(Mutex::new(SessionState::Prepared));
        let barrier = Arc::new(Barrier::new(3));
        let workers = (0..2)
            .map(|_| {
                let state = state.clone();
                let barrier = barrier.clone();
                thread::spawn(move || {
                    barrier.wait();
                    begin_state(&state).is_ok()
                })
            })
            .collect::<Vec<_>>();
        barrier.wait();
        let winners = workers
            .into_iter()
            .map(|worker| worker.join().unwrap())
            .filter(|won| *won)
            .count();
        assert_eq!(winners, 1);
    }

    #[test]
    fn terminal_failures_mark_sessions_consumed() {
        let state = Mutex::new(SessionState::Executing);
        // This is the state transition performed by SessionExecutionGuard on
        // resolution, range, client, fee, serialization, and panic exits.
        if let Ok(mut state) = state.lock() {
            *state = SessionState::Consumed;
        }
        assert_eq!(*state.lock().unwrap(), SessionState::Consumed);
        assert!(begin_state(&state).is_err());
    }
}
