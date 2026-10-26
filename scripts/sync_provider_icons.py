#!/usr/bin/env python3
"""Sync provider brand icons from @lobehub/icons-static-png into res drawables.

Usage:
    ./scripts/sync_provider_icons.py [--version latest] [--workdir /tmp/lobe-icons-sync]

What it does:
  1. `npm pack @lobehub/icons` + `@lobehub/icons-static-png`.
  2. Parse `providerConfig.js` + `providerEnum.js` for web's own
     keyword -> Icon mapping (same source `ProviderIcon` uses), with
     lowercase fallback (mirrors lobe's unsloth push-patch).
  3. Downscale ready-made 640px light/dark PNGs to 144px lossless WebP:
     `drawable-nodpi/ic_provider_<id>.webp` (light) and
     `drawable-night-nodpi/ic_provider_<id>.webp` (dark).
  4. Emit `ProviderIconMap.kt` (id -> drawable).

Icons missing upstream fall back to InitialAvatar at runtime.

Requires: npm, python3 + PIL.
"""

import argparse
import glob
import os
import re
import shutil
import subprocess

# Our 86 provider ids (model-bank cards).
PROVIDER_IDS = (
    "ai21 ai302 ai360 aihubmix akashchat antgroup anthropic azure azureai "
    "baichuan bailiancodingplan bedrock bfl cerebras chatgpt cloudflare cohere "
    "cometapi comfyui deepseek fal fireworksai giteeai github githubcopilot "
    "glmcodingplan google groq higress huggingface hunyuan infiniai internlm "
    "jina kimicodingplan lmstudio lobehub longcat meta minimax minimaxcodingplan "
    "mistral modelscope moonshot nebius newapi novita nvidia ollama ollamacloud "
    "openai opencodecodingplan opencodezen openrouter perplexity ppio qiniu "
    "qwen replicate sambanova search1api sensenova siliconcloud spark stepfun "
    "straico streamlake supergrok taichu tencentcloud togetherai unsloth "
    "upstage v0 vercelaigateway vertexai vllm volcengine volcenginecodingplan "
    "wenxin xai xiaomimimo xinference zenmux zeroone zhipu zai"
).split()

