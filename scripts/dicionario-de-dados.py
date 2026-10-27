"""Gera o dicionario de dados do desafio Burgubras.

Os .csv e .xlsx nao sao lidos pela ingestao (o extrator suporta vtt, pdf, pptx,
md e txt). Este script le essas fontes e escreve um Markdown -- que a ingestao
le -- com a estrutura de cada tabela: colunas, contagem de linhas e algumas
linhas de exemplo. A ideia e tornar os dados pesquisaveis sem jogar 100 mil
linhas de CSV na base vetorial, o que geraria milhares de chunks sem valor.
"""
import csv
import glob
import os
import sys

BASE = sys.argv[1]
OUT = os.path.join(BASE, "DICIONARIO_DE_DADOS.md")
AMOSTRAS = 3


def ler_csv(caminho):
    """Devolve (colunas, linhas_de_amostra, total_de_linhas)."""
    for encoding in ("utf-8-sig", "latin-1"):
        try:
            with open(caminho, newline="", encoding=encoding) as f:
                amostra = f.read(8192)
                f.seek(0)
                try:
                    dialeto = csv.Sniffer().sniff(amostra, delimiters=",;\t|")
                except csv.Error:
                    dialeto = csv.excel
                leitor = csv.reader(f, dialeto)
                colunas = next(leitor, [])
                exemplos, total = [], 0
                for linha in leitor:
                    total += 1
                    if len(exemplos) < AMOSTRAS and any(c.strip() for c in linha):
                        exemplos.append(linha)
                return colunas, exemplos, total
        except UnicodeDecodeError:
            continue
    return [], [], 0


def bloco_amostra(colunas, linha):
    partes = []
    for i, coluna in enumerate(colunas):
        valor = linha[i].strip() if i < len(linha) else ""
        if valor:
            partes.append(f"`{coluna.strip()}` = {valor}")
    return "; ".join(partes)


linhas_md = [
    "# Dicionario de dados do desafio Burgubras",
    "",
    "Estrutura das fontes de dados do desafio: as 9 tabelas em CSV que entram pelo DataDrop e a",
    "planilha de mock data. Para cada tabela: as colunas, quantas linhas tem e algumas linhas de",
    "exemplo.",
    "",
    "> Este arquivo e **gerado** a partir dos proprios CSVs e da planilha. Ele existe porque a",
    "> ingestao nao le `.csv` nem `.xlsx` -- sem ele, a estrutura desses dados nao estaria na base.",
    "> Os arquivos originais continuam no bucket, ao lado deste, para download.",
    "",
]

csvs = sorted(glob.glob(os.path.join(BASE, "04_CSVs_para_DataDrop", "*.csv")))
linhas_md += ["## Tabelas em CSV (carga via DataDrop)", ""]

for caminho in csvs:
    nome = os.path.basename(caminho)
    colunas, exemplos, total = ler_csv(caminho)
    linhas_md += [f"### {nome}", ""]
    linhas_md += [f"- **Linhas de dados:** {total:,}".replace(",", "."), f"- **Colunas ({len(colunas)}):** "
                  + ", ".join(f"`{c.strip()}`" for c in colunas if c.strip()), ""]
    if exemplos:
        linhas_md += ["Linhas de exemplo:", ""]
        for i, linha in enumerate(exemplos, 1):
            linhas_md += [f"{i}. {bloco_amostra(colunas, linha)}"]
        linhas_md += [""]

xlsx = sorted(glob.glob(os.path.join(BASE, "03_Dados_Excel", "*.xlsx")))
if xlsx:
    import openpyxl

    linhas_md += ["## Planilha de mock data", ""]
    for caminho in xlsx:
        nome = os.path.basename(caminho)
        linhas_md += [f"### {nome}", ""]
        wb = openpyxl.load_workbook(caminho, read_only=True, data_only=True)
        for aba in wb.worksheets:
            cabecalho = []
            for linha in aba.iter_rows(min_row=1, max_row=1, values_only=True):
                cabecalho = [str(v).strip() for v in linha if v is not None]
            total = max((aba.max_row or 1) - 1, 0)
            linhas_md += [
                f"- **Aba `{aba.title}`** — {total:,}".replace(",", ".") + " linhas de dados; colunas: "
                + (", ".join(f"`{c}`" for c in cabecalho) if cabecalho else "(vazia)")
            ]
        wb.close()
        linhas_md += [""]

with open(OUT, "w", encoding="utf-8") as f:
    f.write("\n".join(linhas_md) + "\n")

print(f"escrito: {OUT}")
print(f"{len(csvs)} CSVs, {len(xlsx)} planilha(s)")
