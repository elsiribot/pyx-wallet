use serde::Serialize;

use crate::client::ConduitClient;
use crate::currency::list_fiat_currencies;

use super::error::{AndroidError, AndroidErrorCode};
use super::handles::{HandleKind, global_get};

const MAX_CURRENCIES: usize = 256;
const MAX_CURRENCY_NAME_CHARS: usize = 128;
const MAX_CURRENCY_SYMBOL_CHARS: usize = 16;
const MAX_ADDRESSES: usize = 1024;
const MAX_ADDRESS_CHARS: usize = 256;

#[derive(Serialize)]
struct CurrenciesDto {
    currencies: Vec<CurrencyDto>,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct CurrencyDto {
    code: String,
    name: String,
    symbol: String,
    decimal_digits: i32,
}

#[derive(Serialize)]
struct AddressesDto {
    addresses: Vec<AddressDto>,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct AddressDto {
    tweak_index: i64,
    address: String,
}

#[derive(Serialize)]
struct RecheckedDto {
    rechecked: bool,
}

pub(crate) fn currencies() -> Result<String, AndroidError> {
    let currencies = list_fiat_currencies()
        .into_iter()
        .take(MAX_CURRENCIES)
        .map(|currency| CurrencyDto {
            code: currency.code.chars().take(3).collect(),
            name: currency
                .name
                .chars()
                .take(MAX_CURRENCY_NAME_CHARS)
                .collect(),
            symbol: currency
                .symbol
                .chars()
                .take(MAX_CURRENCY_SYMBOL_CHARS)
                .collect(),
            decimal_digits: currency.decimal_digits,
        })
        .collect();
    serialize(&CurrenciesDto { currencies })
}

pub(crate) async fn onchain_addresses_async(client_handle: u64) -> Result<String, AndroidError> {
    let client = client(client_handle)?;
    let addresses = client
        .onchain_list_addresses()
        .await
        .into_iter()
        .filter(|(tweak_index, address)| {
            *tweak_index >= 0 && !address.is_empty() && address.len() <= MAX_ADDRESS_CHARS
        })
        .take(MAX_ADDRESSES)
        .map(|(tweak_index, address)| AddressDto {
            tweak_index,
            address,
        })
        .collect();
    serialize(&AddressesDto { addresses })
}

pub(crate) async fn recheck_onchain_address_async(
    client_handle: u64,
    tweak_index: i64,
) -> Result<String, AndroidError> {
    validate_tweak_index(tweak_index)?;
    let client = client(client_handle)?;
    client
        .onchain_recheck_address(tweak_index)
        .await
        .map_err(|_| AndroidError::internal())?;
    serialize(&RecheckedDto { rechecked: true })
}

fn client(handle: u64) -> Result<std::sync::Arc<ConduitClient>, AndroidError> {
    global_get(handle, HandleKind::Client)
}

fn validate_tweak_index(tweak_index: i64) -> Result<(), AndroidError> {
    if tweak_index < 0 {
        return Err(AndroidError::new(
            AndroidErrorCode::InvalidArgument,
            "The on-chain address index is invalid.",
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
    fn tweak_index_must_be_nonnegative() {
        assert!(validate_tweak_index(0).is_ok());
        assert!(validate_tweak_index(i64::MAX).is_ok());
        assert!(validate_tweak_index(-1).is_err());
    }

    #[test]
    fn response_json_matches_contract() {
        let currencies = serialize(&CurrenciesDto {
            currencies: vec![CurrencyDto {
                code: "USD".into(),
                name: "United States Dollar".into(),
                symbol: "$".into(),
                decimal_digits: 2,
            }],
        })
        .unwrap();
        crate::android::assert_read_dto_snapshot("catalog.currencies", &currencies);
        assert_eq!(
            currencies,
            "{\"currencies\":[{\"code\":\"USD\",\"name\":\"United States Dollar\",\"symbol\":\"$\",\"decimalDigits\":2}]}"
        );
        let addresses = serialize(&AddressesDto {
            addresses: vec![AddressDto {
                tweak_index: 7,
                address: "bc1example".into(),
            }],
        })
        .unwrap();
        crate::android::assert_read_dto_snapshot("catalog.addresses", &addresses);
        assert_eq!(
            addresses,
            "{\"addresses\":[{\"tweakIndex\":7,\"address\":\"bc1example\"}]}"
        );
        assert_eq!(
            serialize(&RecheckedDto { rechecked: true }).unwrap(),
            "{\"rechecked\":true}"
        );
    }

    #[test]
    fn real_currency_catalog_is_bounded_and_well_formed() {
        let json = currencies().unwrap();
        assert!(json.len() < 64 * 1024);
        let value: serde_json::Value = serde_json::from_str(&json).unwrap();
        let currencies = value["currencies"].as_array().unwrap();
        assert!(!currencies.is_empty());
        assert!(currencies.len() <= MAX_CURRENCIES);
        assert!(currencies.iter().all(|currency| {
            currency["code"]
                .as_str()
                .is_some_and(|code| code.len() == 3)
        }));
    }
}
