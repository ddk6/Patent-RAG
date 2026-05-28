from __future__ import annotations

import json
import os
import re
import tempfile
import urllib.request
from dataclasses import dataclass
from typing import Any, Dict, List, Optional, Tuple

from fastapi import FastAPI
from pydantic import BaseModel, Field

try:
    import fitz  # PyMuPDF
except Exception:  # pragma: no cover - reported through parser warnings at runtime
    fitz = None


PARSER_VERSION = "patent-parser-python-v1"
MAX_DIRECT_PDF_BYTES = 80 * 1024 * 1024
CLAIM_START_RE = re.compile(
    r"(?m)^\s*1\s*[.．、]\s*(?:一种|一项|根据|如|用于|系统|装置|设备|方法|介质|计算机|所述)"
)
CLAIM_NUMBER_RE = re.compile(r"(?m)^\s*(\d{1,3})\s*[.．、]\s*")
DESCRIPTION_HEADING_RE = re.compile(
    r"(?m)^\s*(?:#+\s*)?(?:说明书|技术领域|背景技术|现有技术|发明内容|实用新型内容|附图说明|具体实施方式|具体实施例|实施方式)\s*$"
)
ABSTRACT_TRAIL_MARKERS = ("![", "<details>", "摘要附图")


app = FastAPI(title="KnowLink Patent Parser", version=PARSER_VERSION)


class PatentParserRequest(BaseModel):
    fileMd5: Optional[str] = None
    fileName: Optional[str] = None
    fullMd: Optional[str] = None
    contentJson: Optional[str] = None
    layoutJson: Optional[str] = None
    fileUrl: Optional[str] = None
    parserMode: Optional[str] = None


class PatentMetadata(BaseModel):
    applicationNumber: Optional[str] = None
    publicationNumber: Optional[str] = None
    title: Optional[str] = None
    applicant: Optional[str] = None
    inventors: Optional[str] = None
    ipc: Optional[str] = None
    patentType: Optional[str] = None
    applicationDate: Optional[str] = None
    publicationDate: Optional[str] = None
    agency: Optional[str] = None
    agent: Optional[str] = None
    address: Optional[str] = None
    abstractText: Optional[str] = None
    mainClaimText: Optional[str] = None
    rawBibliographicJson: Optional[str] = None


class PatentClaimItem(BaseModel):
    claimNo: int
    text: str
    independent: bool = False
    dependsOnClaimNo: Optional[int] = None
    technicalFeaturesJson: Optional[str] = None
    pageNumber: Optional[int] = None
    anchorText: Optional[str] = None


class PatentSectionItem(BaseModel):
    sectionType: str
    title: Optional[str] = None
    order: int = 0
    text: Optional[str] = None
    pageStart: Optional[int] = None
    pageEnd: Optional[int] = None
    anchorText: Optional[str] = None


class PatentChunkItem(BaseModel):
    sourceType: str
    sourceId: Optional[int] = None
    chunkNo: int
    text: str
    pageNumber: Optional[int] = None
    anchorText: Optional[str] = None
    sectionPath: Optional[str] = None
    claimNo: Optional[int] = None
    independentClaim: bool = False
    tokenCount: Optional[int] = None


class PatentParserResult(BaseModel):
    parserVersion: str = PARSER_VERSION
    metadata: PatentMetadata = Field(default_factory=PatentMetadata)
    claims: List[PatentClaimItem] = Field(default_factory=list)
    sections: List[PatentSectionItem] = Field(default_factory=list)
    chunks: List[PatentChunkItem] = Field(default_factory=list)
    warnings: List[str] = Field(default_factory=list)


@dataclass(frozen=True)
class SectionHeading:
    section_type: str
    title: str
    aliases: Tuple[str, ...]


SECTION_HEADINGS = (
    SectionHeading("TECHNICAL_FIELD", "技术领域", ("技术领域",)),
    SectionHeading("BACKGROUND", "背景技术", ("背景技术", "现有技术")),
    SectionHeading("SUMMARY", "发明内容", ("发明内容", "实用新型内容")),
    SectionHeading("DRAWING_DESC", "附图说明", ("附图说明",)),
    SectionHeading("EMBODIMENT", "具体实施方式", ("具体实施方式", "具体实施例", "实施方式")),
)


@app.get("/health")
def health() -> Dict[str, str]:
    return {"status": "ok", "parserVersion": PARSER_VERSION}


