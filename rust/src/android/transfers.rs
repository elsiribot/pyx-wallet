use serde::Serialize;

use crate::client::ConduitClient;
use crate::{parse_bolt11_invoice, parse_ecash};

use super::error::{AndroidError, AndroidErrorCode};
use super::handles::{HandleKind, global_get};
use super::reconciliation::{self, OperationKind};

const MAX_AMOUNT_SAT: i64 = 2_100_000_000_000_000;
pub(crate) const MAX_PAYMENT_INPUT_BYTES: usize = 64 * 1024;

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct LightningReceiveDto {
    #[serde(rename = "type")]
    receive_type: &'static str,
    payload: String,
    amount_sat: i64,
    fee_sat: i64,
    gateway_url: String,
    expires_at_epoch_seconds: i64,
}

fn invoice_expiry_epoch_seconds(invoice: &str) -> Result<i64, AndroidError> {
    let invoice = parse_bolt11_invoice(invoice).ok_or_else(AndroidError::internal)?;
    let created = invoice.0.duration_since_epoch().as_secs();
    let expiry = invoice.0.expiry_time().as_secs();
    expiry_epoch_seconds(created, expiry)
}

fn expiry_epoch_seconds(created: u64, expiry: u64) -> Result<i64, AndroidError> {
    let expires = created
        .checked_add(expiry)
        .ok_or_else(AndroidError::internal)?;
    i64::try_from(expires).map_err(|_| AndroidError::internal())
}

