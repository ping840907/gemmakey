# 模型安裝說明

## 下載模型

從 HuggingFace 的 LiteRT Community 下載：

```
https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm
```

下載 **`model.litertlm`** 檔案（LiteRT-LM 格式，非舊版 `.task`）。

## 放置模型

將模型放入 app assets（需重新 build），或直接推送到裝置：

**方法 A：放入 assets（隨 APK 打包）**
```
app/src/main/assets/gemma4/model.litertlm
```

**方法 B：adb 直接推送到裝置內部儲存**
```bash
adb push model.litertlm /data/data/com.gemmakey/files/model.litertlm
```

方法 B 適合開發測試，不需要重新 build APK。

## 硬體需求

| 項目 | 需求 |
|------|------|
| RAM  | ≥ 6 GB |
| 儲存 | ≥ 4 GB 可用 |
| Android | ≥ 8.0 (API 26) |
| 建議 SoC | Snapdragon 8 Gen 2+ / Dimensity 9000+ |

## 加速後端說明

App 使用 **LiteRT-LM**（取代已廢棄的 NNAPI），自動依序嘗試：

```
NPU（Qualcomm QNN / MediaTek NeuroPilot）
  ↓ 不支援時 fallback
GPU（OpenCL / Vulkan）
  ↓ 不支援時 fallback
CPU（XNNPACK）
```

| 後端 | 適用裝置 | 速度倍率 |
|------|---------|---------|
| NPU  | Snapdragon 旗艦 / Dimensity 旗艦 | ~100x CPU |
| GPU  | 幾乎所有 Android 裝置 | ~10x CPU |
| CPU  | 所有裝置（最終 fallback） | 基準 |

目前狀態列會顯示實際使用的後端。
