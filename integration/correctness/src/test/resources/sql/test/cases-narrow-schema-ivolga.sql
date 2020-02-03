CREATE TABLE default.ivolga_test_narrow (`dt` Float64, `sensor_id` String, `value_float` Nullable(Float32), `stock_num` String, `value_str` String, `upload_id` String) ENGINE = MergeTree() PARTITION BY toMonday(toDateTime(dt)) ORDER BY (stock_num, value_str, upload_id, sensor_id, dt) SAMPLE BY dt SETTINGS index_granularity = 8192