SIZE_PX = 144


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--version", default="latest")
    ap.add_argument("--workdir", default="/tmp/lobe-icons-sync")
    args = ap.parse_args()
    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

    from PIL import Image

    work = args.workdir
    os.makedirs(work, exist_ok=True)
    print(f"packing @lobehub/icons-static-png@{args.version} ...")
    subprocess.run(
        ["npm", "pack", f"@lobehub/icons-static-png@{args.version}"],
        cwd=work, check=True, capture_output=True,
    )
    tgz = sorted(glob.glob(os.path.join(work, "lobehub-icons-static-png-*.tgz")))[-1]
    pkg = os.path.join(work, "pngpkg")
    shutil.rmtree(pkg, ignore_errors=True)
    os.makedirs(pkg)
    subprocess.run(["tar", "-xzf", tgz, "-C", pkg], check=True)
    png_root = os.path.join(pkg, "package")

    # Web's own mapping: providerConfig.js (Icon+keywords) + providerEnum.js.
    print("packing @lobehub/icons@latest for providerConfig.js ...")
    subprocess.run(
        ["npm", "pack", "@lobehub/icons@latest"],
        cwd=work, check=True, capture_output=True,
    )
    itgz = sorted(glob.glob(os.path.join(work, "lobehub-icons-5*.tgz")))[-1]
    ipkg = os.path.join(work, "iconpkg")
    shutil.rmtree(ipkg, ignore_errors=True)
    os.makedirs(ipkg)
    subprocess.run(["tar", "-xzf", itgz, "-C", ipkg], check=True)
    es = os.path.join(ipkg, "package", "es")
    enum = dict(
        re.findall(
            r'ModelProvider\["(\w+)"\] = "([^"]+)"',
            open(os.path.join(es, "features", "providerEnum.js")).read(),
        )
    )
    kw2icon = {}
    cfg = open(os.path.join(es, "features", "providerConfig.js")).read()
    for m in re.finditer(
        r"Icon:\s*(\w+),(?:\s*combineMultiple:[^,]+,)?\s*keywords:\s*\[([^\]]+)\]",
        cfg,
    ):
        for k in re.findall(r"ModelProvider\.(\w+)", m.group(2)):
            if enum.get(k):
                kw2icon[enum[k]] = m.group(1)
    print(f"web mapping: {len(kw2icon)} keywords")

    res = os.path.join(root, "core/designsystem/src/main/res")
    day = os.path.join(res, "drawable-nodpi")
    os.makedirs(day, exist_ok=True)
    # Legacy qualifier dir: -night follows the *system* theme and mismatches
    # when the app overrides it. Dark variants now use the _dark suffix.
    shutil.rmtree(os.path.join(res, "drawable-night-nodpi"), ignore_errors=True)
    # Drop stale outputs (vectors from the old approach + orphaned webp).
    for stale in glob.glob(os.path.join(res, "drawable", "ic_provider_*.xml")):
        os.remove(stale)
    expected = {f"ic_provider_{pid}.webp" for pid in PROVIDER_IDS}
    expected |= {f.replace(".webp", "_dark.webp") for f in expected}
    for f in os.listdir(day):
        if f.startswith("ic_provider_") and f not in expected:
            os.remove(os.path.join(day, f))

    ok, miss = [], []
    for pid in PROVIDER_IDS:
        # Web order: mapped Icon first, then lowercase self (covers
        # lobe's push-patches like unsloth that upstream mapping lacks).
        mapped = kw2icon.get(pid)
        cands = []
        if mapped:
            cands.append(mapped.lower())
        if pid not in cands:
            cands.append(pid)
        pick = None
        for cand in cands:
            if not cand:
                continue
            for suffix in ("-color.png", "-brand-color.png", ".png"):
                fn = cand + suffix
                if os.path.exists(
                    os.path.join(png_root, "light", fn)
                ) and os.path.exists(os.path.join(png_root, "dark", fn)):
                    pick = (cand, fn)
                    break
            if pick:
                break
        if not pick:
            miss.append(pid)
            continue
        _, fn = pick
        for theme, suffix in (("light", ""), ("dark", "_dark")):
            src = os.path.join(png_root, theme, fn)
            im = Image.open(src).convert("RGBA").resize(
                (SIZE_PX, SIZE_PX), Image.LANCZOS
            )
            im.save(
                os.path.join(day, f"ic_provider_{pid}{suffix}.webp"),
                "WEBP", lossless=True, method=6,
            )
        ok.append(pid)

    ok_set = {f"ic_provider_{pid}.webp" for pid in ok}
    ok_set |= {f.replace(".webp", "_dark.webp") for f in ok_set}
    for f in os.listdir(day):
        if f.startswith("ic_provider_") and f not in ok_set:
            os.remove(os.path.join(day, f))
            print(f"  dropped orphan {f}")

    # ---- Model icons: same pipeline driven by web modelConfig.js ----
    mcfg = open(os.path.join(es, "features", "modelConfig.js")).read()
    model_entries = re.findall(
        r"Icon:\s*(\w+),\s*keywords:\s*\[([^\]]+)\]", mcfg
    )
    model_map = []  # (icon_lower, [keywords]) in web match order
    model_ok, model_miss = [], []
    for icon, kws in model_entries:
        kws = re.findall(r"'([^']+)'", kws)
        base = icon.lower()
        fn = next(
            (
                base + s
                for s in ("-color.png", "-brand-color.png", ".png")
                if os.path.exists(os.path.join(png_root, "light", base + s))
                and os.path.exists(os.path.join(png_root, "dark", base + s))
            ),
            None,
        )
        if not fn:
            model_miss.append(icon)
            continue
        if base not in model_ok:
            for theme, suffix in (("light", ""), ("dark", "_dark")):
                src = os.path.join(png_root, theme, fn)
                im = Image.open(src).convert("RGBA").resize(
                    (SIZE_PX, SIZE_PX), Image.LANCZOS
                )
                im.save(
                    os.path.join(day, f"ic_model_{base}{suffix}.webp"),
                    "WEBP", lossless=True, method=6,
                )
            model_ok.append(base)
        model_map.append((base, kws))
    print(f"model icons: {len(model_ok)} ok, missing: {sorted(set(model_miss))}")

    kt = [
        "package cc.jaxy.bob.core.designsystem.component",
        "",
        "import androidx.annotation.DrawableRes",
        "import cc.jaxy.bob.core.designsystem.R",
        "",
        "/** Generated by `scripts/sync_provider_icons.py`; do not hand-edit. */",
        "@DrawableRes",
        "fun providerBrandIcon(providerId: String, dark: Boolean = false): Int? {",
        "    val id = providerId.lowercase()",
        "    if (dark) {",
        "        return when (id) {",
    ]
    for pid in sorted(ok):
        kt.append(f'            "{pid}" -> R.drawable.ic_provider_{pid}_dark')
    kt += [
        "            else -> null",
        "        }",
        "    }",
        "    return when (id) {",
    ]
    for pid in sorted(ok):
        kt.append(f'        "{pid}" -> R.drawable.ic_provider_{pid}')
    kt += [
        "        else -> null",
        "    }",
        "}",
        "",
    ]
    kt_path = os.path.join(
        root,
        "core/designsystem/src/main/java/cc/jaxy/bob/core/designsystem"
        "/component/ProviderIconMap.kt",
    )
    open(kt_path, "w").write("\n".join(kt))
    print(f"wrote {kt_path}")

    # ModelIconMap.kt: ordered (regex, drawable) pairs, web match order.
    def kt_escape(s):
        return s.replace("\\", "\\\\").replace('"', '\\"').replace("$", "\\$")
    mk = [
        "package cc.jaxy.bob.core.designsystem.component",
        "",
        "import androidx.annotation.DrawableRes",
        "import cc.jaxy.bob.core.designsystem.R",
        "",
        "/** Generated by `scripts/sync_provider_icons.py`; do not hand-edit.",
        " * Ordered model-id match table from web `modelConfig.js`: first",
        " * regex hit wins (case-insensitive), mirroring `ModelIcon`. */",
        "private val MODEL_ICON_RULES: List<Triple<Regex, Int, Int>> = listOf(",
    ]
    for base, kws in model_map:
        for kw in kws:
            mk.append(
                f'    Triple(Regex("{kt_escape(kw)}", RegexOption.IGNORE_CASE), '
                f"R.drawable.ic_model_{base}, R.drawable.ic_model_{base}_dark),"
            )
    mk += [
        ")",
        "",
        "/** Brand icon for a model id, or null when no rule matches. */",
        "@DrawableRes",
        "fun modelBrandIcon(modelId: String, dark: Boolean = false): Int? {",
        "    val id = modelId.lowercase()",
        "    val hit = MODEL_ICON_RULES.firstOrNull { (regex, _, _) -> ",
        "regex.containsMatchIn(id) }",
        "    return if (dark) hit?.third else hit?.second",
        "}",
        "",
    ]
    mk_path = os.path.join(
        root,
        "core/designsystem/src/main/java/cc/jaxy/bob/core/designsystem"
        "/component/ModelIconMap.kt",
    )
    open(mk_path, "w").write("\n".join(mk))
    total_kb = sum(
        os.path.getsize(os.path.join(day, f))
        for f in os.listdir(day)
        if f.startswith("ic_provider_") or f.startswith("ic_model_")
    ) // 1024
    print(f"icons: {len(ok)} ok, missing: {miss}, size: ~{total_kb} KB")
    print(f"wrote {mk_path}")


if __name__ == "__main__":
    main()
