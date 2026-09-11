#!/usr/bin/env python3
"""Regression test for the bundled Newtoki logo and banner UNWM presets.

This mirrors the Android preset path: alpha-content crop, bounded multi-scale
matching, alpha-correct resize and JPEG-aware inverse compositing. It writes a
restored PNG plus a before/result/reference comparison for each case.
"""

from __future__ import annotations

import argparse
import math
from dataclasses import dataclass
from pathlib import Path

import cv2
import numpy as np
from PIL import Image, ImageDraw


@dataclass(frozen=True)
class Case:
    name: str
    observed: Path
    watermark: Path
    reference_width: int
    offset_x: int
    offset_y: int
    minimum_confidence: float
    strength: float
    expected_rect: tuple[int, int, int, int]


def load_rgb(path: Path) -> np.ndarray:
    return np.asarray(Image.open(path).convert("RGB"), dtype=np.float32)


def load_rgba(path: Path) -> np.ndarray:
    return np.asarray(Image.open(path).convert("RGBA"), dtype=np.float32)


def crop_alpha_content(watermark: np.ndarray) -> np.ndarray:
    alpha = watermark[:, :, 3]
    threshold = max(2, round(float(alpha.max()) * 0.02))
    ys, xs = np.where(alpha > threshold)
    if len(xs) == 0:
        raise AssertionError("Watermark tidak memiliki alpha yang dapat dipakai")
    left = max(0, int(xs.min()) - 1)
    top = max(0, int(ys.min()) - 1)
    right = min(watermark.shape[1], int(xs.max()) + 2)
    bottom = min(watermark.shape[0], int(ys.max()) + 2)
    return watermark[top:bottom, left:right]


def resize_alpha_correct(watermark: np.ndarray, width: int, height: int) -> tuple[np.ndarray, np.ndarray]:
    alpha = watermark[:, :, 3] / 255.0
    premultiplied = watermark[:, :, :3] * alpha[:, :, None]
    resized_alpha = cv2.resize(alpha, (width, height), interpolation=cv2.INTER_LINEAR)
    resized_premultiplied = cv2.resize(
        premultiplied,
        (width, height),
        interpolation=cv2.INTER_LINEAR,
    )
    resized_rgb = np.divide(
        resized_premultiplied,
        resized_alpha[:, :, None],
        out=np.zeros_like(resized_premultiplied),
        where=resized_alpha[:, :, None] > 1e-6,
    )
    return resized_rgb, resized_alpha


def detect_bounded(
    observed: np.ndarray,
    original_watermark: np.ndarray,
    reference_width: int,
    offset_x: int,
    offset_y: int,
) -> tuple[int, int, int, int, float]:
    watermark = crop_alpha_content(original_watermark)
    page_height, page_width = observed.shape[:2]
    expected_scale = page_width / float(reference_width)
    original_height, original_width = original_watermark.shape[:2]
    hint_width = round(original_width * expected_scale)
    hint_height = round(original_height * expected_scale)
    hint_left = round(offset_x * expected_scale)
    hint_top = round(offset_y * expected_scale)
    padding_x = max(64, hint_width * 2)
    padding_y = max(64, hint_height * 2)
    search_left = max(0, hint_left - padding_x)
    search_top = max(0, hint_top - padding_y)
    search_right = min(page_width, hint_left + hint_width + padding_x)
    search_bottom = min(page_height, hint_top + hint_height + padding_y)

    target_gray = cv2.cvtColor(observed.astype(np.uint8), cv2.COLOR_RGB2GRAY)
    search_gray = target_gray[search_top:search_bottom, search_left:search_right]
    sample_gray = cv2.cvtColor(watermark.astype(np.uint8), cv2.COLOR_RGBA2GRAY)
    sample_alpha = watermark[:, :, 3].astype(np.uint8)
    factors = [0.94, 0.97, 0.985, 1.0, 1.015, 1.03, 1.06]
    best = (-math.inf, 0, 0, 0, 0, expected_scale)

    def evaluate(scale: float) -> None:
        nonlocal best
        width = max(4, round(watermark.shape[1] * scale))
        height = max(4, round(watermark.shape[0] * scale))
        if width > search_gray.shape[1] or height > search_gray.shape[0]:
            return
        interpolation = cv2.INTER_AREA if scale < 1.0 else cv2.INTER_CUBIC
        resized_gray = cv2.resize(sample_gray, (width, height), interpolation=interpolation)
        resized_alpha = cv2.resize(sample_alpha, (width, height), interpolation=cv2.INTER_LINEAR)
        mask = np.where(resized_alpha > 3, 255, 0).astype(np.uint8)
        scores = cv2.matchTemplate(search_gray, resized_gray, cv2.TM_CCORR_NORMED, mask=mask)
        _, confidence, _, location = cv2.minMaxLoc(scores)
        if math.isfinite(confidence) and confidence > best[0]:
            best = (confidence, location[0], location[1], width, height, scale)

    for factor in factors:
        evaluate(expected_scale * factor)
    coarse_scale = best[5]
    for step in range(-8, 9):
        evaluate(coarse_scale * (1.0 + step * 0.005))

    return (
        search_left + best[1],
        search_top + best[2],
        best[3],
        best[4],
        float(best[0]),
    )


def gaussian(data: np.ndarray, sigma: float) -> np.ndarray:
    if sigma < 0.1:
        return data.copy()
    return cv2.GaussianBlur(data, (0, 0), sigma, borderType=cv2.BORDER_REPLICATE)


