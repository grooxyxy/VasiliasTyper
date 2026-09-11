#!/usr/bin/env python3
"""Reproducible unwatermark benchmark using a known clean reference.

Usage:
  python3 tools/unwatermark_reference_test.py CLEAN.jpg WATERMARKED.jpg WATERMARK.png

The script does not modify application assets. It locates the raster transform,
runs the reference inverse-alpha method and the patched JPEG-aware method, then
fails if placement or restoration quality regresses.
"""

from __future__ import annotations

import math
import sys
from pathlib import Path

import cv2
import numpy as np
from PIL import Image


def load_rgb(path: str) -> np.ndarray:
    return np.asarray(Image.open(path).convert("RGB"), dtype=np.float32)


def load_rgba(path: str) -> np.ndarray:
    return np.asarray(Image.open(path).convert("RGBA"), dtype=np.float32)


def resize_watermark(watermark: np.ndarray, width: int, height: int) -> tuple[np.ndarray, np.ndarray]:
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


def detect_like_android(observed: np.ndarray, watermark: np.ndarray) -> tuple[int, int, int, int, float]:
    """Mirror the patched coarse-to-fine OpenCV detector without using clean data."""
    target_gray = cv2.cvtColor(observed.astype(np.uint8), cv2.COLOR_RGB2GRAY)
    sample_gray = cv2.cvtColor(watermark.astype(np.uint8), cv2.COLOR_RGBA2GRAY)
    sample_alpha = watermark[:, :, 3].astype(np.uint8)
    min_scale = max(0.18, 40.0 / min(watermark.shape[0], watermark.shape[1]))
    max_scale = min(
        3.0,
        observed.shape[1] / watermark.shape[1],
        observed.shape[0] / watermark.shape[0],
    )
    scales: list[float] = []
    scale = min_scale
    while scale <= max_scale:
        scales.append(scale)
        scale *= 1.035
    scales.extend([max_scale, min(max(1.0, min_scale), max_scale)])

    best = (-math.inf, 0, 0, 0, 0, 1.0)

    def evaluate(candidate_scale: float) -> None:
        nonlocal best
        width = max(4, round(watermark.shape[1] * candidate_scale))
        height = max(4, round(watermark.shape[0] * candidate_scale))
        if width > observed.shape[1] or height > observed.shape[0]:
            return
        resized_gray = cv2.resize(sample_gray, (width, height), interpolation=cv2.INTER_AREA)
        resized_alpha = cv2.resize(sample_alpha, (width, height), interpolation=cv2.INTER_LINEAR)
        mask = np.where(resized_alpha > 3, 255, 0).astype(np.uint8)
        if cv2.countNonZero(mask) < 12:
            return
        scores = cv2.matchTemplate(target_gray, resized_gray, cv2.TM_CCORR_NORMED, mask=mask)
        _, confidence, _, location = cv2.minMaxLoc(scores)
        if math.isfinite(confidence) and confidence > best[0]:
            best = (confidence, location[0], location[1], width, height, candidate_scale)

    for candidate in scales:
        evaluate(candidate)
    coarse_scale = best[5]
    for step in range(-8, 9):
        evaluate(coarse_scale * (1.0 + step * 0.005))
    return best[1], best[2], best[3], best[4], best[0]


def locate(clean: np.ndarray, observed: np.ndarray, watermark: np.ndarray) -> tuple[int, int, int, int, float]:
    """Ground-truth-only transform fit used to verify the Android detector."""
    page_height, page_width = clean.shape[:2]
    reference_scale = page_width / 720.0
    candidates: list[tuple[float, int, int, int, int, float]] = []
    for scale in np.arange(reference_scale - 0.04, reference_scale + 0.0401, 0.005):
        width = round(watermark.shape[1] * scale)
        height = round(watermark.shape[0] * scale)
        wm_rgb, alpha = resize_watermark(watermark, width, height)
        support = np.repeat((alpha > 0.005)[:, :, None], 3, axis=2)
        for y in range(max(0, page_height - height - 24), page_height - height + 1):
            for x in range(max(0, page_width - width - 24), page_width - width + 1):
                base = alpha[:, :, None] * (wm_rgb - clean[y : y + height, x : x + width])
                delta = observed[y : y + height, x : x + width] - clean[y : y + height, x : x + width]
                base_values = base[support]
                denominator = float(np.dot(base_values, base_values))
                if denominator <= 1.0:
                    continue
                strength = float(np.clip(np.dot(base_values, delta[support]) / denominator, 0.5, 1.5))
                residual = delta - strength * base
                mse = float(np.mean(np.square(residual[support])))
                candidates.append((mse, x, y, width, height, strength))
    if not candidates:
        raise RuntimeError("No valid watermark transform was found")
    _, x, y, width, height, strength = min(candidates)
    return x, y, width, height, strength


def inverse_rgb(observed: np.ndarray, wm_rgb: np.ndarray, alpha: np.ndarray, strength: float) -> np.ndarray:
    adjusted = np.clip(alpha * strength, 0.0, 0.996)
    return np.clip(
        (observed - adjusted[:, :, None] * wm_rgb) / (1.0 - adjusted[:, :, None]),
        0.0,
        255.0,
    )


def gaussian(data: np.ndarray, sigma: float) -> np.ndarray:
    if sigma < 0.1:
        return data.copy()
    return cv2.GaussianBlur(data, (0, 0), sigma, borderType=cv2.BORDER_REPLICATE)


