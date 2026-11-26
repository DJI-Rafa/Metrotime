#!/usr/bin/env python3
"""
Traz as amostras reais dos comboios CP para os testes da app.

A Cloud Function guarda em cp_samples, a cada 10 minutos e durante 7 dias, uma fotografia dos comboios
({ at, vehicles, routes }). Este script copia-as para app/src/test/resources/cp_samples, onde o CpLiveReplayTest as
repete e verifica que nenhum comboio anda para trás, sai do percurso ou fica atrás da última estação da CP.

Usa o Firebase CLI com a tua sessão (firebase login):

    python tools/fetch_cp_samples.py                       # as últimas 6 (uma hora)
    python tools/fetch_cp_samples.py --last 36             # as últimas 36 (seis horas)
    python tools/fetch_cp_samples.py --key 2026-11-23T13-00Z

Para guardar já um momento sem esperar pela função: node tools/cp_sample_now.js <saída.json>
"""
import argparse
import json
import pathlib
import subprocess
import sys

PROJECT = "metrotime-569ec"
OUT = pathlib.Path(__file__).resolve().parent.parent / "app" / "src" / "test" / "resources" / "cp_samples"


def firebase_get(path, *extra):
    cmd = ["firebase", "database:get", path, "--project", PROJECT, *extra]
    result = subprocess.run(cmd, capture_output=True, text=True, encoding="utf-8", shell=(sys.platform == "win32"))
    if result.returncode != 0:
        sys.exit(result.stderr or result.stdout or f"falhou: {' '.join(cmd)}")
    return json.loads(result.stdout or "null")


def main():
    parser = argparse.ArgumentParser(description="Copia amostras de cp_samples para os testes da app.")
    parser.add_argument("--last", type=int, default=6, help="quantas das mais recentes (por omissão 6)")
    parser.add_argument("--key", action="append", help="uma amostra em particular (pode repetir)")
    args = parser.parse_args()

    keys = args.key or sorted((firebase_get("/cp_samples", "--shallow") or {}).keys())[-args.last:]
    if not keys:
        sys.exit("Ainda não há amostras (a função nova guarda uma a cada 10 minutos).")
    OUT.mkdir(parents=True, exist_ok=True)
    for key in keys:
        sample = firebase_get(f"/cp_samples/{key}")
        if not sample:
            print(f"{key}: vazia")
            continue
        (OUT / f"{key}.json").write_text(json.dumps(sample, ensure_ascii=False, indent=1), encoding="utf-8")
        print(f"{key}: {len(sample.get('vehicles') or {})} comboios, {len(sample.get('routes') or {})} percursos")


if __name__ == "__main__":
    main()
