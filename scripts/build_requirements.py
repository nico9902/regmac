#!/usr/bin/env python3
"""Costruisce il catalogo dei requisiti della Wiki normativa.

Legge `data/normative/corpus.json` (testo per pagina estratto dai PDF originali
del Regolamento Macchine (UE) 2023/1230 e del Cyber Resilience Act (UE) 2024/2847)
e produce:

  * data/normative/requirements.json  – catalogo strutturato (fonte di verità)

Se presente, `data/normative/standards.json` (norme di riferimento per requisito) viene
integrato nel campo `standards` di ogni voce.

Ogni voce conserva il testo originale, le pagine del PDF e il riferimento
(articolo/allegato/punto). Il testo NON è interpretato: la valutazione resta
umana. Uso:  python3 scripts/build_requirements.py
"""
from __future__ import annotations

import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
CORPUS = ROOT / "data" / "normative" / "corpus.json"
OUT_JSON = ROOT / "data" / "normative" / "requirements.json"
STANDARDS = ROOT / "data" / "normative" / "standards.json"

SOFT_HYPHEN = "­"
HEADER_RE = re.compile(
    r"Gazzetta ufficiale dell.Unione europea|ELI: http://data\.europa\.eu|GU L del \d|^\s*IT\s*$|^\s*\d+/\d+\s*$"
)
LIST_MARK_RE = re.compile(r"^(?:[a-z]{1,2}\)|[ivx]+\)|\d+\)|\d+\.(?:\d+\.)*\s|—|-\s|•)")


# --------------------------------------------------------------------------- testo
class Line:
    __slots__ = ("text", "page")

    def __init__(self, text: str, page: int):
        self.text = text
        self.page = page


def load_lines(doc: dict) -> list[Line]:
    out: list[Line] = []
    for p in doc["pages"]:
        for raw in p["text"].split("\n"):
            raw = raw.replace("\f", "").rstrip()
            if HEADER_RE.search(raw):
                continue
            out.append(Line(raw, p["number"]))
    return out


def indent(s: str) -> int:
    return len(s) - len(s.lstrip(" "))


def clean(lines: list[Line]) -> str:
    """Ricompone le righe di layout in paragrafi leggibili."""
    paras: list[str] = []
    cur = ""
    blank = True
    for ln in lines:
        t = ln.text.strip()
        if not t:
            blank = True
            continue
        t = re.sub(r"\s{2,}", " ", t)
        new_para = blank or bool(LIST_MARK_RE.match(t)) or not cur
        if not new_para and cur.endswith((".", ":", ";")) and t[:1].isupper() and indent(ln.text) < 12:
            # inizio di comma dopo chiusura di frase nella stessa colonna
            new_para = False
        if new_para:
            if cur:
                paras.append(cur)
            cur = t
        else:
            if cur.endswith(SOFT_HYPHEN):
                cur = cur[:-1] + t
            elif cur.endswith("-") and not cur.endswith(" -"):
                cur = cur + t
            else:
                cur = cur + " " + t
        blank = False
    if cur:
        paras.append(cur)
    return "\n".join(p.replace(SOFT_HYPHEN, "") for p in paras).strip()


def pages(lines: list[Line]) -> list[int]:
    ps = [l.page for l in lines if l.text.strip()]
    return [min(ps), max(ps)] if ps else []


def find(lines: list[Line], pattern: str, start: int = 0, end: int | None = None) -> int:
    rx = re.compile(pattern)
    end = len(lines) if end is None else end
    for i in range(start, end):
        if rx.search(lines[i].text):
            return i
    raise ValueError(f"pattern non trovato: {pattern!r}")


# ---------------------------------------------------------------------- articoli
ART_RE = re.compile(r"^\s{20,}Articolo (\d+)\s*$")
PARA_RE = re.compile(r"^\s{3,10}(\d+)\.\s{2,}\S")
CAPS_RE = re.compile(r"^[^a-zà-ù]*[A-Z]{3,}[^a-zà-ù]*$")


