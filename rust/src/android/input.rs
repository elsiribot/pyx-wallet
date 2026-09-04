use serde::Serialize;

use crate::client::ConduitClient;
use crate::{parse_bolt11_invoice, parse_ecash, parse_invite_code, parse_lnurl};

use super::bip21;
use super::error::{AndroidError, AndroidErrorCode};
use super::handles::{HandleKind, global_get};

const MAX_INPUT_BYTES: usize = 64 * 1024;

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
enum InputType {
    Ecash,
    Invite,
    Lightning,
    Bitcoin,
    Lnurl,
    Unknown,
}

impl InputType {
    const fn as_str(self) -> &'static str {
        match self {
            Self::Ecash => "ecash",
            Self::Invite => "invite",
            Self::Lightning => "lightning",
            Self::Bitcoin => "bitcoin",
            Self::Lnurl => "lnurl",
            Self::Unknown => "unknown",
        }
    }
}

#[derive(Serialize)]
struct ClassificationDto {
    #[serde(rename = "type")]
    input_type: &'static str,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct BitcoinPaymentDto {
    address: String,
    amount_sat: Option<i64>,
    label: Option<String>,
    message: Option<String>,
    is_uri: bool,
}

#[derive(Serialize)]
struct LnurlReceiveDto {
    #[serde(rename = "type")]
    receive_type: &'static str,
    payload: String,
}

pub(crate) fn classify(payload: &str) -> Result<String, AndroidError> {
    validate_input(payload)?;
    let input_type = if parse_ecash(payload).is_some() {
        InputType::Ecash
    } else if parse_invite_code(payload).is_some() {
        InputType::Invite
    } else if parse_bolt11_invoice(payload).is_some() {
        InputType::Lightning
    } else if bip21::parse(payload).is_ok() {
        InputType::Bitcoin
    } else if parse_lnurl(payload).is_some() {
        InputType::Lnurl
    } else {
        InputType::Unknown
    };
    serialize(&ClassificationDto {
        input_type: input_type.as_str(),
    })
}

pub(crate) fn parse_bitcoin_payment(payload: &str) -> Result<String, AndroidError> {
    validate_input(payload)?;
    let payment = bip21::parse(payload)?;
    serialize(&BitcoinPaymentDto {
        address: payment.canonical_address,
        amount_sat: payment.amount_sat,
        label: payment.label,
        message: payment.message,
        is_uri: payment.is_uri,
    })
}

pub(crate) async fn receive_lnurl_async(client_handle: u64) -> Result<String, AndroidError> {
    let client = global_get::<ConduitClient>(client_handle, HandleKind::Client)?;
    let payload = client.lnurl().await.map_err(|_| AndroidError::internal())?;
    if payload.is_empty() || payload.len() > MAX_INPUT_BYTES {
        return Err(AndroidError::internal());
    }
    serialize(&LnurlReceiveDto {
        receive_type: "lnurl",
        payload,
    })
}

#[cfg(test)]
fn classify_matches(
    ecash: bool,
    invite: bool,
    lightning: bool,
    bitcoin: bool,
    lnurl: bool,
) -> InputType {
    if ecash {
        InputType::Ecash
    } else if invite {
        InputType::Invite
    } else if lightning {
        InputType::Lightning
    } else if bitcoin {
        InputType::Bitcoin
    } else if lnurl {
        InputType::Lnurl
    } else {
        InputType::Unknown
    }
}

fn validate_input(payload: &str) -> Result<(), AndroidError> {
    if payload.len() > MAX_INPUT_BYTES {
        return Err(AndroidError::new(
            AndroidErrorCode::InvalidArgument,
            "The input is too large.",
            false,
        ));
    }
    Ok(())
}

fn serialize<T: Serialize>(value: &T) -> Result<String, AndroidError> {
    serde_json::to_string(value).map_err(|_| AndroidError::internal())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn ecash_wins_fedimint_prefix_ambiguity_and_precedence_is_stable() {
        assert_eq!(
            classify_matches(true, true, true, true, true),
            InputType::Ecash
        );
        assert_eq!(
            classify_matches(false, true, true, true, true),
            InputType::Invite
        );
        assert_eq!(
            classify_matches(false, false, true, true, true),
            InputType::Lightning
        );
        assert_eq!(
            classify_matches(false, false, false, true, true),
            InputType::Bitcoin
        );
        assert_eq!(
            classify_matches(false, false, false, false, true),
            InputType::Lnurl
        );
        assert_eq!(
            classify_matches(false, false, false, false, false),
            InputType::Unknown
        );
    }

    #[test]
    fn classification_is_bounded_and_does_not_echo_input() {
        assert!(validate_input(&"x".repeat(MAX_INPUT_BYTES)).is_ok());
        assert!(validate_input(&"x".repeat(MAX_INPUT_BYTES + 1)).is_err());
        let payload = "not a payment secret";
        let json = classify(payload).unwrap();
        crate::android::assert_read_dto_snapshot("input.classificationUnknown", &json);
        assert_eq!(json, "{\"type\":\"unknown\"}");
        assert!(!json.contains(payload));
        let uri = "bitcoin:bc1qxy2kgdygjrsqtzq2n0yrf2493p83kkfjhx0wlh?amount=1&label=Secret";
        assert_eq!(classify(uri).unwrap(), "{\"type\":\"bitcoin\"}");
        assert!(!classify(uri).unwrap().contains("Secret"));
    }

    #[test]
    fn response_json_matches_receive_contract() {
        assert_eq!(
            serialize(&LnurlReceiveDto {
                receive_type: "lnurl",
                payload: "LNURL1EXAMPLE".into(),
            })
            .unwrap(),
            "{\"type\":\"lnurl\",\"payload\":\"LNURL1EXAMPLE\"}"
        );
        let bitcoin = parse_bitcoin_payment(
            "bitcoin:bc1qxy2kgdygjrsqtzq2n0yrf2493p83kkfjhx0wlh?amount=0.00000001&label=Alice",
        )
        .unwrap();
        crate::android::assert_read_dto_snapshot("input.bitcoinPayment", &bitcoin);
        assert_eq!(
            bitcoin,
            "{\"address\":\"bc1qxy2kgdygjrsqtzq2n0yrf2493p83kkfjhx0wlh\",\"amountSat\":1,\"label\":\"Alice\",\"message\":null,\"isUri\":true}"
        );
    }
}
