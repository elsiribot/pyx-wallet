use crate::{BitcoinAddressWrapper, parse_bitcoin_address};

use super::error::{AndroidError, AndroidErrorCode};

const MAX_URI_BYTES: usize = 64 * 1024;
const MAX_METADATA_CHARS: usize = 256;
const MAX_AMOUNT_SAT: u64 = 2_100_000_000_000_000;

pub(crate) struct BitcoinDestination {
    pub(crate) address: BitcoinAddressWrapper,
    pub(crate) canonical_address: String,
    pub(crate) amount_sat: Option<i64>,
    pub(crate) label: Option<String>,
    pub(crate) message: Option<String>,
    pub(crate) is_uri: bool,
}

pub(crate) fn parse(value: &str) -> Result<BitcoinDestination, AndroidError> {
    if value.is_empty() || value.len() > MAX_URI_BYTES {
        return Err(invalid_destination());
    }
    let is_uri = value
        .get(..8)
        .is_some_and(|prefix| prefix.eq_ignore_ascii_case("bitcoin:"));
    let (address_text, query) = if is_uri {
        let body = &value[8..];
        if body.contains('#') {
            return Err(invalid_destination());
        }
        body.split_once('?')
            .map_or((body, None), |(address, query)| (address, Some(query)))
    } else {
        (value, None)
    };
    if address_text.is_empty() || address_text.contains('%') {
        return Err(invalid_destination());
    }
    let address = parse_bitcoin_address(address_text).ok_or_else(invalid_destination)?;
    let canonical_address = address.to_string();
    let mut amount_sat = None;
    let mut label = None;
    let mut message = None;
    let mut label_seen = false;
    let mut message_seen = false;
    if let Some(query) = query {
        for parameter in query.split('&').filter(|parameter| !parameter.is_empty()) {
            let (raw_key, raw_value) = parameter.split_once('=').unwrap_or((parameter, ""));
            let key = percent_decode(raw_key)?;
            let value = percent_decode(raw_value)?;
            match key.as_str() {
                "amount" => {
                    if amount_sat.is_some() {
                        return Err(invalid_amount());
                    }
                    amount_sat = Some(parse_btc_amount(&value)?);
                }
                "label" => {
                    if label_seen {
                        return Err(invalid_destination());
                    }
                    label_seen = true;
                    label = metadata(value)?;
                }
                "message" => {
                    if message_seen {
                        return Err(invalid_destination());
                    }
                    message_seen = true;
                    message = metadata(value)?;
                }
                required if required.starts_with("req-") => {
                    return Err(AndroidError::new(
                        AndroidErrorCode::InvalidArgument,
                        "The bitcoin URI requires an unsupported feature.",
                        false,
                    ));
                }
                _ => {}
            }
        }
    }
    Ok(BitcoinDestination {
        address,
        canonical_address,
        amount_sat,
        label,
        message,
        is_uri,
    })
}

fn metadata(value: String) -> Result<Option<String>, AndroidError> {
    if value.chars().count() > MAX_METADATA_CHARS {
        return Err(invalid_destination());
    }
    Ok((!value.is_empty()).then_some(value))
}

fn parse_btc_amount(value: &str) -> Result<i64, AndroidError> {
    let (whole, fraction) = value
        .split_once('.')
        .map_or((value, ""), |(whole, fraction)| (whole, fraction));
    if whole.is_empty()
        || !whole.bytes().all(|byte| byte.is_ascii_digit())
        || (whole.len() > 1 && whole.starts_with('0'))
        || fraction.len() > 8
        || !fraction.bytes().all(|byte| byte.is_ascii_digit())
        || (value.contains('.') && fraction.is_empty())
    {
        return Err(invalid_amount());
    }
    let btc = whole.parse::<u64>().map_err(|_| invalid_amount())?;
    let fraction_value = if fraction.is_empty() {
        0
    } else {
        fraction.parse::<u64>().map_err(|_| invalid_amount())?
            * 10_u64.pow((8 - fraction.len()) as u32)
    };
    let sats = btc
        .checked_mul(100_000_000)
        .and_then(|value| value.checked_add(fraction_value))
        .filter(|value| (1..=MAX_AMOUNT_SAT).contains(value))
        .ok_or_else(invalid_amount)?;
    i64::try_from(sats).map_err(|_| invalid_amount())
}

fn percent_decode(value: &str) -> Result<String, AndroidError> {
    let bytes = value.as_bytes();
    let mut decoded = Vec::with_capacity(bytes.len());
    let mut index = 0;
    while index < bytes.len() {
        match bytes[index] {
            b'%' => {
                if index + 2 >= bytes.len() {
                    return Err(invalid_destination());
                }
                let high = hex(bytes[index + 1]).ok_or_else(invalid_destination)?;
                let low = hex(bytes[index + 2]).ok_or_else(invalid_destination)?;
                decoded.push(high << 4 | low);
                index += 3;
            }
            b'+' => {
                decoded.push(b' ');
                index += 1;
            }
            byte => {
                decoded.push(byte);
                index += 1;
            }
        }
    }
    String::from_utf8(decoded).map_err(|_| invalid_destination())
}

const fn hex(value: u8) -> Option<u8> {
    match value {
        b'0'..=b'9' => Some(value - b'0'),
        b'a'..=b'f' => Some(value - b'a' + 10),
        b'A'..=b'F' => Some(value - b'A' + 10),
        _ => None,
    }
}

fn invalid_destination() -> AndroidError {
    AndroidError::new(
        AndroidErrorCode::InvalidArgument,
        "The payment destination is invalid.",
        false,
    )
}

fn invalid_amount() -> AndroidError {
    AndroidError::new(
        AndroidErrorCode::InvalidArgument,
        "The bitcoin URI amount is invalid.",
        false,
    )
}

#[cfg(test)]
mod tests {
    use super::*;

    const ADDRESS: &str = "bc1qxy2kgdygjrsqtzq2n0yrf2493p83kkfjhx0wlh";

    #[test]
    fn parses_exact_amount_and_bounded_metadata_without_float() {
        let parsed = parse(&format!(
            "bitcoin:{ADDRESS}?amount=1.23456789&label=Alice%20Shop&message=Order+42"
        ))
        .unwrap();
        assert_eq!(parsed.canonical_address, ADDRESS);
        assert_eq!(parsed.amount_sat, Some(123_456_789));
        assert_eq!(parsed.label.as_deref(), Some("Alice Shop"));
        assert_eq!(parsed.message.as_deref(), Some("Order 42"));
        assert!(parsed.is_uri);
    }

    #[test]
    fn rejects_duplicate_bad_or_unsupported_required_parameters() {
        for query in [
            "amount=1&amount=1",
            "amount=1&amount=2",
            "amount=0",
            "amount=-1",
            "amount=0.000000001",
            "amount=21000000.00000001",
            "amount=1e-8",
            "label=bad%2",
            "label=%FF",
            "label=&label=again",
            "req-extra=yes",
        ] {
            assert!(
                parse(&format!("bitcoin:{ADDRESS}?{query}")).is_err(),
                "accepted {query}"
            );
        }
    }

    #[test]
    fn preserves_plain_addresses() {
        let parsed = parse(ADDRESS).unwrap();
        assert_eq!(parsed.canonical_address, ADDRESS);
        assert_eq!(parsed.amount_sat, None);
        assert!(!parsed.is_uri);
    }
}