def parse_articles(lines: list[Line], start: int, end: int) -> list[dict]:
    idx = [i for i in range(start, end) if ART_RE.match(lines[i].text)]
    arts = []
    for n, i in enumerate(idx):
        j = idx[n + 1] if n + 1 < len(idx) else end
        num = int(ART_RE.match(lines[i].text).group(1))
        k = i + 1
        while k < j and not lines[k].text.strip():
            k += 1
        title_parts = []
        while k < j and lines[k].text.strip() and indent(lines[k].text) >= 15:
            title_parts.append(lines[k].text.strip())
            k += 1
        body = [l for l in lines[k:j] if not (CAPS_RE.match(l.text.strip()) and indent(l.text) > 20)]
        # rimuove intestazioni di CAPO/SEZIONE a fine articolo
        while body and (not body[-1].text.strip() or re.match(r"^\s*(CAPO|SEZIONE)\b", body[-1].text)):
            body.pop()
        paras = []
        pidx = [m for m, l in enumerate(body) if PARA_RE.match(l.text)]
        for q, m in enumerate(pidx):
            r = pidx[q + 1] if q + 1 < len(pidx) else len(body)
            chunk = body[m:r]
            paras.append({
                "n": PARA_RE.match(chunk[0].text).group(1),
                "text": re.sub(r"^\d+\.\s*", "", clean(chunk)),
                "pages": pages(chunk),
            })
        arts.append({
            "num": num,
            "title": " ".join(title_parts).replace(SOFT_HYPHEN, ""),
            "text": clean(body),
            "pages": pages(body) or [lines[i].page, lines[i].page],
            "paragraphs": paras,
        })
    return arts


# ------------------------------------------------------------- liste numerate
def split_items(lines: list[Line], pattern: str) -> list[tuple[str, list[Line]]]:
    """Divide un blocco in voci che iniziano con il marcatore `pattern` (gruppo 1 = chiave)."""
    rx = re.compile(pattern)
    items: list[tuple[str, list[Line]]] = []
    for ln in lines:
        m = rx.match(ln.text)
        if m:
            items.append((m.group(1), [ln]))
        elif items:
            items[-1][1].append(ln)
    return items


def strip_marker(text: str) -> str:
    return re.sub(r"^(?:[a-z]{1,2}\)|\d+\)|\d+\.)\s*", "", text, count=1)


ITEM_RE = re.compile(r"^\s{8,20}([a-z]{1,2})\)\s")


def split_section(body: list[Line]):
    """Scompone una sezione in commi e lettere, conservando l'introduzione di ogni elenco."""
    chunks: list[list[Line]] = []
    cur: list[Line] = []
    for b in body:
        if not b.text.strip():
            if cur:
                chunks.append(cur)
                cur = []
        else:
            cur.append(b)
    if cur:
        chunks.append(cur)
    units = []  # (tipo, lines)
    expected = "a"
    for ch in chunks:
        m = ITEM_RE.match(ch[0].text)
        key = m.group(1) if m else None
        # lettera di primo livello solo se segue la sequenza (a, b, c...): evita i sottopunti i), ii)
        is_item = key is not None and (key == expected or key == "a")
        if is_item:
            expected = chr(ord(key) + 1)
            units.append(("item", list(ch)))
        elif units and units[-1][0] == "item" and (m or indent(ch[0].text) > 16 or ch[0].text.strip()[:1].islower()):
            units[-1][1].extend(ch)      # continuazione (sottopunti, salto pagina)
        else:
            units.append(("para", list(ch)))
            if clean(ch).endswith(":"):
                expected = "a"
    lists = sum(1 for t, ch in units if t == "item" and ITEM_RE.match(ch[0].text).group(1) == "a")
    out, comma, lst, intro = [], 0, 0, None
    for t, ch in units:
        if t == "para":
            txt = clean(ch)
            if txt.endswith(":"):
                intro = txt
                continue
            comma += 1
            out.append((f"c{comma}", f"comma {comma}", f"comma {comma}", txt, pages(ch)))
            intro = None
        else:
            key = ITEM_RE.match(ch[0].text).group(1)
            if key == "a":
                lst += 1
            sid = key if lists <= 1 else f"{lst}{key}"
            ref = f"lettera {key})" if lists <= 1 else f"elenco {lst}, lettera {key})"
            txt = clean(ch)
            if intro:
                txt = intro + "\n" + txt
            out.append((sid, ref, f"lett. {key})" if lists <= 1 else f"elenco {lst}, lett. {key})", txt, pages(ch)))
    return out


# --------------------------------------------------------------- catalogo
REQS: list[dict] = []


def add(**kw) -> None:
    kw.setdefault("assessable", True)
    kw.setdefault("tags", [])
    kw.setdefault("always", False)
    kw.setdefault("note", "")
    REQS.append(kw)


