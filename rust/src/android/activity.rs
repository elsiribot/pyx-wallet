use serde::Serialize;
use sha2::{Digest, Sha256};
use std::collections::HashSet;

use crate::client::ConduitClient;
use crate::events::{ConduitPayment, PaymentType};

use super::error::{AndroidError, AndroidErrorCode};
use super::handles::{HandleKind, global_get};

const MAX_OPERATION_ID_BYTES: usize = 256;
const MAX_PAGE_SIZE: usize = 50;
const CURSOR_PREFIX: &str = "pyx1.";

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct PaymentDetailsDto {
    operation_id: String,
    incoming: bool,
    #[serde(rename = "type")]
    payment_type: &'static str,
    amount_sat: i64,
    fee_sat: Option<i64>,
    timestamp_millis: i64,
    status: &'static str,
    ecash: Option<String>,
    txid: Option<String>,
    address: Option<String>,
    preimage: Option<String>,
    fiat_amount: Option<String>,
    fiat_currency_code: Option<String>,
}

#[derive(Serialize)]
struct PaymentDetailsResponseDto {
    payment: PaymentDetailsDto,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct PaymentPageDto {
    payments: Vec<PaymentSummaryDto>,
    next_cursor: Option<String>,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct PaymentSummaryDto {
    operation_id: String,
    direction: &'static str,
    #[serde(rename = "type")]
    payment_type: &'static str,
    amount_sat: i64,
    fee_sat: Option<i64>,
    timestamp_millis: i64,
    status: &'static str,
    fiat_amount: Option<String>,
    fiat_currency_code: Option<String>,
}

pub(crate) async fn payment_history_page_async(
    client_handle: u64,
    cursor: String,
    page_size: i32,
) -> Result<String, AndroidError> {
    let client = global_get::<ConduitClient>(client_handle, HandleKind::Client)?;
    payment_history_page_json(client.get_payment_history().await, &cursor, page_size)
}

fn payment_history_page_json(
    mut payments: Vec<ConduitPayment>,
    cursor: &str,
    page_size: i32,
) -> Result<String, AndroidError> {
    let page_size = usize::try_from(page_size)
        .ok()
        .filter(|size| *size > 0 && *size <= MAX_PAGE_SIZE)
        .ok_or_else(invalid_page)?;
    payments.retain(valid_summary);
    payments.sort_by(|left, right| {
        right
            .timestamp
            .cmp(&left.timestamp)
            .then_with(|| left.operation_id.cmp(&right.operation_id))
    });
    let mut seen = HashSet::new();
    payments.retain(|payment| seen.insert(payment.operation_id.clone()));

    let start = if cursor.is_empty() {
        0
    } else {
        let cursor = parse_cursor(cursor)?;
        payments
            .iter()
            .position(|payment| cursor_for(payment) == cursor)
            .map(|index| index + 1)
            .ok_or_else(invalid_cursor)?
    };
    let end = start.saturating_add(page_size).min(payments.len());
    let page = &payments[start..end];
    let next_cursor = (end < payments.len()).then(|| cursor_for(&payments[end - 1]));
    serialize(&PaymentPageDto {
        payments: page.iter().cloned().map(PaymentSummaryDto::from).collect(),
        next_cursor,
    })
}

fn valid_summary(payment: &ConduitPayment) -> bool {
    !payment.operation_id.is_empty()
        && payment.operation_id.len() <= MAX_OPERATION_ID_BYTES
        && payment.amount_sats >= 0
        && payment.fee_sats.is_none_or(|fee| fee >= 0)
        && payment.timestamp >= 0
}

fn cursor_for(payment: &ConduitPayment) -> String {
    let digest = Sha256::digest(payment.operation_id.as_bytes());
    let hash = digest
        .iter()
        .map(|byte| format!("{byte:02x}"))
        .collect::<String>();
    format!("{CURSOR_PREFIX}{:016x}.{hash}", payment.timestamp)
}

fn parse_cursor(cursor: &str) -> Result<String, AndroidError> {
    let body = cursor
        .strip_prefix(CURSOR_PREFIX)
        .ok_or_else(invalid_cursor)?;
    let (timestamp, hash) = body.split_once('.').ok_or_else(invalid_cursor)?;
    if timestamp.len() != 16
        || !timestamp.bytes().all(|byte| byte.is_ascii_hexdigit())
        || hash.len() != 64
        || !hash.bytes().all(|byte| byte.is_ascii_hexdigit())
        || cursor.len() != CURSOR_PREFIX.len() + 16 + 1 + 64
    {
        return Err(invalid_cursor());
    }
    Ok(cursor.to_ascii_lowercase())
}

fn invalid_cursor() -> AndroidError {
    AndroidError::new(
        AndroidErrorCode::InvalidArgument,
        "The activity cursor is invalid.",
        false,
    )
}

fn invalid_page() -> AndroidError {
    AndroidError::new(
        AndroidErrorCode::InvalidArgument,
        "The activity page size is invalid.",
        false,
    )
}

pub(crate) async fn payment_details_async(
    client_handle: u64,
    operation_id: String,
) -> Result<String, AndroidError> {
    validate_operation_id(&operation_id)?;
    let client = global_get::<ConduitClient>(client_handle, HandleKind::Client)?;
    let payment = client
        .get_payment_history()
        .await
        .into_iter()
        .find(|payment| payment.operation_id == operation_id)
        .ok_or_else(payment_not_found)?;
    serialize(&PaymentDetailsResponseDto {
        payment: PaymentDetailsDto::from(payment),
    })
}

fn validate_operation_id(operation_id: &str) -> Result<(), AndroidError> {
    if operation_id.is_empty() || operation_id.len() > MAX_OPERATION_ID_BYTES {
        return Err(AndroidError::new(
            AndroidErrorCode::InvalidArgument,
            "The payment identifier is invalid.",
            false,
        ));
    }
    Ok(())
}

fn payment_not_found() -> AndroidError {
    AndroidError::new(
        AndroidErrorCode::InvalidArgument,
        "The payment could not be found.",
        false,
    )
}

fn serialize<T: Serialize>(value: &T) -> Result<String, AndroidError> {
    serde_json::to_string(value).map_err(|_| AndroidError::internal())
}

impl From<ConduitPayment> for PaymentDetailsDto {
    fn from(payment: ConduitPayment) -> Self {
        Self {
            operation_id: payment.operation_id,
            incoming: payment.incoming,
            payment_type: match payment.payment_type {
                PaymentType::Lightning => "lightning",
                PaymentType::Bitcoin => "onchain",
                PaymentType::Ecash => "ecash",
            },
            amount_sat: payment.amount_sats,
            fee_sat: payment.fee_sats,
            timestamp_millis: payment.timestamp,
            status: match payment.success {
                None => "pending",
                Some(true) => "succeeded",
                Some(false) => "failed",
            },
            ecash: payment.ecash,
            txid: payment.txid,
            address: payment.address,
            preimage: payment.preimage,
            fiat_amount: payment
                .fiat_amount
                .filter(|amount| amount.is_finite())
                .map(|amount| amount.to_string()),
            fiat_currency_code: payment.fiat_currency_code.filter(|code| {
                code.len() == 3 && code.bytes().all(|byte| byte.is_ascii_uppercase())
            }),
        }
    }
}

impl From<ConduitPayment> for PaymentSummaryDto {
    fn from(payment: ConduitPayment) -> Self {
        let fiat_amount = payment.fiat_amount.filter(|amount| amount.is_finite());
        let fiat_currency_code = payment
            .fiat_currency_code
            .filter(|code| code.len() == 3 && code.bytes().all(|byte| byte.is_ascii_uppercase()));
        let has_fiat_pair = fiat_amount.is_some() && fiat_currency_code.is_some();
        Self {
            operation_id: payment.operation_id,
            direction: if payment.incoming {
                "incoming"
            } else {
                "outgoing"
            },
            payment_type: match payment.payment_type {
                PaymentType::Lightning => "lightning",
                PaymentType::Bitcoin => "onchain",
                PaymentType::Ecash => "ecash",
            },
            amount_sat: payment.amount_sats,
            fee_sat: payment.fee_sats,
            timestamp_millis: payment.timestamp,
            status: match payment.success {
                None => "pending",
                Some(true) => "succeeded",
                Some(false) => "failed",
            },
            fiat_amount: has_fiat_pair.then(|| fiat_amount.unwrap().to_string()),
            fiat_currency_code: has_fiat_pair.then_some(fiat_currency_code.unwrap()),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn payment() -> ConduitPayment {
        ConduitPayment {
            operation_id: "operation".into(),
            incoming: true,
            payment_type: PaymentType::Ecash,
            amount_sats: 42,
            fee_sats: Some(1),
            timestamp: 123,
            success: Some(true),
            ecash: Some("cash\"\nvalue".into()),
            txid: Some("txid".into()),
            preimage: Some("preimage".into()),
            address: Some("address".into()),
            fiat_amount: Some(1.25),
            fiat_currency_code: Some("USD".into()),
        }
    }

    #[test]
    fn operation_id_is_exact_and_bounded() {
        assert!(validate_operation_id("operation").is_ok());
        assert!(validate_operation_id("").is_err());
        assert!(validate_operation_id(&"x".repeat(MAX_OPERATION_ID_BYTES)).is_ok());
        assert!(validate_operation_id(&"x".repeat(MAX_OPERATION_ID_BYTES + 1)).is_err());

        let payments = [payment()];
        assert!(
            payments
                .iter()
                .any(|payment| payment.operation_id == "operation")
        );
        assert!(
            !payments
                .iter()
                .any(|payment| payment.operation_id == "Operation")
        );
        assert!(
            !payments
                .iter()
                .any(|payment| payment.operation_id == "oper")
        );
    }

    #[test]
    fn detail_json_returns_and_escapes_explicit_sensitive_fields() {
        let json = serialize(&PaymentDetailsResponseDto {
            payment: PaymentDetailsDto::from(payment()),
        })
        .unwrap();
        crate::android::assert_read_dto_snapshot("activity.paymentDetails", &json);
        assert_eq!(
            json,
            "{\"payment\":{\"operationId\":\"operation\",\"incoming\":true,\"type\":\"ecash\",\"amountSat\":42,\"feeSat\":1,\"timestampMillis\":123,\"status\":\"succeeded\",\"ecash\":\"cash\\\"\\nvalue\",\"txid\":\"txid\",\"address\":\"address\",\"preimage\":\"preimage\",\"fiatAmount\":\"1.25\",\"fiatCurrencyCode\":\"USD\"}}"
        );
    }

    #[test]
    fn unavailable_or_invalid_optional_values_serialize_as_null() {
        let mut payment = payment();
        payment.ecash = None;
        payment.txid = None;
        payment.address = None;
        payment.preimage = None;
        payment.fiat_amount = Some(f64::NAN);
        payment.fiat_currency_code = Some("invalid".into());
        let json = serialize(&PaymentDetailsDto::from(payment)).unwrap();
        assert!(json.contains("\"ecash\":null"));
        assert!(json.contains("\"fiatAmount\":null"));
        assert!(json.contains("\"fiatCurrencyCode\":null"));
    }

    #[test]
    fn history_pages_are_stable_bounded_and_have_no_duplicates() {
        let mut payments = (0..7)
            .map(|index| {
                let mut value = payment();
                value.operation_id = format!("operation-{index}");
                value.timestamp = if index < 2 { 700 } else { 700 - index };
                value
            })
            .collect::<Vec<_>>();
        payments.push(payments[2].clone());

        let first = payment_history_page_json(payments.clone(), "", 3).unwrap();
        let first: serde_json::Value = serde_json::from_str(&first).unwrap();
        assert_eq!(first["payments"].as_array().unwrap().len(), 3);
        assert_eq!(first["payments"][0]["operationId"], "operation-0");
        assert_eq!(first["payments"][1]["operationId"], "operation-1");
        let cursor = first["nextCursor"].as_str().unwrap();
        assert!(!cursor.contains("operation"));

        // A new head event does not shift the keyset boundary.
        let mut head = payment();
        head.operation_id = "new-live-head".into();
        head.timestamp = 999;
        payments.push(head);
        let second = payment_history_page_json(payments, cursor, 3).unwrap();
        let second: serde_json::Value = serde_json::from_str(&second).unwrap();
        let second_ids = second["payments"]
            .as_array()
            .unwrap()
            .iter()
            .map(|item| item["operationId"].as_str().unwrap())
            .collect::<Vec<_>>();
        assert_eq!(
            second_ids,
            vec!["operation-3", "operation-4", "operation-5"]
        );
        assert!(second["nextCursor"].is_string());
    }

    #[test]
    fn history_page_marks_end_and_accepts_empty_history() {
        let final_page: serde_json::Value = serde_json::from_str(
            &payment_history_page_json(vec![payment()], "", MAX_PAGE_SIZE as i32).unwrap(),
        )
        .unwrap();
        assert!(final_page["nextCursor"].is_null());
        let empty: serde_json::Value =
            serde_json::from_str(&payment_history_page_json(Vec::new(), "", 1).unwrap()).unwrap();
        assert_eq!(empty["payments"].as_array().unwrap().len(), 0);
        assert!(empty["nextCursor"].is_null());
    }

    #[test]
    fn history_page_rejects_malformed_or_unknown_cursor_and_bad_size() {
        let payments = vec![payment()];
        assert!(payment_history_page_json(payments.clone(), "not-a-cursor", 10).is_err());
        let unknown = format!("{CURSOR_PREFIX}{:016x}.{}", 1, "00".repeat(32));
        assert!(payment_history_page_json(payments.clone(), &unknown, 10).is_err());
        assert!(payment_history_page_json(payments.clone(), "", 0).is_err());
        assert!(payment_history_page_json(payments, "", MAX_PAGE_SIZE as i32 + 1).is_err());
    }
}
