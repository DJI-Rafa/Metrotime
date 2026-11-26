"""
Avisos da CP -> Firebase (nó cp_avisos), lidos pela app no separador Comboios e em cada estação.

O site da CP (cp.pt/pt/avisos) carrega os avisos de um endpoint JSON público; antes este script abria
um Chromium com Playwright e tirava só os títulos do HTML. Agora lê o JSON diretamente e guarda também
o resumo, as datas, as linhas/serviços afetados e o link de cada aviso.

Formato escrito:
  cp_avisos/ultima_atualizacao  -> ms
  cp_avisos/lista[]             -> { titulo, resumo, link, inicio, fim, categorias[], prioridade }
"""
import time

import firebase_admin
import requests
from firebase_admin import credentials, db

URL = "https://www.cp.pt/bei/getContentsList"
PATH = "PWA/Homepage/Avisos"
PER_PAGE = 50
LINK = "https://www.cp.pt/pt/detalhe-aviso/{}"
HEADERS = {"User-Agent": "Mozilla/5.0 (Metrotime; avisos)", "Accept": "application/json"}

firebase_admin.initialize_app(
    credentials.Certificate("serviceAccountKey.json"),
    {"databaseURL": "https://metrotime-569ec-default-rtdb.europe-west1.firebasedatabase.app"},
)


def fields(item, lang="pt-PT"):
    """contentFields de um idioma, indexados pelo rótulo ("Título conteúdo", "Lead", ...)."""
    out = {}
    for f in (item.get("contentFields") or {}).get(lang) or []:
        value = (f.get("contentFieldValue") or {}).get("data")
        if isinstance(value, str):
            out[f.get("label")] = value.strip()
    return out


def to_notice(item):
    f = fields(item)
    title = f.get("Título conteúdo") or ((item.get("title") or {}).get("pt-PT") or "").split(" | ")[0].strip()
    if not title:
        return None
    slug = item.get("friendlyUrlPath") or ""
    return {
        "titulo": title,
        "resumo": f.get("Lead", ""),
        "link": LINK.format(slug) if slug else "https://www.cp.pt/pt/avisos",
        "inicio": (f.get("Data de início") or "")[:10],
        "fim": (f.get("Data de fim") or "")[:10],
        "categorias": [c.get("taxonomyCategoryName") for c in item.get("taxonomyCategoryBriefs") or [] if c.get("taxonomyCategoryName")],
        "prioridade": item.get("priority") or 0,
    }


def fetch_avisos():
    avisos, page, max_pages = [], 1, 1
    while page <= max_pages and page <= 20:
        params = {"path": PATH, "itensPage": PER_PAGE, "page": page, "order": "priority:desc"}
        r = requests.get(URL, params=params, headers=HEADERS, timeout=30)
        r.raise_for_status()
        data = r.json()
        if not isinstance(data.get("item"), list):
            raise ValueError("Resposta inesperada da CP (sem lista 'item')")
        avisos += [n for n in map(to_notice, data["item"]) if n]
        max_pages = int(data.get("maxPages") or 1)
        page += 1
    # Sem duplicados (mesmo título), pela ordem de prioridade da CP
    seen, unique = set(), []
    for a in avisos:
        if a["titulo"] not in seen:
            seen.add(a["titulo"])
            unique.append(a)
    return unique


def main():
    # Se o pedido falhar, levanta exceção e o Firebase fica como estava. Uma lista vazia é válida:
    # quer dizer que a CP não tem avisos e os antigos têm de desaparecer (antes ficavam para sempre).
    avisos = fetch_avisos()
    print(f"{len(avisos)} avisos.")
    for a in avisos:
        print(f"- {a['titulo']} ({a['inicio']} a {a['fim'] or '...'})")
    db.reference("cp_avisos").set({"ultima_atualizacao": int(time.time() * 1000), "lista": avisos})
    print("Guardado no Firebase.")


if __name__ == "__main__":
    main()
