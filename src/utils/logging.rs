//! Builds a `tracing_subscriber` filter from the optional `logging:` config block, replacing
//! the Java version's SLF4J/Logback runtime reconfiguration.

use crate::config::LoggingConfig;
use tracing_subscriber::EnvFilter;

/// Initializes the global tracing subscriber. Safe to call once at process start; a config-less
/// call (`config: None`) defaults to `info` level, matching the Java default.
pub fn configure_logging(config: Option<&LoggingConfig>) {
    let filter = build_filter(config);
    let _ = tracing_subscriber::fmt().with_env_filter(filter).try_init();
}

fn build_filter(config: Option<&LoggingConfig>) -> EnvFilter {
    let directives = build_directives(config);
    EnvFilter::try_new(&directives).unwrap_or_else(|_| EnvFilter::new("info"))
}

fn build_directives(config: Option<&LoggingConfig>) -> String {
    let Some(config) = config else {
        return "info".to_string();
    };
    let mut directives = vec![config.level.to_lowercase()];
    if let Some(loggers) = &config.loggers {
        let mut targets: Vec<_> = loggers.iter().collect();
        targets.sort_by_key(|(k, _)| k.as_str());
        for (target, level) in targets {
            directives.push(format!("{target}={}", level.to_lowercase()));
        }
    }
    directives.join(",")
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::collections::HashMap;

    #[test]
    fn build_directives_defaults_to_info_without_config() {
        assert_eq!(build_directives(None), "info");
    }

    #[test]
    fn build_directives_uses_configured_level() {
        let config = LoggingConfig {
            level: "DEBUG".to_string(),
            loggers: None,
        };
        assert_eq!(build_directives(Some(&config)), "debug");
    }

    #[test]
    fn build_directives_appends_per_target_overrides_sorted() {
        let config = LoggingConfig {
            level: "WARN".to_string(),
            loggers: Some(HashMap::from([
                ("jingra::engine".to_string(), "TRACE".to_string()),
                ("jingra::data".to_string(), "DEBUG".to_string()),
            ])),
        };
        assert_eq!(build_directives(Some(&config)), "warn,jingra::data=debug,jingra::engine=trace");
    }

    #[test]
    fn build_filter_falls_back_to_info_on_unparsable_directives() {
        let config = LoggingConfig {
            level: "not a valid directive!!".to_string(),
            loggers: None,
        };
        // Should not panic; falls back rather than propagating a parse error.
        let _ = build_filter(Some(&config));
    }
}