MR_GROUP_TAGS = {
    "1": ["generale"],
    "2.1": ["alimentari"],
    "2.2": ["portatili"],
    "2.3": ["legno"],
    "2.4": ["fitosanitari"],
    "3": ["mobilita"],
    "4": ["sollevamento"],
    "5": ["sotterraneo"],
    "6": ["sollevamento_persone"],
}
# Allegato III, parte B, punto 2: si applicano in ogni caso
MR_ALWAYS = ("1.1.2", "1.7.3", "1.7.4")
# Titoli che nel PDF vanno a capo
MR_TITLE_FIX = {
    "2": "REQUISITI ESSENZIALI SUPPLEMENTARI DI SICUREZZA E DI TUTELA DELLA SALUTE PER TALUNE CATEGORIE DI MACCHINE O DI PRODOTTI CORRELATI",
    "3": "REQUISITI ESSENZIALI SUPPLEMENTARI DI SICUREZZA E DI TUTELA DELLA SALUTE PER OVVIARE AI RISCHI DOVUTI ALLA MOBILITÀ",
    "4": "REQUISITI ESSENZIALI SUPPLEMENTARI DI SICUREZZA E DI TUTELA DELLA SALUTE PER PREVENIRE I RISCHI DOVUTI ALLE OPERAZIONI DI SOLLEVAMENTO",
    "5": "REQUISITI ESSENZIALI SUPPLEMENTARI DI SICUREZZA E DI TUTELA DELLA SALUTE PER LE MACCHINE O I PRODOTTI CORRELATI DESTINATI AD ESSERE UTILIZZATI IN LAVORI SOTTERRANEI",
    "6": "REQUISITI ESSENZIALI SUPPLEMENTARI DI SICUREZZA E DI TUTELA DELLA SALUTE PER LE MACCHINE O I PRODOTTI CORRELATI CHE PRESENTANO PERICOLI PARTICOLARI DOVUTI AL SOLLEVAMENTO DI PERSONE",
}
# Sezioni dell'Allegato III scomposte in punti di verifica distinti
MR_SPLIT_PARAS = {"1.1.9"}
MR_SPLIT_LETTERS = {"1.7.4.2", "1.2.1", "1.7.3"}
MR_INFO = {"2.4.1", "3.1.1", "4.1.1"}  # definizioni


IV_TITLES = {
    "A": {"a": "Descrizione completa e uso previsto", "b": "Documentazione della valutazione del rischio",
          "c": "Disegni e schemi di progettazione e fabbricazione", "d": "Descrizioni per la comprensione di disegni e funzionamento",
          "e": "Norme armonizzate / specifiche comuni applicate", "f": "Altre specifiche tecniche applicate",
          "g": "Relazioni di calcolo, prove, ispezioni ed esami", "h": "Mezzi per assicurare la conformità in produzione",
          "i": "Copia delle istruzioni per l'uso", "j": "Dichiarazione di incorporazione e istruzioni di assemblaggio",
          "k": "Dichiarazioni UE di prodotti incorporati", "l": "Misure interne per la produzione in serie",
          "m": "Codice sorgente / logica del software di sicurezza (su richiesta)",
          "n": "Sistemi basati su sensori, remoti o autonomi", "o": "Ricerche e prove per montaggio e messa in servizio sicuri"},
    "B": {"a": "Descrizione completa e funzione prevista", "b": "Documentazione della valutazione del rischio",
          "c": "Disegni e schemi di progettazione e fabbricazione", "d": "Descrizioni per la comprensione di disegni e funzionamento",
          "e": "Norme armonizzate / specifiche comuni applicate", "f": "Altre specifiche tecniche applicate",
          "g": "Relazioni di calcolo, prove, ispezioni ed esami", "h": "Mezzi per assicurare la conformità in produzione",
          "i": "Copia delle istruzioni di assemblaggio (All. XI)", "j": "Misure interne per la produzione in serie",
          "k": "Codice sorgente / logica del software di sicurezza (su richiesta)",
          "l": "Sistemi basati su sensori, remoti o autonomi", "m": "Ricerche e prove per incorporazione sicura"},
}
CRA_TITLES = {
    "II": {"1": "Dati di contatto del fabbricante", "2": "Punto di contatto unico per le vulnerabilità e politica CVD",
           "3": "Identificazione univoca del prodotto", "4": "Finalità prevista, funzionalità e proprietà di sicurezza",
           "5": "Circostanze che possono comportare rischi di cibersicurezza significativi",
           "6": "Indirizzo internet della dichiarazione di conformità UE",
           "7": "Assistenza tecnica di sicurezza e data finale del periodo di assistenza",
           "8": "Istruzioni dettagliate per uso sicuro, aggiornamenti e smantellamento",
           "9": "Accesso alla distinta base del software (SBOM), se resa disponibile"},
    "VII": {"1": "Descrizione generale del prodotto", "2": "Progettazione, sviluppo, produzione e gestione delle vulnerabilità",
            "3": "Valutazione dei rischi di cibersicurezza", "4": "Informazioni per determinare il periodo di assistenza",
            "5": "Norme armonizzate, specifiche comuni o certificazioni applicate", "6": "Relazioni delle prove di conformità",
            "7": "Copia della dichiarazione di conformità UE", "8": "SBOM su richiesta motivata dell'autorità"},
}


