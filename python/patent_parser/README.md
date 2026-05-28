# Patent Parser Worker

This worker is the Python sidecar for KnowLink patent RAG.

It receives either direct PDF input or MinerU output from the Java service and returns structured patent data:

- bibliographic metadata
- claims
- description sections
- retrieval chunks

The Java service tries direct PDF extraction first for files already marked as `PATENT`.
If the parsed result does not pass the Java-side quality gate, the task falls back to MinerU.
The quality gate requires enough bibliographic signals, claims, retrieval chunks, and text coverage before the direct path is accepted.

Run locally:

```bash
cd python/patent_parser
python -m venv .venv
.venv\Scripts\activate
pip install -r requirements.txt
uvicorn app.main:app --host 0.0.0.0 --port 8091
```

Health check:

```bash
curl http://localhost:8091/health
```

Run with Docker Compose from the repository root:

```bash
docker compose -f docs/docker-compose.yaml up -d patent-parser
```
