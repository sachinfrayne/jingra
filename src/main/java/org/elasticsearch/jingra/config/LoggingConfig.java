package org.elasticsearch.jingra.config;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;

/**
 * Logging configuration for runtime log level adjustment.
 */
public class LoggingConfig {

    @JsonProperty("level")
    private String level = "INFO";

    @JsonProperty("loggers")
    private Map<String, String> loggers;

    public String getLevel() {
        return level;
    }

    public void setLevel(String level) {
        this.level = level;
    }

    public Map<String, String> getLoggers() {
        return loggers;
    }

    public void setLoggers(Map<String, String> loggers) {
        this.loggers = loggers;
    }
}