@app.post("/parse-patent")
def parse_patent(request: PatentParserRequest) -> PatentParserResult:
    warnings: List[str] = []
    text = normalize_text(extract_text(request, warnings))
    if not text:
        warnings.append("empty input")
        return PatentParserResult(warnings=warnings)
    if len(text) < 300:
        warnings.append("text too short")

    metadata = extract_metadata(text)
    claims = extract_claims(text)
    sections = extract_sections(text)

    if claims:
        metadata.mainClaimText = claims[0].text
    else:
        warnings.append("no claims extracted")

    if not sections:
        warnings.append("no description sections extracted")

    chunks = build_chunks(metadata, claims, sections)
    return PatentParserResult(
        metadata=metadata,
        claims=claims,
        sections=sections,
        chunks=chunks,
        warnings=warnings,
    )


def extract_text(request: PatentParserRequest, warnings: List[str]) -> str:
    parser_mode = (request.parserMode or "").upper()
    if parser_mode == "DIRECT_PDF" and request.fileUrl:
        direct_text = extract_direct_pdf_text(request.fileUrl, warnings)
        if direct_text.strip():
            return direct_text
        warnings.append("direct pdf extraction returned empty text")

    if request.fullMd and request.fullMd.strip():
        return request.fullMd

    if request.contentJson and request.contentJson.strip():
        try:
            parsed = json.loads(request.contentJson)
            return content_json_to_text(parsed)
        except Exception:
            return request.contentJson

    return ""


def extract_direct_pdf_text(file_url: str, warnings: List[str]) -> str:
    if fitz is None:
        warnings.append("pymupdf unavailable")
        return ""

    try:
        pdf_bytes = load_pdf_bytes(file_url)
        if not pdf_bytes:
            warnings.append("empty pdf bytes")
            return ""
        return extract_pdf_bytes_text(pdf_bytes, warnings)
    except Exception as exc:
        warnings.append(f"direct pdf extraction failed: {type(exc).__name__}")
        return ""


def load_pdf_bytes(file_url: str) -> bytes:
    value = file_url.strip()
    if value.lower().startswith(("http://", "https://")):
        with urllib.request.urlopen(value, timeout=30) as response:
            data = response.read(MAX_DIRECT_PDF_BYTES + 1)
            if len(data) > MAX_DIRECT_PDF_BYTES:
                raise ValueError("pdf exceeds direct parser size limit")
            return data

    if os.path.isfile(value):
        with open(value, "rb") as file:
            data = file.read(MAX_DIRECT_PDF_BYTES + 1)
            if len(data) > MAX_DIRECT_PDF_BYTES:
                raise ValueError("pdf exceeds direct parser size limit")
            return data

    raise ValueError("fileUrl is neither URL nor readable local file")


def extract_pdf_bytes_text(pdf_bytes: bytes, warnings: List[str]) -> str:
    pages: List[str] = []
    with tempfile.NamedTemporaryFile(suffix=".pdf", delete=True) as temp_file:
        temp_file.write(pdf_bytes)
        temp_file.flush()
        with fitz.open(temp_file.name) as document:
            for page in document:
                text = page.get_text("text") or ""
                cleaned = remove_repeated_page_noise(text)
                if cleaned.strip():
                    pages.append(cleaned.strip())

    if not pages:
        warnings.append("no text pages extracted")
    return "\n\n".join(pages)


def remove_repeated_page_noise(page_text: str) -> str:
    lines = [line.strip() for line in page_text.splitlines()]
    cleaned = [
        line
        for line in lines
        if line
        and not re.fullmatch(r"\d+\s*/\s*\d+", line)
        and not re.fullmatch(r"第\s*\d+\s*页", line)
    ]
    return "\n".join(cleaned)


def content_json_to_text(value: Any) -> str:
    parts: List[str] = []

    def walk(node: Any) -> None:
        if isinstance(node, dict):
            for key in ("text", "content", "caption", "html"):
                item = node.get(key)
                if isinstance(item, str) and item.strip():
                    parts.append(item.strip())
            for item in node.values():
                if isinstance(item, (dict, list)):
                    walk(item)
        elif isinstance(node, list):
            for item in node:
                walk(item)

    walk(value)
    return "\n".join(parts)


