-- Identity values are allocated before commit. Publish visible rows through a serialized
-- cursor so a later-committing raw row cannot fall behind an already returned API cursor.
CREATE TABLE device_event_publication (
    raw_event_seq BIGINT PRIMARY KEY REFERENCES device_event_log(event_seq),
    event_seq BIGINT NOT NULL UNIQUE
);

CREATE TABLE device_event_publication_cursor (
    singleton INTEGER PRIMARY KEY CHECK (singleton = 1),
    next_seq BIGINT NOT NULL CHECK (next_seq > 0)
);

INSERT INTO device_event_publication(raw_event_seq,event_seq)
SELECT event_seq,event_seq FROM device_event_log;

INSERT INTO device_event_publication_cursor(singleton,next_seq)
SELECT 1,COALESCE(MAX(event_seq),0)+1 FROM device_event_publication;
