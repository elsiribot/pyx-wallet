use std::sync::Mutex;

use fedimint_core::base32::{FEDIMINT_PREFIX, decode_prefixed};
use fedimint_fountain::Fragment;
use serde::Serialize;

use crate::{ECashDecoder, ECashEncoder, parse_ecash};

use super::error::{AndroidError, AndroidErrorCode};
use super::handles::{HandleKind, global_close, global_get, global_insert, global_validate_type};

const MAX_ECASH_PAYLOAD_BYTES: usize = 64 * 1024;
const MAX_FRAGMENT_BYTES: usize = 4 * 1024;

struct EncoderEntry {
    encoder: Mutex<ECashEncoder>,
}

struct DecoderEntry {
    state: Mutex<DecoderState>,
}

enum DecoderState {
    Active(ECashDecoder),
    Complete,
}

#[derive(Serialize)]
struct FragmentDto {
    fragment: String,
}

#[derive(Serialize)]
struct IncompleteDto {
    complete: bool,
}

#[derive(Serialize)]
struct CompleteDto {
    complete: bool,
    payload: String,
}

#[derive(Serialize)]
struct ClosedDto {
    closed: bool,
}

pub(crate) fn create_encoder(payload: &str) -> Result<u64, AndroidError> {
    validate_payload(payload)?;
    let ecash = parse_ecash(payload).ok_or_else(invalid_payload)?;
    global_insert(
        HandleKind::Encoder,
        EncoderEntry {
            encoder: Mutex::new(ECashEncoder::new(&ecash)),
        },
    )
}

pub(crate) fn next_fragment(handle: u64) -> Result<String, AndroidError> {
    let entry = global_get::<EncoderEntry>(handle, HandleKind::Encoder)?;
    let fragment = entry
        .encoder
        .lock()
        .map_err(|_| AndroidError::internal())?
        .next_fragment();
    validate_fragment(&fragment)?;
    serialize(&FragmentDto { fragment })
}

pub(crate) fn create_decoder() -> Result<u64, AndroidError> {
    global_insert(
        HandleKind::Decoder,
        DecoderEntry {
            state: Mutex::new(DecoderState::Active(ECashDecoder::new())),
        },
    )
}

pub(crate) fn add_fragment(handle: u64, fragment: &str) -> Result<String, AndroidError> {
    validate_frame(fragment)?;
    let entry = global_get::<DecoderEntry>(handle, HandleKind::Decoder)?;
    let mut state = entry.state.lock().map_err(|_| AndroidError::internal())?;
    let DecoderState::Active(decoder) = &mut *state else {
        return Err(AndroidError::invalid_handle());
    };
    let ecash = if let Some(ecash) = parse_ecash(fragment) {
        ecash
    } else {
        validate_fragment(fragment)?;
        let Some(ecash) = decoder.add_fragment(fragment) else {
            return serialize(&IncompleteDto { complete: false });
        };
        ecash
    };
    let payload = ecash.to_string();
    validate_payload(&payload).map_err(|_| AndroidError::internal())?;
    *state = DecoderState::Complete;
    drop(state);
    let _ = global_close(handle, HandleKind::Decoder);
    serialize(&CompleteDto {
        complete: true,
        payload,
    })
}

pub(crate) fn close(handle: u64, kind: &str) -> Result<String, AndroidError> {
    match kind {
        "encoder" => close_typed::<EncoderEntry>(handle, HandleKind::Encoder, |entry| {
            drop(entry.encoder.lock())
        })?,
        "decoder" => close_typed::<DecoderEntry>(handle, HandleKind::Decoder, |entry| {
            drop(entry.state.lock())
        })?,
        _ => return Err(invalid_kind()),
    }
    serialize(&ClosedDto { closed: true })
}

fn close_typed<T>(handle: u64, kind: HandleKind, wait: impl FnOnce(&T)) -> Result<(), AndroidError>
where
    T: Send + Sync + 'static,
{
    match global_get::<T>(handle, kind) {
        Ok(entry) => wait(&entry),
        Err(error) if error.code == AndroidErrorCode::InvalidHandle => {
            global_validate_type::<T>(handle, kind)?;
        }
        Err(error) => return Err(error),
    }
    global_close(handle, kind)
}

fn validate_payload(payload: &str) -> Result<(), AndroidError> {
    if payload.is_empty()
        || payload.len() > MAX_ECASH_PAYLOAD_BYTES
        || (!payload.starts_with("fedimint") && !payload.starts_with("fedimint:"))
    {
        return Err(invalid_payload());
    }
    Ok(())
}

