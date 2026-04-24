DEMO_NAME := Elasticsearch Hybrid Search Demo
export DATA_PATH := ../data
export BUILD_CONTEXT := ../../..
POST_START_HOOK = curl -sf -X POST "http://localhost:9200/_license/start_trial?acknowledge=true" > /dev/null && echo "✓ Trial license activated"
include ../../common/demo.mk