def normalize_text(text: str) -> str:
    text = text.replace("\r\n", "\n").replace("\r", "\n")
    text = text.replace("\u3000", " ").replace("\xa0", " ")
    text = re.sub(r"[ \t]+", " ", text)
    text = re.sub(r"\n{3,}", "\n\n", text)
    text = re.sub(r"(?m)^\s*#+\s*", "", text)
    return text.strip()


def extract_metadata(text: str) -> PatentMetadata:
    data: Dict[str, Optional[str]] = {
        "applicationNumber": first_match(text, [
            r"(?:\(21\)\s*)?申请号\s*[:：]?\s*([A-Z]{0,4}\d[\dA-Z.]+)",
        ]),
        "publicationNumber": first_match(text, [
            r"(?:\(11\)\s*)?(?:申请公布号|授权公告号|公开号|公告号)\s*[:：]?\s*([A-Z]{0,4}\d[\dA-Z.]+)",
        ]),
        "title": first_match(text, [
            r"(?:\(54\)\s*)?(?:发明名称|实用新型名称|外观设计名称)\s*[:：]?\s*([^\n]{2,120})",
        ]),
        "applicant": first_match(text, [
            r"(?:\(71\)\s*)?申请人\s*[:：]?\s*([^\n]{2,200})",
        ]),
        "inventors": first_match(text, [
            r"(?:\(72\)\s*)?(?:发明人|设计人)\s*[:：]?\s*([^\n]{2,200})",
        ]),
        "ipc": first_match(text, [
            r"(?:\(51\)\s*)?(?:Int\.Cl\.|国际分类号|IPC分类号)\s*[:：]?\s*([^\n]{2,200})",
        ]),
        "applicationDate": first_match(text, [
            r"(?:\(22\)\s*)?申请日\s*[:：]?\s*(\d{4}[.\-/年]\d{1,2}[.\-/月]\d{1,2}日?)",
        ]),
        "publicationDate": first_match(text, [
            r"(?:\(43\)|\(45\))?\s*(?:申请公布日|授权公告日|公开日|公告日)\s*[:：]?\s*(\d{4}[.\-/年]\d{1,2}[.\-/月]\d{1,2}日?)",
        ]),
        "agency": first_match(text, [
            r"(?:\(74\)\s*)?专利代理机构\s*[:：]?\s*([^\n]{2,200})",
        ]),
        "agent": first_match(text, [
            r"(?:代理人)\s*[:：]?\s*([^\n]{2,120})",
        ]),
        "address": first_match(text, [
            r"(?:地址)\s*[:：]?\s*([^\n]{6,240})",
        ]),
    }

    patent_type = first_match(text, [
        r"(发明专利申请)",
        r"(发明专利)",
        r"(实用新型专利)",
        r"(外观设计专利)",
    ])
    data["patentType"] = patent_type
    data["abstractText"] = extract_abstract(text)
    data["rawBibliographicJson"] = json.dumps(data, ensure_ascii=False)
    return PatentMetadata(**data)


def first_match(text: str, patterns: List[str]) -> Optional[str]:
    for pattern in patterns:
        match = re.search(pattern, text, flags=re.IGNORECASE)
        if match:
            return cleanup_field(match.group(1))
    return None


def cleanup_field(value: Optional[str]) -> Optional[str]:
    if value is None:
        return None
    value = re.sub(r"\s+", " ", value).strip(" ：:;；")
    return value or None


def extract_abstract(text: str) -> Optional[str]:
    abstract_text = between_heading_aliases(text, ("摘要",), ("权利要求书", "说明书", "摘要附图"))
    if abstract_text:
        return compact_block(trim_abstract_tail(abstract_text), max_chars=4000)

    patterns = [
        r"(?:摘\s*要)\s*\n(.+?)(?=\n\s*(?:权\s*利\s*要\s*求\s*书|说\s*明\s*书|摘\s*要\s*附\s*图))",
        r"(?:摘\s*要)\s*[:：]\s*(.+?)(?=\n\s*(?:权\s*利\s*要\s*求\s*书|说\s*明\s*书|摘\s*要\s*附\s*图))",
    ]
    for pattern in patterns:
        match = re.search(pattern, text, flags=re.DOTALL)
        if match:
            return compact_block(trim_abstract_tail(match.group(1)), max_chars=4000)
    return None


def extract_claims(text: str) -> List[PatentClaimItem]:
    claims_text = between_heading_aliases(text, ("权利要求书",), ("说明书", "摘要", "摘要附图"))
    result = parse_claim_items(claims_text) if claims_text else []
    if result:
        return result

    fallback_claims_text = extract_claims_region_without_heading(text)
    if not fallback_claims_text:
        return []
    return parse_claim_items(fallback_claims_text)