def inverse_jpeg(observed: np.ndarray, wm_rgb: np.ndarray, alpha: np.ndarray, strength: float) -> tuple[np.ndarray, np.ndarray]:
    adjusted = np.clip(alpha * strength, 0.0, 0.92)
    luma_sigma = 0.30
    chroma_sigma = 1.40

    red, green, blue = (wm_rgb[:, :, channel] for channel in range(3))
    wm_y = 0.299 * red + 0.587 * green + 0.114 * blue
    wm_cb = -0.168736 * red - 0.331264 * green + 0.5 * blue
    wm_cr = 0.5 * red - 0.418688 * green - 0.081312 * blue

    obs_r, obs_g, obs_b = (observed[:, :, channel] for channel in range(3))
    obs_y = 0.299 * obs_r + 0.587 * obs_g + 0.114 * obs_b
    obs_cb = -0.168736 * obs_r - 0.331264 * obs_g + 0.5 * obs_b
    obs_cr = 0.5 * obs_r - 0.418688 * obs_g - 0.081312 * obs_b

    alpha_y = gaussian(adjusted, luma_sigma)
    alpha_chroma = gaussian(adjusted, chroma_sigma)
    out_y = (obs_y - gaussian(adjusted * wm_y, luma_sigma)) / np.maximum(1.0 - alpha_y, 0.08)
    out_cb = (obs_cb - gaussian(adjusted * wm_cb, chroma_sigma)) / np.maximum(1.0 - alpha_chroma, 0.08)
    out_cr = (obs_cr - gaussian(adjusted * wm_cr, chroma_sigma)) / np.maximum(1.0 - alpha_chroma, 0.08)
    output = np.stack(
        [
            out_y + 1.402 * out_cr,
            out_y - 0.344136 * out_cb - 0.714136 * out_cr,
            out_y + 1.772 * out_cb,
        ],
        axis=2,
    )
    return np.clip(output, 0.0, 255.0), np.maximum(alpha_y, alpha_chroma)


def bilateral_support(image: np.ndarray, support: np.ndarray, radius: int = 4, range_sigma: float = 24.0) -> np.ndarray:
    source = image.copy()
    output = image.copy()
    height, width = support.shape
    spatial_sigma = max(0.8, radius * 0.75)
    range_denominator = 2.0 * range_sigma * range_sigma
    spatial_denominator = 2.0 * spatial_sigma * spatial_sigma
    for y in range(height):
        for x in range(width):
            if support[y, x] <= 4.0 / 255.0:
                continue
            center = source[y, x]
            weighted = np.zeros(3, dtype=np.float64)
            weight_sum = 0.0
            for dy in range(-radius, radius + 1):
                sample_y = min(height - 1, max(0, y + dy))
                for dx in range(-radius, radius + 1):
                    sample_x = min(width - 1, max(0, x + dx))
                    sample = source[sample_y, sample_x]
                    difference = sample - center
                    colour_distance = float(np.dot(difference, difference)) / 3.0
                    spatial_distance = float(dx * dx + dy * dy)
                    weight = math.exp(
                        -colour_distance / range_denominator
                        - spatial_distance / spatial_denominator
                    )
                    weighted += sample * weight
                    weight_sum += weight
            output[y, x] = weighted / weight_sum
    return output


def mae(actual: np.ndarray, expected: np.ndarray, support: np.ndarray) -> float:
    return float(np.mean(np.abs(actual - expected)[support]))


def main() -> int:
    if len(sys.argv) != 4:
        print(__doc__.strip())
        return 2
    for path in sys.argv[1:]:
        if not Path(path).is_file():
            raise FileNotFoundError(path)

    clean = load_rgb(sys.argv[1])
    observed = load_rgb(sys.argv[2])
    watermark = load_rgba(sys.argv[3])
    if clean.shape != observed.shape:
        raise AssertionError("Clean and watermarked dimensions differ")

    x, y, width, height, fitted_strength = locate(clean, observed, watermark)
    detected_x, detected_y, detected_width, detected_height, confidence = detect_like_android(
        observed,
        watermark,
    )
    wm_rgb, alpha = resize_watermark(watermark, width, height)
    clean_roi = clean[y : y + height, x : x + width]
    observed_roi = observed[y : y + height, x : x + width]
    support = alpha > 0.01

    baseline = mae(observed_roi, clean_roi, support)
    reference = inverse_rgb(observed_roi, wm_rgb, alpha, 0.94)
    jpeg, correction_support = inverse_jpeg(observed_roi, wm_rgb, alpha, 0.94)
    patched = bilateral_support(jpeg, correction_support)
    reference_mae = mae(reference, clean_roi, support)
    patched_mae = mae(patched, clean_roi, support)

    print(f"placement={x},{y} size={width}x{height} fitted_strength={fitted_strength:.4f}")
    print(
        f"android_detector={detected_x},{detected_y} "
        f"size={detected_width}x{detected_height} confidence={confidence:.4f}"
    )
    print(f"input_support_mae={baseline:.4f}")
    print(f"html_inverse_support_mae={reference_mae:.4f}")
    print(f"patched_support_mae={patched_mae:.4f}")
    print(f"improvement_vs_input={(1.0 - patched_mae / baseline) * 100.0:.2f}%")

    assert (x, y, width, height) == (444, 885, 143, 139), "Reference placement regressed"
    assert (
        detected_x,
        detected_y,
        detected_width,
        detected_height,
    ) == (x, y, width, height), "Coarse-to-fine detector missed the verified transform"
    assert confidence >= 0.94, "Detection confidence regressed"
    assert patched_mae < reference_mae * 0.60, "Patched method must clearly beat plain inverse alpha"
    assert patched_mae < baseline * 0.25, "Restoration did not remove enough watermark signal"
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
