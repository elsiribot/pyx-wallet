use std::sync::Mutex;

use serde::Serialize;

use crate::client::ConduitClient;
use crate::{BitcoinAddressWrapper, Bolt11InvoiceWrapper, parse_bolt11_invoice};

use super::bip21;
use super::error::AndroidError;
use super::handles::{HandleKind, global_close, global_get, global_insert};
use super::reconciliation::{self, OperationKind};
use super::transfers::{validate_amount, validate_payload};

struct LightningQuote {
    invoice: Bolt11InvoiceWrapper,
    gateway_url: String,
    fee_sat: i64,
}

struct OnchainQuote {
    address: BitcoinAddressWrapper,
    amount_sat: i64,
    fee_sat: i64,
}

enum QuoteState<T> {
    Prepared(T),
    Executing,
    Consumed,
}

struct QuoteEntry<T> {
    owner_client_handle: u64,
    state: Mutex<QuoteState<T>>,
}

impl<T> QuoteEntry<T> {
    fn new(owner_client_handle: u64, payload: T) -> Self {
        Self {
            owner_client_handle,
            state: Mutex::new(QuoteState::Prepared(payload)),
        }
    }

    fn begin(&self, client_handle: u64) -> Result<T, AndroidError> {
        if self.owner_client_handle != client_handle {
            return Err(AndroidError::invalid_handle());
        }
        let mut state = self.state.lock().map_err(|_| AndroidError::internal())?;
        match std::mem::replace(&mut *state, QuoteState::Executing) {
            QuoteState::Prepared(payload) => Ok(payload),
            QuoteState::Executing => {
                *state = QuoteState::Executing;
                Err(AndroidError::invalid_handle())
            }
            QuoteState::Consumed => {
                *state = QuoteState::Consumed;
                Err(AndroidError::invalid_handle())
            }
        }
    }

