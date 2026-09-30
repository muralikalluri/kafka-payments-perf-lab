-- Tracing across the outbox: the W3C traceparent of the request that wrote the row, restored by the publisher.
ALTER TABLE outbox ADD COLUMN trace text;