def group_tags(num: str) -> list[str]:
    best = []
    for k, v in MR_GROUP_TAGS.items():
        if num == k or num.startswith(k + "."):
            if len(k) >= len(best[0] if best else ""):
                best = [k, v]
    return list(best[1]) if best else []


HEAD_RE = re.compile(r"^\s{3,8}([1-6](?:\.\d+)*)\.\s+(\S.*)$")


def build_mr(doc: dict) -> dict:
    L = load_lines(doc)
    law = "MR"
    first_annex = find(L, r"^\s{20,}ALLEGATO I\s*$")
    start = find(L, r"HANNO ADOTTATO IL PRESENTE REGOLAMENTO")
    arts = parse_articles(L, start, first_annex)

    sections = []  # struttura per la navigazione

    # ---- Articoli: tutti consultabili, alcuni valutabili paragrafo per paragrafo
    # articolo -> (tag di profilo, paragrafi rivolti al fabbricante; None = tutti)
    assess_arts = {
        10: (["macchina"], None),
        11: (["quasi_macchina"], None),
        21: (["macchina"], None),
        22: (["quasi_macchina"], None),
        24: (["macchina"], {"1", "2", "3", "4"}),
        25: (["macchina"], {"1", "2", "3", "4"}),
    }
    for a in arts:
        ref = f"Art. {a['num']}"
        sec = f"MR-ART"
        if a["num"] in assess_arts and a["paragraphs"]:
            tags, keep = assess_arts[a["num"]]
            for p in a["paragraphs"]:
                if keep is not None and p["n"] not in keep:
                    continue
                add(id=f"MR-ART{a['num']}-{p['n']}", law=law, section="MR-OBB", group=f"Art. {a['num']} — {a['title']}",
                    ref=f"{ref}, par. {p['n']}", title=a["title"], text=p["text"], pages=p["pages"], tags=tags,
                    kind="obbligo")
        add(id=f"MR-ART{a['num']}", law=law, section=sec, group="Articoli", ref=ref, title=a["title"],
            text=a["text"], pages=a["pages"], assessable=False, kind="articolo")

    # ---- Allegato III
    a3 = find(L, r"^\s{20,}ALLEGATO III\s*$")
    a4 = find(L, r"^\s{20,}ALLEGATO IV\s*$")
    pb = find(L, r"^\s{20,}PARTE B\s*$", a3, a4)
    ch1 = find(L, r"^\s{3,8}1\.\s+REQUISITI ESSENZIALI DI SICUREZZA", pb, a4)
    pa = find(L, r"^\s{20,}PARTE A\s*$", a3, a4)
    add(id="MR-III-A", law=law, section="MR-III", group="Allegato III — Parte A", ref="All. III, parte A",
        title="Definizioni", text=clean(L[pa + 1:pb]), pages=pages(L[pa:pb]), assessable=False, kind="definizioni")
    for key, chunk in split_items(L[pb + 1:ch1], r"^\s{3,8}(\d)\.\s{2,}\S"):
        title = {"1": "Valutazione e riduzione del rischio", "2": "Applicabilità dei RESS",
                 "3": "Inderogabilità e stato dell'arte", "4": "Struttura dell'allegato",
                 "5": "Quasi-macchine"}[key]
        add(id=f"MR-III-B{key}", law=law, section="MR-III", group="Allegato III — Parte B: Principi generali",
            ref=f"All. III, parte B, punto {key}", title=title, text=strip_marker(clean(chunk)), pages=pages(chunk),
            assessable=(key == "1"), always=(key == "1"), tags=["generale"], kind="principio")

    heads = [i for i in range(ch1, a4) if HEAD_RE.match(L[i].text)]
    nodes = []
    for n, i in enumerate(heads):
        j = heads[n + 1] if n + 1 < len(heads) else a4
        num, title = HEAD_RE.match(L[i].text).groups()
        title = MR_TITLE_FIX.get(num, title.strip()).replace(SOFT_HYPHEN, "")
        body = L[i + 1:j]
        # continuazione del titolo su riga successiva (es. 2.1, 2.2.2, 4.1.2.2)
        if num not in MR_TITLE_FIX and body and body[0].text.strip() and indent(body[0].text) >= 10:
            nxt = body[0].text.strip()
            if title.endswith(SOFT_HYPHEN) or title.endswith("­") or (nxt[:1].islower() and len(body) > 1 and
                                                                     not body[1].text.strip()):
                title = (title.rstrip(SOFT_HYPHEN) + ("" if title.endswith(SOFT_HYPHEN) else " ") + nxt).replace(SOFT_HYPHEN, "")
                body = body[1:]
        if num in MR_TITLE_FIX:
            body = [b for b in body if not CAPS_RE.match(b.text.strip() or "x")]
        nodes.append((num, title, body))

    titles = {num: title for num, title, _ in nodes}

    def group_of(num: str) -> str:
        parts = num.split(".")
        g = ".".join(parts[:2]) if len(parts) > 2 else parts[0]
        if g == num:
            g = parts[0]
        return f"{g}. {titles.get(g, '')}".strip()

    for num, title, body in nodes:
        text = clean(body)
        if not text:
            continue
        tags = group_tags(num)
        always = any(num == a or num.startswith(a + ".") for a in MR_ALWAYS)
        base = dict(law=law, section="MR-III", group=f"Allegato III — {group_of(num)}", title=title, tags=tags,
                    always=always, kind="ress", chapter=num.split(".")[0])
        if "." not in num:  # introduzione di capo
            add(id=f"MR-III-{num}", ref=f"All. III, capo {num}", text=text, pages=pages(body), assessable=False,
                **{**base, "kind": "introduzione", "title": title.capitalize()})
            continue
        if num in MR_INFO:
            add(id=f"MR-III-{num}", ref=f"All. III, punto {num}", text=text, pages=pages(body), assessable=False,
                **{**base, "kind": "definizioni"})
            continue
        if num in MR_SPLIT_PARAS or num in MR_SPLIT_LETTERS:
            for sid, ref, label, ch_text, ch_pages in split_section(body):
                add(id=f"MR-III-{num}-{sid}", ref=f"All. III, punto {num}, {ref}", text=ch_text, pages=ch_pages,
                    **{**base, "title": f"{title} — {label}"})
            continue
        add(id=f"MR-III-{num}", ref=f"All. III, punto {num}", text=text, pages=pages(body), **base)

    # ---- Allegato IV (documentazione tecnica)
    a5 = find(L, r"^\s{20,}ALLEGATO V\s*$")
    pbIV = find(L, r"^\s{20,}PARTE B\s*$", a4, a5)
    for part, s, e, tags, label in (("A", a4, pbIV, ["macchina"], "macchine e prodotti correlati"),
                                    ("B", pbIV, a5, ["quasi_macchina"], "quasi-macchine")):
        block = L[s:e]
        items = split_items(block, r"^\s{3,8}([a-z])\)\s")
        for key, ch in items:
            add(id=f"MR-IV-{part}-{key}", law=law, section="MR-DOC",
                group=f"All. IV, parte {part} — Documentazione tecnica ({label})",
                ref=f"All. IV, parte {part}, lettera {key})", title=(IV_TITLES[part].get(key) or f"lett. {key})"),
                text=clean(ch), pages=pages(ch), tags=tags, kind="documentazione")

    # ---- Allegato V (dichiarazioni)
    a6 = find(L, r"^\s{20,}ALLEGATO VI\s*$")
    pbV = find(L, r"^\s{20,}PARTE B\s*$", a5, a6)
    add(id="MR-V-A", law=law, section="MR-DOC", group="All. V — Dichiarazioni", ref="All. V, parte A",
        title="Contenuto della dichiarazione di conformità UE", text=clean(L[a5 + 4:pbV]), pages=pages(L[a5:pbV]),
        tags=["macchina"], kind="documentazione")
    add(id="MR-V-B", law=law, section="MR-DOC", group="All. V — Dichiarazioni", ref="All. V, parte B",
        title="Contenuto della dichiarazione di incorporazione UE", text=clean(L[pbV + 1:a6]), pages=pages(L[pbV:a6]),
        tags=["quasi_macchina"], kind="documentazione")

    # ---- Altri allegati: solo consultazione
    roman = ["I", "II", "VI", "VII", "VIII", "IX", "X", "XI"]
    anchors = {}
    for r in ["I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X", "XI", "XII"]:
        anchors[r] = find(L, rf"^\s{{20,}}ALLEGATO {r}\s*$")
    order = sorted(anchors.items(), key=lambda kv: kv[1])
    for n, (r, s) in enumerate(order):
        if r not in roman:
            continue
        e = order[n + 1][1] if n + 1 < len(order) else len(L)
        block = L[s + 1:e]
        title = next((b.text.strip() for b in block if b.text.strip()), "")
        add(id=f"MR-ALL-{r}", law=law, section="MR-ALL", group="Altri allegati", ref=f"Allegato {r}",
            title=title.capitalize() if title.isupper() else title, text=clean(block), pages=pages(block),
            assessable=False, kind="allegato")

    return {
        "id": "MR", "code": "(UE) 2023/1230", "short": "Regolamento Macchine",
        "title": doc["title"], "pdf": f"../data/normative/{doc['filename']}", "sha256": doc["sha256"],
        "sourceUrl": doc["sourceUrl"], "publicationDate": doc["publicationDate"],
        "application": "Si applica dal 20 gennaio 2027 (art. 54), salvo le eccezioni ivi previste.",
        "sections": [
            {"id": "MR-III", "title": "Allegato III — Requisiti essenziali di sicurezza e tutela della salute (RESS)"},
            {"id": "MR-OBB", "title": "Obblighi del fabbricante, dichiarazioni, marcatura e procedure"},
            {"id": "MR-DOC", "title": "Documentazione tecnica e dichiarazioni (All. IV e V)"},
            {"id": "MR-ART", "title": "Testo degli articoli"},
            {"id": "MR-ALL", "title": "Altri allegati"},
        ],
    }


