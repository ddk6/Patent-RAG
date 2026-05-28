-- Optional cleanup after switching to the patent-only pipeline.
-- Apply only after confirming legacy GENERAL RAG data is no longer needed.

DROP TABLE IF EXISTS document_vectors;