    fn consume(&self) -> Result<(), AndroidError> {
        let mut state = self.state.lock().map_err(|_| AndroidError::internal())?;
        *state = QuoteState::Consumed;
        Ok(())
    }
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct PreparedLightningDto {
    quote_handle: u64,
    amount_sat: i64,
    fee_sat: i64,
    gateway_url: String,
    direct: bool,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct ExecutedLightningDto {
    correlation_id: String,
    operation_id: String,
    fee_sat: i64,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct PreparedOnchainDto {
    quote_handle: u64,
    amount_sat: i64,
    fee_sat: i64,
    address: String,
    uri_amount_sat: Option<i64>,
    amount_locked: bool,
    label: Option<String>,
    message: Option<String>,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct ExecutedOnchainDto {
    correlation_id: String,
    operation_id: String,
    fee_sat: i64,
}

pub(crate) async fn prepare_lightning_async_with_handle(
    client_handle: u64,
    invoice: String,
) -> Result<(String, u64), AndroidError> {
    validate_payload(&invoice)?;
    let invoice = parse_bolt11_invoice(&invoice).ok_or_else(invalid_destination)?;
    prepare_resolved_lightning_async_with_handle(client_handle, invoice).await
}

pub(crate) async fn prepare_resolved_lightning_async_with_handle(
    client_handle: u64,
    invoice: Bolt11InvoiceWrapper,
) -> Result<(String, u64), AndroidError> {
    let amount_sat = invoice.amount_sats();
    validate_amount(amount_sat)?;
    let client = client(client_handle)?;
    let fees = client
        .ln_calculate_fees(&invoice)
        .await
        .map_err(|e| AndroidError::from_lightning(&e))?;
    let fee_sat = fees.fee_sats;
    let gateway_url = fees.gateway_url;
    let direct = fees.is_direct;
    let quote_handle = global_insert(
        HandleKind::Quote,
        QuoteEntry::new(
            client_handle,
            LightningQuote {
                invoice,
                gateway_url: gateway_url.clone(),
                fee_sat,
            },
        ),
    )?;
    let json = serialize(&PreparedLightningDto {
        quote_handle,
        amount_sat,
        fee_sat,
        gateway_url,
        direct,
    })?;
    Ok((json, quote_handle))
}

pub(crate) async fn execute_lightning_async(
    client_handle: u64,
    quote_handle: u64,
    requested_correlation_id: String,
) -> Result<String, AndroidError> {
    let quote = global_get::<QuoteEntry<LightningQuote>>(quote_handle, HandleKind::Quote)?;
    let payload = quote.begin(client_handle)?;
    let client = client(client_handle)?;
    let (correlation_id, _submission) = reconciliation::start_with_correlation(
        &client,
        OperationKind::Lightning,
        payload.invoice.amount_sats(),
        payload.fee_sat,
        &[],
        &requested_correlation_id,
    )
    .await?;
    let result = client
        .ln_send_with_meta(
            &payload.invoice,
            Some(payload.gateway_url),
            reconciliation::marker(&correlation_id),
        )
        .await;
    quote.consume()?;
    let _ = global_close(quote_handle, HandleKind::Quote);
    let operation_id = result.map_err(|e| AndroidError::from_lightning(&e))?;
    reconciliation::attach_operation(&client, &correlation_id, operation_id).await?;
    serialize(&ExecutedLightningDto {
        correlation_id,
        operation_id: operation_id.fmt_full().to_string(),
        fee_sat: payload.fee_sat,
    })
}

pub(crate) async fn prepare_onchain_async_with_handle(
    client_handle: u64,
    address: String,
    amount_sat: i64,
) -> Result<(String, u64), AndroidError> {
    validate_payload(&address)?;
    let destination = bip21::parse(&address)?;
    let amount_sat = resolve_onchain_amount(amount_sat, destination.amount_sat)?;
    validate_amount(amount_sat)?;
    let canonical_address = destination.canonical_address;
    let address = destination.address;
    let client = client(client_handle)?;
    let fee_sat = client
        .onchain_calculate_fees(&address, amount_sat)
        .await
        .map_err(|e| AndroidError::internal_logged("onchain_calculate_fees", &e))?;
    let quote_handle = global_insert(
        HandleKind::Quote,
        QuoteEntry::new(
            client_handle,
            OnchainQuote {
                address,
                amount_sat,
                fee_sat,
            },
        ),
    )?;
    let json = serialize(&PreparedOnchainDto {
        quote_handle,
        amount_sat,
        fee_sat,
        address: canonical_address,
        uri_amount_sat: destination.amount_sat,
        amount_locked: destination.amount_sat.is_some(),
        label: destination.label,
        message: destination.message,
    })?;
    Ok((json, quote_handle))
}

pub(crate) fn close_quote_after_suppressed_delivery(handle: u64) {
    let _ = global_close(handle, HandleKind::Quote);
}

fn resolve_onchain_amount(
    user_amount_sat: i64,
    uri_amount_sat: Option<i64>,
) -> Result<i64, AndroidError> {
    match uri_amount_sat {
        Some(uri_amount) if user_amount_sat == 0 || user_amount_sat == uri_amount => Ok(uri_amount),
        Some(_) => Err(AndroidError::new(
            super::error::AndroidErrorCode::InvalidArgument,
            "The entered amount conflicts with the bitcoin URI.",
            false,
        )),
        None => {
            validate_amount(user_amount_sat)?;
            Ok(user_amount_sat)
        }
    }
}

pub(crate) async fn execute_onchain_async(
    client_handle: u64,
    quote_handle: u64,
    requested_correlation_id: String,
) -> Result<String, AndroidError> {
    let quote = global_get::<QuoteEntry<OnchainQuote>>(quote_handle, HandleKind::Quote)?;
    let payload = quote.begin(client_handle)?;
    let client = client(client_handle)?;
    let address_fingerprint_source = payload.address.to_string();
    let (correlation_id, _submission) = reconciliation::start_with_correlation(
        &client,
        OperationKind::Onchain,
        payload.amount_sat,
        payload.fee_sat,
        address_fingerprint_source.as_bytes(),
        &requested_correlation_id,
    )
    .await?;
    let result = client
        .onchain_send_with_meta(
            &payload.address,
            payload.amount_sat,
            reconciliation::marker(&correlation_id),
        )
        .await;
    quote.consume()?;
    let _ = global_close(quote_handle, HandleKind::Quote);
    let operation_id = result.map_err(|e| AndroidError::internal_logged("onchain_send", &e))?;
    reconciliation::attach_operation(&client, &correlation_id, operation_id).await?;
    serialize(&ExecutedOnchainDto {
        correlation_id,
        operation_id: operation_id.fmt_full().to_string(),
        fee_sat: payload.fee_sat,
    })
}

fn client(handle: u64) -> Result<std::sync::Arc<ConduitClient>, AndroidError> {
    global_get(handle, HandleKind::Client)
}

fn invalid_destination() -> AndroidError {
    AndroidError::new(
        super::error::AndroidErrorCode::InvalidArgument,
        "The payment destination is invalid.",
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
    fn quote_is_one_shot_and_owner_bound() {
        let quote = QuoteEntry::new(7, "payload");
        assert!(quote.begin(8).is_err());
        assert_eq!(quote.begin(7).unwrap(), "payload");
        assert!(quote.begin(7).is_err());
        quote.consume().unwrap();
        assert!(quote.begin(7).is_err());
    }

    #[test]
    fn concurrent_execution_allows_exactly_one_winner() {
        let quote = Arc::new(QuoteEntry::new(7, 42));
        let barrier = Arc::new(Barrier::new(3));
        let workers = (0..2)
            .map(|_| {
                let quote = quote.clone();
                let barrier = barrier.clone();
                thread::spawn(move || {
                    barrier.wait();
                    quote.begin(7).is_ok()
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
    fn cancellation_after_execute_begins_does_not_restore_quote() {
        let quote = QuoteEntry::new(7, "irreversible-operation");
        assert_eq!(quote.begin(7).unwrap(), "irreversible-operation");
        // NativeRequest cancellation suppresses callback delivery only. The
        // quote remains Executing and cannot be retried by another request.
        assert!(quote.begin(7).is_err());
        quote.consume().unwrap();
        assert!(quote.begin(7).is_err());
    }

    #[test]
    fn response_json_matches_contract() {
        assert_eq!(
            serialize(&PreparedLightningDto {
                quote_handle: 7,
                amount_sat: 100,
                fee_sat: 2,
                gateway_url: "https://gateway.invalid".into(),
                direct: false,
            })
            .unwrap(),
            "{\"quoteHandle\":7,\"amountSat\":100,\"feeSat\":2,\"gatewayUrl\":\"https://gateway.invalid\",\"direct\":false}"
        );
        assert_eq!(
            serialize(&PreparedOnchainDto {
                quote_handle: 8,
                amount_sat: 200,
                fee_sat: 3,
                address: "bc1example".into(),
                uri_amount_sat: Some(200),
                amount_locked: true,
                label: Some("Alice".into()),
                message: None,
            })
            .unwrap(),
            "{\"quoteHandle\":8,\"amountSat\":200,\"feeSat\":3,\"address\":\"bc1example\",\"uriAmountSat\":200,\"amountLocked\":true,\"label\":\"Alice\",\"message\":null}"
        );
        assert_eq!(
            serialize(&ExecutedLightningDto {
                correlation_id: "00112233445566778899aabbccddeeff".into(),
                operation_id: "operation".into(),
                fee_sat: 2,
            })
            .unwrap(),
            "{\"correlationId\":\"00112233445566778899aabbccddeeff\",\"operationId\":\"operation\",\"feeSat\":2}"
        );
        assert_eq!(
            serialize(&ExecutedOnchainDto {
                correlation_id: "00112233445566778899aabbccddeeff".into(),
                operation_id: "operation".into(),
                fee_sat: 3
            })
            .unwrap(),
            "{\"correlationId\":\"00112233445566778899aabbccddeeff\",\"operationId\":\"operation\",\"feeSat\":3}"
        );
    }

    #[test]
    fn uri_amount_prefills_and_locks_while_conflicts_are_rejected() {
        assert_eq!(resolve_onchain_amount(0, Some(123)).unwrap(), 123);
        assert_eq!(resolve_onchain_amount(123, Some(123)).unwrap(), 123);
        assert!(resolve_onchain_amount(124, Some(123)).is_err());
        assert!(resolve_onchain_amount(0, None).is_err());
        assert_eq!(resolve_onchain_amount(123, None).unwrap(), 123);
    }
}