def build_cra(doc: dict) -> dict:
    L = load_lines(doc)
    law = "CRA"
    first_annex = find(L, r"^\s{20,}ALLEGATO I\s*$")
    start = find(L, r"HANNO ADOTTATO IL PRESENTE REGOLAMENTO")
    arts = parse_articles(L, start, first_annex)
    # articolo -> paragrafi rivolti al fabbricante (gli altri riguardano Commissione/Stati/CSIRT)
    assess_arts = {6: None, 7: {"1"}, 13: {str(n) for n in range(1, 24)},
                   14: {"1", "2", "3", "4", "5", "7", "8"}, 28: {"1", "2", "3", "4"}, 30: {"1", "2", "3", "4"},
                   31: {"1", "2", "3", "4"}, 32: {"1", "2", "3", "4", "5"}}
    for a in arts:
        if a["num"] in assess_arts and a["paragraphs"]:
            keep = assess_arts[a["num"]]
            for p in a["paragraphs"]:
                if keep is not None and p["n"] not in keep:
                    continue
                add(id=f"CRA-ART{a['num']}-{p['n']}", law=law, section="CRA-OBB",
                    group=f"Art. {a['num']} — {a['title']}", ref=f"Art. {a['num']}, par. {p['n']}", title=a["title"],
                    text=p["text"], pages=p["pages"], tags=["cra"], kind="obbligo")
        add(id=f"CRA-ART{a['num']}", law=law, section="CRA-ART", group="Articoli", ref=f"Art. {a['num']}",
            title=a["title"], text=a["text"], pages=a["pages"], assessable=False, kind="articolo")

    anchors = {}
    for r in ["I", "II", "III", "IV", "V", "VI", "VII", "VIII"]:
        anchors[r] = find(L, rf"^\s{{20,}}ALLEGATO {r}\s*$")
    order = sorted(anchors.items(), key=lambda kv: kv[1])
    bounds = {r: (s, order[n + 1][1] if n + 1 < len(order) else len(L)) for n, (r, s) in enumerate(order)}

    # ---- Allegato I
    s, e = bounds["I"]
    p2 = find(L, r"^\s*Parte II Requisiti di gestione", s, e)
    p1 = find(L, r"^\s*Parte I Requisiti di cibersicurezza", s, e)
    items = split_items(L[p1 + 1:p2], r"^\s{3,10}(\d)\)\s")
    for key, ch in items:
        if key == "1":
            add(id="CRA-I-1-1", law=law, section="CRA-I", group="All. I, parte I — Proprietà di sicurezza del prodotto",
                ref="All. I, parte I, punto 1", title="Livello adeguato di cibersicurezza in base ai rischi",
                text=strip_marker(clean(ch)), pages=pages(ch), tags=["cra"], kind="rec", always=True)
        else:
            intro = next(k for k, b in enumerate(ch) if re.match(r"^\s{8,14}a\)\s", b.text))
            add(id="CRA-I-1-2", law=law, section="CRA-I", group="All. I, parte I — Proprietà di sicurezza del prodotto",
                ref="All. I, parte I, punto 2", title="Applicazione sulla base della valutazione dei rischi",
                text=strip_marker(clean(ch[:intro])), pages=pages(ch[:intro]), tags=["cra"], kind="rec",
                assessable=False)
            letters = split_items(ch[intro:], r"^\s{8,14}([a-m])\)\s")
            titles = {
                "a": "Nessuna vulnerabilità sfruttabile nota", "b": "Configurazione sicura per impostazione predefinita",
                "c": "Gestione delle vulnerabilità tramite aggiornamenti di sicurezza",
                "d": "Protezione dall'accesso non autorizzato", "e": "Riservatezza dei dati",
                "f": "Integrità di dati, comandi, programmi e configurazione", "g": "Minimizzazione dei dati",
                "h": "Disponibilità delle funzioni essenziali e resilienza (DoS)",
                "i": "Impatto minimo su altri dispositivi o reti", "j": "Limitazione delle superfici di attacco",
                "k": "Attenuazione dell'impatto degli incidenti", "l": "Registrazione e monitoraggio delle attività",
                "m": "Rimozione sicura di dati e impostazioni"}
            for key2, ch2 in letters:
                add(id=f"CRA-I-1-2{key2}", law=law, section="CRA-I",
                    group="All. I, parte I — Proprietà di sicurezza del prodotto",
                    ref=f"All. I, parte I, punto 2, lettera {key2})", title=titles[key2],
                    text=strip_marker(clean(ch2)), pages=pages(ch2), tags=["cra"], kind="rec")
    titles2 = {"1": "Identificazione delle vulnerabilità e SBOM", "2": "Correzione tempestiva delle vulnerabilità",
               "3": "Prove e riesami periodici di sicurezza", "4": "Divulgazione delle vulnerabilità risolte",
               "5": "Politica di divulgazione coordinata (CVD)", "6": "Condivisione informazioni e contatto per segnalazioni",
               "7": "Distribuzione sicura degli aggiornamenti", "8": "Diffusione tempestiva e gratuita degli aggiornamenti"}
    for key, ch in split_items(L[p2 + 1:e], r"^\s{3,10}(\d)\)\s"):
        add(id=f"CRA-I-2-{key}", law=law, section="CRA-I", group="All. I, parte II — Gestione delle vulnerabilità",
            ref=f"All. I, parte II, punto {key}", title=titles2[key], text=strip_marker(clean(ch)), pages=pages(ch),
            tags=["cra"], kind="rec", always=True)

    # ---- Allegato II, V, VII: elenchi valutabili
    for r, group, label in (("II", "All. II — Informazioni e istruzioni per l'utilizzatore", "Informazioni per l'utilizzatore"),
                            ("VII", "All. VII — Contenuto della documentazione tecnica", "Documentazione tecnica")):
        s, e = bounds[r]
        for key, ch in split_items(L[s:e], r"^\s{3,10}(\d)\.\s"):
            t = strip_marker(clean(ch))
            add(id=f"CRA-{r}-{key}", law=law, section="CRA-DOC", group=group, ref=f"All. {r}, punto {key}",
                title=CRA_TITLES[r][key], text=t, pages=pages(ch), tags=["cra"], kind="documentazione")
    s, e = bounds["V"]
    add(id="CRA-V", law=law, section="CRA-DOC", group="All. V — Dichiarazione di conformità UE", ref="Allegato V",
        title="Contenuto della dichiarazione di conformità UE", text=clean(L[s + 4:e]), pages=pages(L[s:e]),
        tags=["cra"], kind="documentazione")
    for r in ["III", "IV", "VI", "VIII"]:
        s, e = bounds[r]
        block = L[s + 1:e]
        title = next((b.text.strip() for b in block if b.text.strip()), "")
        add(id=f"CRA-ALL-{r}", law=law, section="CRA-ALL", group="Altri allegati", ref=f"Allegato {r}",
            title=title.capitalize(), text=clean(block), pages=pages(block), assessable=False, kind="allegato")

    return {
        "id": "CRA", "code": "(UE) 2024/2847", "short": "Cyber Resilience Act",
        "title": doc["title"], "pdf": f"../data/normative/{doc['filename']}", "sha256": doc["sha256"],
        "sourceUrl": doc["sourceUrl"], "publicationDate": doc["publicationDate"],
        "application": "Si applica dall'11 dicembre 2027; obblighi di segnalazione (art. 14) dall'11 settembre 2026 (art. 71).",
        "sections": [
            {"id": "CRA-I", "title": "Allegato I — Requisiti essenziali di cibersicurezza"},
            {"id": "CRA-OBB", "title": "Obblighi del fabbricante, segnalazione, dichiarazione e marcatura"},
            {"id": "CRA-DOC", "title": "Informazioni per l'utilizzatore e documentazione (All. II, V, VII)"},
            {"id": "CRA-ART", "title": "Testo degli articoli"},
            {"id": "CRA-ALL", "title": "Altri allegati"},
        ],
    }


