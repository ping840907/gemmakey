# 模型安裝說明

## 下載模型

從 HuggingFace 的 LiteRT Community 下載：

```
https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm
```

下載 **`model.litertlm`** 檔案（LiteRT-LM 格式，非舊版 `.task`）。

## 放置模型

> ⚠️ **不可放入 `app/src/main/assets/`**  
> Gemma 4 模型約 3 GB，AGP 的 `CompressAssetsTask` 會用 `Files.readAllBytes()` 讀取所有 asset 檔案，超出 JVM 陣列大小上限會直接 OOM 建置失敗。

### 方法 A：外部 App 專屬儲存（推薦，免重新 build）

```bash
# App 安裝後才能推送（目錄自動建立）
adb push model.litertlm /sdcard/Android/data/com.gemmakey/files/model.litertlm
```

此路徑對應 `context.getExternalFilesDir(null)`，**不需要任何 Storage 權限**，App 解除安裝時自動清除。

### 方法 B：內部 App 儲存

```bash
adb push model.litertlm /data/data/com.gemmakey/files/model.litertlm
```

需要 root 或 `adb shell run-as com.gemmakey`。

### 方法 C：開發者暫存路徑

```bash
adb push model.litertlm /data/local/tmp/model.litertlm
```

適合快速測試，不需要 App 已安裝。

## 解析優先序

App 啟動時依以下順序尋找模型：

1. `/sdcard/Android/data/com.gemmakey/files/model.litertlm` （外部 App 目錄）
2. `/data/data/com.gemmakey/files/model.litertlm` （內部 App 目錄）
3. `/data/local/tmp/model.litertlm` （開發者暫存）

## 硬體需求

| 項目 | 需求 |
|------|------|
| RAM  | ≥ 6 GB |
| 儲存 | ≥ 4 GB 可用 |
| Android | ≥ 12 (API 31) — LiteRT-LM 0.11.0 最低要求 |
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
