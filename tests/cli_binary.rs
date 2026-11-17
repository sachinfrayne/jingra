//! Black-box tests that run the real `jingra` binary. Logging setup is process-global and can
//! only be observed from outside, so it cannot be checked by an in-process unit test.

use std::io::Write;
use std::process::{Command, Output};

fn run_report_with(extra_yaml: &str) -> Output {
    let results = tempfile::tempdir().unwrap();
    let out = tempfile::tempdir().unwrap();
    let mut cfg = tempfile::NamedTempFile::new().unwrap();
    writeln!(
        cfg,
        r#"
report:
  run_id: some-run
  engines: [qdrant]
  results_uri: "file://{}"
  output_directory: "{}"
  generate_plots: false
{extra_yaml}
"#,
        results.path().display(),
        out.path().display()
    )
    .unwrap();
    Command::new(env!("CARGO_BIN_EXE_jingra"))
        .arg("report")
        .arg(cfg.path())
        .env_remove("RUST_LOG")
        .output()
        .expect("run jingra binary")
}

fn all_output(o: &Output) -> String {
    format!("{}{}", String::from_utf8_lossy(&o.stdout), String::from_utf8_lossy(&o.stderr))
}

#[test]
fn logging_level_from_the_config_file_is_honored() {
    let output = run_report_with("logging:\n  level: ERROR\n");
    assert!(output.status.success(), "{}", all_output(&output));
    let text = all_output(&output);
    assert!(!text.contains(" INFO "), "level: ERROR must suppress INFO lines, got:\n{text}");
    assert!(!text.contains("JINGRA v"), "the INFO banner must be suppressed too, got:\n{text}");
}

#[test]
fn info_logging_is_the_default_without_a_logging_block() {
    let output = run_report_with("");
    assert!(output.status.success(), "{}", all_output(&output));
    let text = all_output(&output);
    assert!(text.contains("JINGRA v"), "the version banner should be logged by default, got:\n{text}");
    assert!(text.contains(" INFO "), "got:\n{text}");
}

#[test]
fn an_unreadable_config_is_reported_on_the_console_and_exits_non_zero() {
    let output = Command::new(env!("CARGO_BIN_EXE_jingra"))
        .args(["report", "/no/such/config.yaml"])
        .env_remove("RUST_LOG")
        .output()
        .unwrap();
    assert_eq!(output.status.code(), Some(1));
    assert!(all_output(&output).contains("Failed to load config"), "{}", all_output(&output));
}

#[test]
fn version_flag_prints_the_crate_version_and_exits_zero() {
    let output = Command::new(env!("CARGO_BIN_EXE_jingra")).arg("--version").output().unwrap();
    assert!(output.status.success(), "{}", all_output(&output));
    let text = String::from_utf8_lossy(&output.stdout);
    assert_eq!(text.trim(), format!("jingra {}", env!("CARGO_PKG_VERSION")));
}