PROFILE_TAGS = [
    {"id": "macchina", "label": "Macchina o prodotto correlato (art. 10, All. IV-A, V-A)", "group": "tipo"},
    {"id": "quasi_macchina", "label": "Quasi-macchina (art. 11, All. IV-B, V-B)", "group": "tipo"},
    {"id": "generale", "label": "RESS generali — Allegato III, capo 1", "group": "mr", "default": True},
    {"id": "alimentari", "label": "2.1 Macchine alimentari, cosmetiche o farmaceutiche", "group": "mr"},
    {"id": "portatili", "label": "2.2 Macchine portatili tenute o condotte a mano", "group": "mr"},
    {"id": "legno", "label": "2.3 Lavorazione del legno e materie simili", "group": "mr"},
    {"id": "fitosanitari", "label": "2.4 Applicazione di prodotti fitosanitari", "group": "mr"},
    {"id": "mobilita", "label": "3 Rischi dovuti alla mobilità", "group": "mr"},
    {"id": "sollevamento", "label": "4 Operazioni di sollevamento", "group": "mr"},
    {"id": "sotterraneo", "label": "5 Lavori sotterranei", "group": "mr"},
    {"id": "sollevamento_persone", "label": "6 Sollevamento di persone", "group": "mr"},
    {"id": "cra", "label": "Prodotto con elementi digitali — Cyber Resilience Act", "group": "cra"},
]