#[derive(Serialize)]
struct OnchainReceiveDto {
    #[serde(rename = "type")]
    receive_type: &'static str,
    payload: String,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct EcashCreateDto {
    correlation_id: String,
    operation_id: Option<String>,
    payload: String,
    amount_sat: i64,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct EcashClaimDto {
    correlation_id: String,
    operation_id: Option<String>,
    amount_sat: i64,
}

pub(crate) async fn receive_lightning_async(
    client_handle: u64,
    amount_sat: i64,
) -> Result<String, AndroidError> {
    validate_amount(amount_sat)?;
    let client = client(client_handle)?;
    let receive = client
        .ln_receive(amount_sat)
        .await
        .map_err(|_| AndroidError::internal())?;
    let expires_at_epoch_seconds = invoice_expiry_epoch_seconds(&receive.invoice)?;
    serialize(&LightningReceiveDto {
        receive_type: "lightning",
        payload: receive.invoice,
        amount_sat,
        fee_sat: receive.fee_sats,
        gateway_url: receive.gateway_url,
        expires_at_epoch_seconds,
    })
}

pub(crate) async fn receive_onchain_async(client_handle: u64) -> Result<String, AndroidError> {
    let client = client(client_handle)?;
    let payload = match client.wallet_v2_receive().await {
        Some(address) => address,
        None => client
            .onchain_receive_address()
            .await
            .map_err(|_| AndroidError::internal())?,
    };
    serialize(&OnchainReceiveDto {
        receive_type: "onchain",
        payload,
    })
}

pub(crate) async fn create_ecash_async(
    client_handle: u64,
    amount_sat: i64,
    requested_correlation_id: String,
) -> Result<String, AndroidError> {
    validate_amount(amount_sat)?;
    let client = client(client_handle)?;
    let (correlation_id, _submission) = reconciliation::start_with_correlation(
        &client,
        OperationKind::EcashCreate,
        amount_sat,
        0,
        &[],
        &requested_correlation_id,
    )
    .await?;
    let (operation_id, ecash) = client
        .ecash_send_with_meta(amount_sat, reconciliation::marker(&correlation_id))
        .await
        .map_err(|_| AndroidError::internal())?;
    if let Some(operation_id) = operation_id {
        reconciliation::attach_operation(&client, &correlation_id, operation_id).await?;
    }
    serialize(&EcashCreateDto {
        correlation_id,
        operation_id: operation_id.map(|id| id.fmt_full().to_string()),
        payload: ecash.to_string(),
        amount_sat,
    })
}

pub(crate) async fn claim_ecash_async(
    client_handle: u64,
    payload: String,
    requested_correlation_id: String,
) -> Result<String, AndroidError> {
    validate_payload(&payload)?;
    let ecash = parse_ecash(&payload).ok_or_else(invalid_payment_input)?;
    let amount_sat = ecash.amount_sats();
    validate_amount(amount_sat)?;
    let client = client(client_handle)?;
    let (correlation_id, _submission) = reconciliation::start_with_correlation(
        &client,
        OperationKind::EcashClaim,
        amount_sat,
        0,
        &[],
        &requested_correlation_id,
    )
    .await?;
    let operation_id = client
        .ecash_receive_with_meta(&ecash, reconciliation::marker(&correlation_id))
        .await
        .map_err(|_| AndroidError::internal())?;
    if let Some(operation_id) = operation_id {
        reconciliation::attach_operation(&client, &correlation_id, operation_id).await?;
    }
    serialize(&EcashClaimDto {
        correlation_id,
        operation_id: operation_id.map(|id| id.fmt_full().to_string()),
        amount_sat,
    })
}

fn client(handle: u64) -> Result<std::sync::Arc<ConduitClient>, AndroidError> {
    global_get(handle, HandleKind::Client)
}

pub(crate) fn validate_amount(amount_sat: i64) -> Result<(), AndroidError> {
    if !(1..=MAX_AMOUNT_SAT).contains(&amount_sat) {
        return Err(AndroidError::new(
            AndroidErrorCode::InvalidArgument,
            "The payment amount is invalid.",
            false,
        ));
    }
    Ok(())
}

pub(crate) fn validate_payload(payload: &str) -> Result<(), AndroidError> {
    if payload.is_empty() || payload.len() > MAX_PAYMENT_INPUT_BYTES {
        return Err(invalid_payment_input());
    }
    Ok(())
}

fn invalid_payment_input() -> AndroidError {
    AndroidError::new(
        AndroidErrorCode::InvalidArgument,
        "The payment request is invalid.",
        false,
    )
}

fn serialize<T: Serialize>(value: &T) -> Result<String, AndroidError> {
    serde_json::to_string(value).map_err(|_| AndroidError::internal())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn amounts_are_positive_and_bounded() {
        assert!(validate_amount(1).is_ok());
        assert!(validate_amount(MAX_AMOUNT_SAT).is_ok());
        assert!(validate_amount(0).is_err());
        assert!(validate_amount(-1).is_err());
        assert!(validate_amount(MAX_AMOUNT_SAT + 1).is_err());
    }

    #[test]
    fn payloads_are_nonempty_and_bounded() {
        assert!(validate_payload("invoice").is_ok());
        assert!(validate_payload("").is_err());
        assert!(validate_payload(&"x".repeat(MAX_PAYMENT_INPUT_BYTES)).is_ok());
        assert!(validate_payload(&"x".repeat(MAX_PAYMENT_INPUT_BYTES + 1)).is_err());
    }

    #[test]
    fn response_json_matches_native_contract() {
        assert_eq!(
            serialize(&LightningReceiveDto {
                receive_type: "lightning",
                payload: "lnbc".into(),
                amount_sat: 100,
                fee_sat: 2,
                gateway_url: "https://gateway.invalid".into(),
                expires_at_epoch_seconds: 1_800_000_000,
            })
            .unwrap(),
            "{\"type\":\"lightning\",\"payload\":\"lnbc\",\"amountSat\":100,\"feeSat\":2,\"gatewayUrl\":\"https://gateway.invalid\",\"expiresAtEpochSeconds\":1800000000}"
        );
        assert_eq!(
            serialize(&EcashClaimDto {
                correlation_id: "00112233445566778899aabbccddeeff".into(),
                operation_id: None,
                amount_sat: 42
            })
            .unwrap(),
            "{\"correlationId\":\"00112233445566778899aabbccddeeff\",\"operationId\":null,\"amountSat\":42}"
        );
        assert_eq!(
            serialize(&EcashCreateDto {
                correlation_id: "00112233445566778899aabbccddeeff".into(),
                operation_id: None,
                payload: "cash".into(),
                amount_sat: 5,
            })
            .unwrap(),
            "{\"correlationId\":\"00112233445566778899aabbccddeeff\",\"operationId\":null,\"payload\":\"cash\",\"amountSat\":5}"
        );
    }

    #[test]
    fn invoice_expiry_is_checked_epoch_arithmetic() {
        assert_eq!(
            expiry_epoch_seconds(1_700_000_000, 3_600).unwrap(),
            1_700_003_600
        );
        assert!(expiry_epoch_seconds(u64::MAX, 1).is_err());
        assert!(expiry_epoch_seconds(i64::MAX as u64, 1).is_err());
    }

    #[test]
    fn json_serialization_escapes_payloads() {
        let json = serialize(&OnchainReceiveDto {
            receive_type: "onchain",
            payload: "quote\"newline\n".into(),
        })
        .unwrap();
        assert_eq!(
            json,
            "{\"type\":\"onchain\",\"payload\":\"quote\\\"newline\\n\"}"
        );
    }
}
