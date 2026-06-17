package org.elasticsearch.jingra.config;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Configuration for loading data via the {@code metricsgenreceiver} binary.
 * Lives under {@code load.metricsgen} in the benchmark YAML.
 */
public class MetricsgenConfig {

    private static final String DEFAULT_VERSION  = "1.0.7";
    private static final String DEFAULT_SCENARIO = "builtin/hostmetrics";
    private static final int    DEFAULT_SCALE     = 10_000;
    private static final String DEFAULT_INTERVAL  = "1s";
    private static final String DEFAULT_WINDOW    = "270m";
    private static final int    DEFAULT_SEED      = 123;

    @JsonProperty("version")
    private String version;

    @JsonProperty("scenario")
    private String scenario;

    @JsonProperty("scale")
    private Integer scale;

    @JsonProperty("interval")
    private String interval;

    @JsonProperty("start_now_minus")
    private String startNowMinus;

    @JsonProperty("seed")
    private Integer seed;

    public String getVersion()      { return version; }
    public String getScenario()     { return scenario; }
    public Integer getScale()       { return scale; }
    public String getInterval()     { return interval; }
    public String getStartNowMinus(){ return startNowMinus; }
    public Integer getSeed()        { return seed; }

    public void setVersion(String version)            { this.version = version; }
    public void setScenario(String scenario)          { this.scenario = scenario; }
    public void setScale(Integer scale)               { this.scale = scale; }
    public void setInterval(String interval)          { this.interval = interval; }
    public void setStartNowMinus(String startNowMinus){ this.startNowMinus = startNowMinus; }
    public void setSeed(Integer seed)                 { this.seed = seed; }

    public String versionOrDefault()      { return version      != null ? version      : DEFAULT_VERSION;  }
    public String scenarioOrDefault()     { return scenario     != null ? scenario     : DEFAULT_SCENARIO; }
    public int    scaleOrDefault()        { return scale        != null ? scale        : DEFAULT_SCALE;    }
    public String intervalOrDefault()     { return interval     != null ? interval     : DEFAULT_INTERVAL; }
    public String startNowMinusOrDefault(){ return startNowMinus!= null ? startNowMinus: DEFAULT_WINDOW;   }
    public int    seedOrDefault()         { return seed         != null ? seed         : DEFAULT_SEED;     }
}