def parse_claim_items(claims_text: str) -> List[PatentClaimItem]:
    matches = list(CLAIM_NUMBER_RE.finditer(claims_text))
    result: List[PatentClaimItem] = []

    for idx, match in enumerate(matches):
        claim_no = int(match.group(1))
        start = match.end()
        end = matches[idx + 1].start() if idx + 1 < len(matches) else len(claims_text)
        claim_text = compact_block(claims_text[start:end])
        if not claim_text:
            continue
        depends_on = detect_claim_dependency(claim_text)
        result.append(PatentClaimItem(
            claimNo=claim_no,
            text=f"{claim_no}. {claim_text}",
            independent=depends_on is None,
            dependsOnClaimNo=depends_on,
            technicalFeaturesJson=extract_technical_features_json(claim_text),
            anchorText=build_anchor(claim_text),
        ))

    return result


def trim_abstract_tail(text: str) -> str:
    end_candidates: List[int] = []
    claim_start = find_claim_start(text)
    if claim_start is not None:
        end_candidates.append(claim_start)

    for marker in ABSTRACT_TRAIL_MARKERS:
        marker_index = text.find(marker)
        if marker_index >= 0:
            end_candidates.append(marker_index)

    if not end_candidates:
        return text
    return text[:min(end_candidates)]


def extract_claims_region_without_heading(text: str) -> str:
    start = find_claim_start(text)
    if start is None:
        return ""

    end_match = DESCRIPTION_HEADING_RE.search(text, start)
    end = end_match.start() if end_match else len(text)
    return text[start:end].strip()


def find_claim_start(text: str) -> Optional[int]:
    match = CLAIM_START_RE.search(text)
    return match.start() if match else None


def between_markers(text: str, start_marker: str, end_markers: Tuple[str, ...]) -> str:
    return between_heading_aliases(text, (start_marker,), end_markers)


def between_heading_aliases(text: str, start_aliases: Tuple[str, ...], end_aliases: Tuple[str, ...]) -> str:
    start_match = find_heading(text, start_aliases)
    if start_match is None:
        start_match = find_marker(text, start_aliases)
    if start_match is None:
        return ""

    start = start_match[1]
    end_match = find_heading(text, end_aliases, start)
    if end_match is None:
        end_match = find_marker(text, end_aliases, start)
    end = end_match[0] if end_match else len(text)
    return text[start:end].strip()


def find_heading(text: str, aliases: Tuple[str, ...], start: int = 0) -> Optional[Tuple[int, int]]:
    best: Optional[Tuple[int, int]] = None
    for alias in aliases:
        pattern = rf"(?m)^\s*(?:#+\s*)?{flexible_text_pattern(alias)}\s*$"
        match = re.search(pattern, text[start:])
        if match:
            candidate = (start + match.start(), start + match.end())
            if best is None or candidate[0] < best[0]:
                best = candidate
    return best


def find_marker(text: str, aliases: Tuple[str, ...], start: int = 0) -> Optional[Tuple[int, int]]:
    best: Optional[Tuple[int, int]] = None
    for alias in aliases:
        match = re.search(flexible_text_pattern(alias), text[start:])
        if match:
            candidate = (start + match.start(), start + match.end())
            if best is None or candidate[0] < best[0]:
                best = candidate
    return best


def flexible_text_pattern(text: str) -> str:
    return r"\s*".join(re.escape(char) for char in text)


def legacy_between_markers(text: str, start_marker: str, end_markers: Tuple[str, ...]) -> str:
    start = text.find(start_marker)
    if start < 0:
        return ""
    start += len(start_marker)
    end_candidates = [text.find(marker, start) for marker in end_markers]
    end_candidates = [item for item in end_candidates if item >= 0]
    end = min(end_candidates) if end_candidates else len(text)
    return text[start:end].strip()


def detect_claim_dependency(claim_text: str) -> Optional[int]:
    match = re.search(r"(?:根据|如|按照)?权利要求\s*(\d{1,3})\s*(?:所述|所记载)?", claim_text)
    if match:
        return int(match.group(1))
    return None


def extract_technical_features_json(claim_text: str) -> str:
    clauses = re.split(r"[；;。]\s*", claim_text)
    features = [cleanup_field(item) for item in clauses if cleanup_field(item)]
    return json.dumps({"features": features[:20]}, ensure_ascii=False)


