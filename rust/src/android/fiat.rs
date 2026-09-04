use serde::Serialize;

use crate::client::ConduitClient;
use crate::currency::find_fiat_currency;
use crate::exchange::{EXCHANGE_RATE_TTL, fetch_exchange_rate};

use super::error::{AndroidError, AndroidErrorCode};
use super::handles::{HandleKind, global_get};

const MAX_AMOUNT_SAT: i64 = 2_100_000_000_000_000;
const MAX_DECIMAL_BYTES: usize = 64;

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct FiatAmountDto {
    amount_decimal: String,
    currency_code: String,
    currency_name: String,
    currency_symbol: String,
    decimal_digits: i32,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct SatsAmountDto {
    amount_sat: i64,
    currency_code: String,
}

pub(crate) fn sats_to_fiat(
    client_handle: u64,
    amount_sat: i64,
) -> Result<Option<String>, AndroidError> {
    if !(0..=MAX_AMOUNT_SAT).contains(&amount_sat) {
        return Err(invalid_amount());
    }
    let client = global_get::<ConduitClient>(client_handle, HandleKind::Client)?;
    let currency = find_fiat_currency(&client.currency_code).ok_or_else(invalid_currency)?;
    let Ok(guard) = client.exchange_rate_cache.try_lock() else {
        return Ok(None);
    };
    let Some((rate, timestamp)) = guard.as_ref() else {
        return Ok(None);
    };
    if timestamp.elapsed() >= EXCHANGE_RATE_TTL {
        return Ok(None);
    }
    validate_rate(*rate)?;
    let amount = (amount_sat as f64 / 100_000_000.0) * rate;
    if !amount.is_finite() {
        return Err(rate_unavailable());
    }
    serialize(&FiatAmountDto {
        amount_decimal: format!(
            "{amount:.precision$}",
            precision = currency.decimal_digits as usize
        ),
        currency_code: currency.code,
        currency_name: currency.name,
        currency_symbol: currency.symbol,
        decimal_digits: currency.decimal_digits,
    })
    .map(Some)
}

pub(crate) async fn fiat_to_sats(
    client_handle: u64,
    amount_decimal: String,
) -> Result<String, AndroidError> {
    let client = global_get::<ConduitClient>(client_handle, HandleKind::Client)?;
    let currency = find_fiat_currency(&client.currency_code).ok_or_else(invalid_currency)?;
    let amount = parse_decimal(&amount_decimal, currency.decimal_digits as usize)?;
    let rate = fetch_exchange_rate(
        client.exchange_rate_cache.clone(),
        client.currency_code.clone(),
    )
    .await
    .map_err(|_| rate_unavailable())?;
    let amount_sat = convert_to_sats(amount, rate)?;
    serialize(&SatsAmountDto {
        amount_sat,
        currency_code: currency.code,
    })
}

fn parse_decimal(input: &str, max_scale: usize) -> Result<f64, AndroidError> {
    if input.is_empty() || input.len() > MAX_DECIMAL_BYTES || !input.is_ascii() {
        return Err(invalid_decimal());
    }
    let (whole, fraction) = match input.split_once('.') {
        Some((whole, fraction)) => (whole, Some(fraction)),
        None => (input, None),
    };
    if whole.is_empty()
        || !whole.bytes().all(|byte| byte.is_ascii_digit())
        || (whole.len() > 1 && whole.starts_with('0'))
        || fraction.is_some_and(|value| {
            value.is_empty()
                || value.len() > max_scale
                || !value.bytes().all(|byte| byte.is_ascii_digit())
        })
    {
        return Err(invalid_decimal());
    }
    let value = input.parse::<f64>().map_err(|_| invalid_decimal())?;
    if !value.is_finite() || value <= 0.0 {
        return Err(invalid_amount());
    }
    Ok(value)
}

fn convert_to_sats(amount: f64, rate: f64) -> Result<i64, AndroidError> {
    validate_rate(rate)?;
    let sats = (amount / rate) * 100_000_000.0;
    if !sats.is_finite() || sats < 0.5 || sats > MAX_AMOUNT_SAT as f64 + 0.5 {
        return Err(invalid_amount());
    }
    // Decimal input and a binary floating-point feed rate can put an exact
    // half-sat infinitesimally below its mathematical value. Snap only values
    // within floating-point noise of a half before applying round-half-away.
    let half = (sats * 2.0).round() / 2.0;
    let tolerance = f64::EPSILON * sats.abs().max(1.0) * 8.0;
    let normalized = if (sats - half).abs() <= tolerance {
        half
    } else {
        sats
    };
    let rounded = normalized.round();
    if !(1.0..=MAX_AMOUNT_SAT as f64).contains(&rounded) {
        return Err(invalid_amount());
    }
    Ok(rounded as i64)
}

fn validate_rate(rate: f64) -> Result<(), AndroidError> {
    if !rate.is_finite() || rate <= 0.0 {
        return Err(rate_unavailable());
    }
    Ok(())
}

fn invalid_decimal() -> AndroidError {
    AndroidError::new(
        AndroidErrorCode::InvalidArgument,
        "The fiat amount format is invalid.",
        false,
    )
}

fn invalid_amount() -> AndroidError {
    AndroidError::new(
        AndroidErrorCode::InvalidArgument,
        "The amount is outside the supported range.",
        false,
    )
}

fn invalid_currency() -> AndroidError {
    AndroidError::new(
        AndroidErrorCode::InvalidArgument,
        "The selected currency is unsupported.",
        false,
    )
}

fn rate_unavailable() -> AndroidError {
    AndroidError::new(
        AndroidErrorCode::Internal,
        "The exchange rate is unavailable.",
        true,
    )
}

fn serialize<T: Serialize>(value: &T) -> Result<String, AndroidError> {
    serde_json::to_string(value).map_err(|_| AndroidError::internal())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn canonical_decimal_rejects_zero_negative_huge_exponent_and_nan() {
        for invalid in [
            "", "0", "0.00", "-1", "+1", "01", "1.", ".1", "1e2", "NaN", "inf",
        ] {
            assert!(parse_decimal(invalid, 2).is_err(), "accepted {invalid:?}");
        }
        assert!(parse_decimal(&"9".repeat(MAX_DECIMAL_BYTES + 1), 2).is_err());
        assert!(parse_decimal("1.234", 2).is_err());
        assert_eq!(parse_decimal("1.20", 2).unwrap(), 1.2);
    }

    #[test]
    fn conversion_rounds_to_nearest_sat_and_checks_range_and_rate() {
        assert_eq!(convert_to_sats(0.005, 1_000_000.0).unwrap(), 1);
        assert_eq!(convert_to_sats(0.014, 1_000_000.0).unwrap(), 1);
        assert_eq!(convert_to_sats(0.015, 1_000_000.0).unwrap(), 2);
        assert!(convert_to_sats(0.004, 1_000_000.0).is_err());
        assert!(convert_to_sats(f64::INFINITY, 1.0).is_err());
        assert!(convert_to_sats(1.0, 0.0).is_err());
        assert!(convert_to_sats(1.0, f64::NAN).is_err());
        assert!(convert_to_sats(MAX_AMOUNT_SAT as f64 + 1.0, 100_000_000.0).is_err());
    }

    #[test]
    fn dto_shape_uses_decimal_strings_and_currency_metadata() {
        let json = serialize(&FiatAmountDto {
            amount_decimal: "12.34".into(),
            currency_code: "USD".into(),
            currency_name: "United States Dollar".into(),
            currency_symbol: "$".into(),
            decimal_digits: 2,
        })
        .unwrap();
        assert_eq!(
            json,
            r#"{"amountDecimal":"12.34","currencyCode":"USD","currencyName":"United States Dollar","currencySymbol":"$","decimalDigits":2}"#
        );
        assert!(!json.contains("12.34,"));

        assert_eq!(
            serialize(&SatsAmountDto {
                amount_sat: 123,
                currency_code: "USD".into()
            })
            .unwrap(),
            r#"{"amountSat":123,"currencyCode":"USD"}"#
        );
    }
}