fn validate_fragment(fragment: &str) -> Result<(), AndroidError> {
    validate_frame(fragment)?;
    decode_prefixed::<Fragment>(FEDIMINT_PREFIX, fragment).map_err(|_| invalid_fragment())?;
    Ok(())
}

fn validate_frame(fragment: &str) -> Result<(), AndroidError> {
    if fragment.is_empty()
        || fragment.len() > MAX_FRAGMENT_BYTES
        || !fragment.starts_with("fedimint")
    {
        return Err(invalid_fragment());
    }
    Ok(())
}

fn invalid_payload() -> AndroidError {
    AndroidError::new(
        AndroidErrorCode::InvalidArgument,
        "The ecash payload is invalid.",
        false,
    )
}

fn invalid_fragment() -> AndroidError {
    AndroidError::new(
        AndroidErrorCode::InvalidArgument,
        "The animated QR fragment is invalid.",
        false,
    )
}

fn invalid_kind() -> AndroidError {
    AndroidError::new(
        AndroidErrorCode::InvalidArgument,
        "The ecash codec type is invalid.",
        false,
    )
}

fn serialize<T: Serialize>(value: &T) -> Result<String, AndroidError> {
    serde_json::to_string(value).map_err(|_| AndroidError::internal())
}

#[cfg(test)]
mod tests {
    use fedimint_core::config::FederationId;
    use fedimint_fountain::{FountainDecoder, FountainEncoder};
    use fedimint_mintv2_client::ECash;

    use super::super::handles::HandleRegistry;
    use super::*;
    use crate::{ECashWrapper, EcashToken};

    #[test]
    fn fountain_roundtrip_accepts_duplicates_and_shuffled_fragments() {
        let payload = (0..4096).map(|index| index as u8).collect::<Vec<_>>();
        let mut encoder = FountainEncoder::new(&payload, 512);
        let mut fragments = (0..32).map(|_| encoder.next_fragment()).collect::<Vec<_>>();
        fragments.reverse();
        fragments.insert(1, fragments[0].clone());
        let mut decoder = FountainDecoder::<Vec<u8>>::default();
        let mut decoded = None;
        for fragment in fragments {
            decoded = decoder.add_fragment(&fragment).or(decoded);
        }
        assert_eq!(decoded.expect("must decode"), payload);
    }

    #[test]
    fn malformed_and_oversized_inputs_are_rejected_without_echo() {
        let secret = "fedimint:not-valid-secret";
        let error = create_encoder(secret).unwrap_err();
        assert!(!error.to_string().contains(secret));
        assert!(validate_payload(&"x".repeat(MAX_ECASH_PAYLOAD_BYTES + 1)).is_err());
        assert!(validate_fragment("not-fedimint").is_err());
        assert!(validate_fragment("fedimintgarbage").is_err());
        assert!(validate_fragment(&format!("fedimint{}", "x".repeat(MAX_FRAGMENT_BYTES))).is_err());
    }

    #[test]
    fn codec_handles_are_typed_generation_safe_and_close_idempotently() {
        let mut registry = HandleRegistry::default();
        for _ in 0..100 {
            let encoder = registry.insert(HandleKind::Encoder, 1_u8);
            assert!(registry.close(encoder, HandleKind::Decoder).is_err());
            registry.close(encoder, HandleKind::Encoder).unwrap();
            registry.close(encoder, HandleKind::Encoder).unwrap();
            let decoder = registry.insert(HandleKind::Decoder, 2_u8);
            assert!(registry.get::<u8>(encoder, HandleKind::Encoder).is_err());
            registry.close(decoder, HandleKind::Decoder).unwrap();
        }
    }

    #[test]
    fn response_shapes_hide_payload_until_terminal() {
        assert_eq!(
            serialize(&IncompleteDto { complete: false }).unwrap(),
            r#"{"complete":false}"#
        );
        assert_eq!(
            serialize(&CompleteDto {
                complete: true,
                payload: "fedimint1payload".into()
            })
            .unwrap(),
            r#"{"complete":true,"payload":"fedimint1payload"}"#
        );
        assert_eq!(
            serialize(&ClosedDto { closed: true }).unwrap(),
            r#"{"closed":true}"#
        );
    }

    #[test]
    fn static_token_completes_before_fountain_decoding() {
        let payload =
            ECashWrapper(EcashToken::V2(ECash::new(FederationId::dummy(), vec![]))).to_string();
        assert!(parse_ecash(&payload).is_some());
        let decoder = create_decoder().unwrap();
        let json = add_fragment(decoder, &payload).unwrap();
        let value: serde_json::Value = serde_json::from_str(&json).unwrap();
        assert_eq!(value["complete"], true);
        assert_eq!(value["payload"], payload);
        assert!(add_fragment(decoder, &payload).is_err());
    }
}