def main() -> int:
    corpus = json.loads(CORPUS.read_text(encoding="utf-8"))
    by_law = {d["law"]: d for d in corpus}
    laws = [build_mr(by_law["MR"]), build_cra(by_law["CRA"])]
    # ordine di consultazione e valutazione: regolamento, poi sezioni come dichiarate (RESS prima degli articoli)
    sec_order = {s["id"]: (i, j) for i, law in enumerate(laws) for j, s in enumerate(law["sections"])}
    REQS[:] = [r for _, r in sorted(enumerate(REQS), key=lambda x: (sec_order[x[1]["section"]], x[0]))]
    ids = [r["id"] for r in REQS]
    dup = {i for i in ids if ids.count(i) > 1}
    if dup:
        print("ID duplicati:", sorted(dup), file=sys.stderr)
        return 1
    empty = [r["id"] for r in REQS if not r["text"]]
    if empty:
        print("Voci senza testo:", empty, file=sys.stderr)
        return 1
    standards_notice = None
    if STANDARDS.exists():
        std = json.loads(STANDARDS.read_text(encoding="utf-8"))
        assert std.get("schema") == "regmac-standards-v1", "schema standards.json non supportato"
        ids_assessable = [r["id"] for r in REQS if r["assessable"]]
        for m in std["mappings"]:
            pat = m["match"]
            hits = [i for i in ids_assessable if (i.startswith(pat[:-1]) if pat.endswith("*") else i == pat)]
            if not hits:
                print(f"ATTENZIONE: nessun requisito per {pat}", file=sys.stderr)
            for r in REQS:
                if r["id"] in hits:
                    have = {x["code"] for x in r.setdefault("standards", [])}
                    r["standards"].extend(x for x in m["standards"] if x["code"] not in have)
        standards_notice = {"text": std.get("notice", ""), "updated": std.get("updated", "")}
        n_std = sum(1 for r in REQS if r.get("standards"))
        print(f"Norme di riferimento associate a {n_std} requisiti")
    catalog = {
        "schema": "regmac-wiki-catalog-v1",
        "generatedFrom": [{"law": d["law"], "sha256": d["sha256"], "filename": d["filename"]} for d in corpus],
        "disclaimer": ("Testo tratto dalle pubblicazioni originali in Gazzetta ufficiale (non consolidate). "
                       "La suddivisione in voci è uno strumento di lavoro: non sostituisce la lettura integrale "
                       "né la valutazione del rischio del fabbricante."),
        "laws": laws,
        "profileTags": PROFILE_TAGS,
        "standardsNotice": standards_notice,
        "requirements": REQS,
    }
    OUT_JSON.write_text(json.dumps(catalog, ensure_ascii=False, indent=1), encoding="utf-8")
    n_ass = sum(r["assessable"] for r in REQS)
    for law in ("MR", "CRA"):
        rs = [r for r in REQS if r["law"] == law]
        print(f"{law}: {len(rs)} voci, {sum(r['assessable'] for r in rs)} valutabili")
    print(f"Totale: {len(REQS)} voci, {n_ass} valutabili → {OUT_JSON.relative_to(ROOT)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
