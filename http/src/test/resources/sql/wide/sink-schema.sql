CREATE TABLE IF NOT EXISTS SM_Integration_wide_patterns ( date Date DEFAULT now(),  from DateTime,  from_millis UInt16 DEFAULT 0,  to DateTime,  to_millis UInt16 DEFAULT 0,  pattern_id String,  mechanism_id String) ENGINE = Log()