def extract_sections(text: str) -> List[PatentSectionItem]:
    description = text[text.find("说明书") + len("说明书"):] if "说明书" in text else text
    heading_matches: List[Tuple[int, SectionHeading]] = []

    for heading in SECTION_HEADINGS:
        for alias in heading.aliases:
            for match in re.finditer(rf"(?m)^\s*{re.escape(alias)}\s*$", description):
                heading_matches.append((match.start(), heading))

    heading_matches.sort(key=lambda item: item[0])
    if not heading_matches:
        return []

    sections: List[PatentSectionItem] = []
    for idx, (start, heading) in enumerate(heading_matches):
        content_start_match = re.search(r"\n", description[start:])
        content_start = start + content_start_match.end() if content_start_match else start + len(heading.title)
        end = heading_matches[idx + 1][0] if idx + 1 < len(heading_matches) else len(description)
        section_text = compact_block(description[content_start:end])
        if not section_text:
            continue
        sections.append(PatentSectionItem(
            sectionType=heading.section_type,
            title=heading.title,
            order=len(sections) + 1,
            text=section_text,
            anchorText=build_anchor(section_text),
        ))

    return sections


def build_chunks(
    metadata: PatentMetadata,
    claims: List[PatentClaimItem],
    sections: List[PatentSectionItem],
) -> List[PatentChunkItem]:
    chunks: List[PatentChunkItem] = []

    bibliographic_lines = [
        ("申请号", metadata.applicationNumber),
        ("公开号", metadata.publicationNumber),
        ("名称", metadata.title),
        ("申请人", metadata.applicant),
        ("发明人", metadata.inventors),
        ("IPC", metadata.ipc),
        ("专利类型", metadata.patentType),
        ("申请日", metadata.applicationDate),
        ("公开日", metadata.publicationDate),
    ]
    bibliographic = "\n".join(f"{key}: {value}" for key, value in bibliographic_lines if value)
    if bibliographic:
        chunks.append(make_chunk("BIBLIOGRAPHIC", len(chunks) + 1, bibliographic))

    if metadata.abstractText:
        chunks.append(make_chunk("ABSTRACT", len(chunks) + 1, metadata.abstractText, section_path="摘要"))

    for claim in claims:
        chunks.append(make_chunk(
            "CLAIM",
            len(chunks) + 1,
            claim.text,
            claim_no=claim.claimNo,
            independent_claim=claim.independent,
            anchor_text=claim.anchorText,
            section_path=f"权利要求书 > 权利要求{claim.claimNo}",
        ))

    for section in sections:
        chunks.append(make_chunk(
            "DESCRIPTION",
            len(chunks) + 1,
            f"{section.title}\n{section.text}",
            anchor_text=section.anchorText,
            section_path=section.title,
        ))

    return chunks


def make_chunk(
    source_type: str,
    chunk_no: int,
    text: str,
    claim_no: Optional[int] = None,
    independent_claim: bool = False,
    anchor_text: Optional[str] = None,
    section_path: Optional[str] = None,
) -> PatentChunkItem:
    cleaned = compact_block(text)
    return PatentChunkItem(
        sourceType=source_type,
        chunkNo=chunk_no,
        text=cleaned,
        claimNo=claim_no,
        independentClaim=independent_claim,
        anchorText=anchor_text or build_anchor(cleaned),
        sectionPath=section_path,
        tokenCount=estimate_tokens(cleaned),
    )


def compact_block(text: Optional[str], max_chars: Optional[int] = None) -> str:
    if not text:
        return ""
    value = re.sub(r"[ \t]+", " ", text)
    value = re.sub(r"\n\s*\n+", "\n", value)
    value = value.strip()
    if max_chars and len(value) > max_chars:
        return value[:max_chars].rstrip()
    return value


def build_anchor(text: Optional[str]) -> Optional[str]:
    value = compact_block(text)
    if not value:
        return None
    value = re.sub(r"\s+", " ", value)
    return value[:120]


def estimate_tokens(text: Optional[str]) -> int:
    if not text:
        return 0
    chinese_chars = len(re.findall(r"[\u4e00-\u9fff]", text))
    latin_words = len(re.findall(r"[A-Za-z0-9_]+", text))
    return chinese_chars + int(latin_words * 1.5)
