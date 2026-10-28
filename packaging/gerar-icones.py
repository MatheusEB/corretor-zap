"""Gera os ícones do app a partir do mesmo desenho do logo (frontend/src/components/logo.tsx).

Uso: python packaging/gerar-icones.py
Saída: backend/src/main/resources/icone/icone-{16,32,64,256}.png e packaging/icone.ico
"""
import math
from pathlib import Path

from PIL import Image, ImageDraw

RAIZ = Path(__file__).resolve().parent.parent
TINTA = (28, 26, 23, 255)
PAPEL = (244, 241, 234, 255)
VERMELHAO = (200, 65, 43, 255)
ESCALA = 1024 / 32  # o logo é desenhado numa grade de 32x32


def p(x, y):
    return (x * ESCALA, y * ESCALA)


def desenhar():
    img = Image.new("RGBA", (1024, 1024), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    d.rounded_rectangle([p(0, 0), p(32, 32)], radius=8 * ESCALA, fill=TINTA)
    # balão de conversa
    d.rounded_rectangle([p(6, 9.5), p(26, 21.5)], radius=2 * ESCALA, fill=PAPEL)
    d.polygon([p(10, 21), p(15.5, 21), p(10, 25.2)], fill=PAPEL)
    # linha de texto
    d.line([p(10, 13.6), p(19.5, 13.6)], fill=TINTA, width=round(1.6 * ESCALA))
    for x in (10, 19.5):
        d.ellipse([p(x - 0.8, 12.8), p(x + 0.8, 14.4)], fill=TINTA)
    # onda de revisor
    pontos = [p(10 + t / 100 * 10.8, 17.4 + 0.75 * math.sin(t / 100 * 4 * math.pi)) for t in range(101)]
    d.line(pontos, fill=VERMELHAO, width=round(1.6 * ESCALA), joint="curve")
    for ponto in (pontos[0], pontos[-1]):
        r = 0.8 * ESCALA
        d.ellipse([ponto[0] - r, ponto[1] - r, ponto[0] + r, ponto[1] + r], fill=VERMELHAO)
    return img


def main():
    grande = desenhar()
    destino = RAIZ / "backend/src/main/resources/icone"
    destino.mkdir(parents=True, exist_ok=True)
    for tamanho in (16, 32, 64, 256):
        grande.resize((tamanho, tamanho), Image.LANCZOS).save(destino / f"icone-{tamanho}.png")
    grande.resize((256, 256), Image.LANCZOS).save(
        RAIZ / "packaging/icone.ico", sizes=[(16, 16), (24, 24), (32, 32), (48, 48), (64, 64), (128, 128), (256, 256)]
    )
    print("ícones gerados em", destino)


if __name__ == "__main__":
    main()
