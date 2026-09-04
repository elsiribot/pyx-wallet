use serde::Serialize;

use super::error::{AndroidError, AndroidErrorCode};
use super::handles::{HandleKind, global_close, global_validate_type};
use super::lnurl_pay::LnurlPaySession;

#[derive(Serialize)]
struct ClosedDto {
    closed: bool,
}

pub(crate) fn close(handle: u64, kind: &str) -> Result<String, AndroidError> {
    match kind {
        "quote" => global_close(handle, HandleKind::Quote)?,
        "lnurl" => {
            // Downcast first: Parsed is a shared registry category, while this
            // API is intentionally limited to LNURL pay sessions.
            global_validate_type::<LnurlPaySession>(handle, HandleKind::Parsed)?;
            global_close(handle, HandleKind::Parsed)?;
        }
        _ => {
            return Err(AndroidError::new(
                AndroidErrorCode::InvalidArgument,
                "The transient handle type is invalid.",
                false,
            ));
        }
    }
    serde_json::to_string(&ClosedDto { closed: true }).map_err(|_| AndroidError::internal())
}

#[cfg(test)]
mod tests {
    use super::super::handles::HandleRegistry;
    use super::*;

    #[test]
    fn registry_close_contract_is_idempotent_typed_and_generation_safe() {
        let mut registry = HandleRegistry::default();
        let quote = registry.insert(HandleKind::Quote, "quote");
        registry.close(quote, HandleKind::Quote).unwrap();
        registry.close(quote, HandleKind::Quote).unwrap();
        registry
            .validate_type::<&str>(quote, HandleKind::Quote)
            .unwrap();
        assert!(
            registry
                .validate_type::<String>(quote, HandleKind::Quote)
                .is_err()
        );

        let current = registry.insert(HandleKind::Quote, "new quote");
        assert_ne!(quote, current);
        assert!(registry.close(quote, HandleKind::Quote).is_err());
        assert!(registry.close(current, HandleKind::Parsed).is_err());
        registry.close(current, HandleKind::Quote).unwrap();
    }

    #[test]
    fn close_response_matches_binding_contract() {
        assert_eq!(
            serde_json::to_string(&ClosedDto { closed: true }).unwrap(),
            "{\"closed\":true}"
        );
    }
}