def inverse_jpeg(
    observed: np.ndarray,
    watermark_rgb: np.ndarray,
    alpha: np.ndarray,
    strength: float,
) -> np.ndarray:
    adjusted = np.clip(alpha * strength, 0.0, 0.92)
    red, green, blue = (watermark_rgb[:, :, channel] for channel in range(3))
    watermark_y = 0.299 * red + 0.587 * green + 0.114 * blue
    watermark_cb = -0.168736 * red - 0.331264 * green + 0.5 * blue
    watermark_cr = 0.5 * red - 0.418688 * green - 0.081312 * blue
    observed_r, observed_g, observed_b = (observed[:, :, channel] for channel in range(3))
    observed_y = 0.299 * observed_r + 0.587 * observed_g + 0.114 * observed_b
    observed_cb = -0.168736 * observed_r - 0.331264 * observed_g + 0.5 * observed_b
    observed_cr = 0.5 * observed_r - 0.418688 * observed_g - 0.081312 * observed_b
    alpha_y = gaussian(adjusted, 0.30)
    alpha_chroma = gaussian(adjusted, 1.40)
    output_y = (observed_y - gaussian(adjusted * watermark_y, 0.30)) / np.maximum(1.0 - alpha_y, 0.08)
    output_cb = (observed_cb - gaussian(adjusted * watermark_cb, 1.40)) / np.maximum(1.0 - alpha_chroma, 0.08)
    output_cr = (observed_cr - gaussian(adjusted * watermark_cr, 1.40)) / np.maximum(1.0 - alpha_chroma, 0.08)
    return np.clip(
        np.stack(
            [
                output_y + 1.402 * output_cr,
                output_y - 0.344136 * output_cb - 0.714136 * output_cr,
                output_y + 1.772 * output_cb,
            ],
            axis=2,
        ),
        0.0,
        255.0,
    )


def support_mae(actual: np.ndarray, expected: np.ndarray, support: np.ndarray) -> float:
    return float(np.mean(np.abs(actual - expected)[support]))


def save_comparison(before: np.ndarray, result: np.ndarray, reference: np.ndarray, path: Path) -> None:
    panels = [before, result, reference]
    labels = ["SEBELUM", "HASIL PATCH", "REFERENSI"]
    width = before.shape[1]
    header = 48
    canvas = Image.new("RGB", (width * 3, before.shape[0] + header), "white")
    draw = ImageDraw.Draw(canvas)
    for index, (panel, label) in enumerate(zip(panels, labels)):
        x = index * width
        canvas.paste(Image.fromarray(np.uint8(np.clip(panel, 0, 255))), (x, header))
        draw.text((x + 12, 15), label, fill="black")
    canvas.save(path)


def run_case(case: Case, clean: np.ndarray, output_dir: Path) -> None:
    observed = load_rgb(case.observed)
    original_watermark = load_rgba(case.watermark)
    if observed.shape != clean.shape:
        raise AssertionError(f"{case.name}: dimensi input dan referensi berbeda")
    x, y, width, height, confidence = detect_bounded(
        observed,
        original_watermark,
        case.reference_width,
        case.offset_x,
        case.offset_y,
    )
    expected_x, expected_y, expected_width, expected_height = case.expected_rect
    assert abs(x - expected_x) <= 2 and abs(y - expected_y) <= 2, f"{case.name}: posisi deteksi meleset"
    assert abs(width - expected_width) <= 2 and abs(height - expected_height) <= 2, f"{case.name}: ukuran deteksi meleset"
    assert confidence >= case.minimum_confidence, f"{case.name}: confidence {confidence:.4f} terlalu rendah"

    watermark = crop_alpha_content(original_watermark)
    watermark_rgb, alpha = resize_alpha_correct(watermark, width, height)
    observed_roi = observed[y : y + height, x : x + width]
    clean_roi = clean[y : y + height, x : x + width]
    support = alpha > 0.01
    baseline_mae = support_mae(observed_roi, clean_roi, support)
    restored_roi = inverse_jpeg(observed_roi, watermark_rgb, alpha, case.strength)
    restored_mae = support_mae(restored_roi, clean_roi, support)
    assert restored_mae < baseline_mae * 0.40, f"{case.name}: sinyal watermark belum cukup hilang"

    restored = observed.copy()
    restored[y : y + height, x : x + width] = restored_roi
    result_path = output_dir / f"{case.name}_hasil.png"
    comparison_path = output_dir / f"{case.name}_perbandingan.png"
    Image.fromarray(np.uint8(np.clip(restored, 0, 255))).save(result_path)
    save_comparison(observed, restored, clean, comparison_path)
    print(
        f"PASS {case.name}: rect={x},{y},{width}x{height} confidence={confidence:.4f} "
        f"support_mae={baseline_mae:.4f}->{restored_mae:.4f} "
        f"improvement={(1.0 - restored_mae / baseline_mae) * 100.0:.2f}%"
    )
    print(f"  result={result_path}")
    print(f"  comparison={comparison_path}")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--clean", type=Path, required=True)
    parser.add_argument("--logo-input", type=Path, required=True)
    parser.add_argument("--banner-input", type=Path, required=True)
    parser.add_argument("--logo-watermark", type=Path, required=True)
    parser.add_argument("--banner-watermark", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    args = parser.parse_args()
    for path in [args.clean, args.logo_input, args.banner_input, args.logo_watermark, args.banner_watermark]:
        if not path.is_file():
            raise FileNotFoundError(path)
    args.output_dir.mkdir(parents=True, exist_ok=True)
    clean = load_rgb(args.clean)
    cases = [
        Case("kasus_2_logo", args.logo_input, args.logo_watermark, 608, 175, 314, 0.82, 0.98, (130, 233, 103, 106)),
        Case("kasus_3_banner", args.banner_input, args.banner_watermark, 575, 14, 258, 0.60, 1.00, (11, 202, 432, 398)),
    ]
    for case in cases:
        run_case(case, clean, args.output_dir)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
